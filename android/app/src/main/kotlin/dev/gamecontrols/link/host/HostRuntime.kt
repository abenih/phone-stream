package dev.gamecontrols.link.host

import android.app.KeyguardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.gamecontrols.link.LinkApp
import dev.gamecontrols.link.media.DisplaySnapshot
import dev.gamecontrols.link.net.HostIdentity
import dev.gamecontrols.link.net.LanBinding
import dev.gamecontrols.link.protocol.FramedConnection
import dev.gamecontrols.link.protocol.Geometry
import dev.gamecontrols.link.protocol.InputGate
import dev.gamecontrols.link.protocol.InputCompletion
import dev.gamecontrols.link.protocol.Invitation
import dev.gamecontrols.link.protocol.InvitationGate
import dev.gamecontrols.link.protocol.Packet
import dev.gamecontrols.link.protocol.PairingHandshake
import dev.gamecontrols.link.protocol.ProtocolException
import dev.gamecontrols.link.protocol.SessionFence
import dev.gamecontrols.link.protocol.ReceivedInput
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HostPhase { IDLE, OPENING, INVITING, PAIRED, STARTING, STREAMING, STOPPING }

data class HostState(
    val phase: HostPhase = HostPhase.IDLE,
    val status: String = "Idle. Nothing is capturing, controlling, or listening.",
    val invitation: Invitation? = null,
    val sessionId: Long = 0,
    val geometry: Geometry? = null,
    val controlTarget: String? = null,
    val controlAvailable: Boolean = false,
    val controlReady: Boolean = false,
)

class HostRuntime(private val app: LinkApp) {
    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(HostState())
    val state = mutableState.asStateFlow()
    private val fence = SessionFence()
    private var ticket = 0L
    private val sockets = Any()
    private var listener: ServerSocket? = null
    private var handshaking: Socket? = null
    @Volatile private var connection: FramedConnection? = null
    private var invitationGate: InvitationGate? = null
    private var capture: CaptureService? = null
    private val input = InputGate()
    private val pendingInputs = AtomicInteger()
    private var target: String? = null
    private var pendingTarget: String? = null
    private var targetDeadline = 0L
    private var phaseDeadline = 0L
    private var awaitingProjection = false
    private var geometrySafe = false

    fun openInvitation(binding: LanBinding) {
        checkMain()
        if (state.value.phase != HostPhase.IDLE) {
            report("Stop the current session and wait for resource cleanup before opening another invitation.")
            return
        }
        ticket = fence.begin()
        val owner = ticket
        phaseDeadline = now() + 120_000
        mutableState.value = HostState(HostPhase.OPENING, "Preparing a new private, one-use invitation.")
        main.removeCallbacks(tick)
        main.post(tick)
        Thread({ listen(owner, binding) }, "GameLink-pair").apply { isDaemon = true; start() }
    }

    private fun listen(owner: Long, binding: LanBinding) {
        try {
            check(binding.isAvailable(app)) { "Selected LAN interface is unavailable." }
            val identity = HostIdentity()
            val invitation = Invitation(requireNotNull(binding.address.hostAddress), PORT, identity.pin, Invitation.newToken())
            val gate = InvitationGate(invitation.token, now())
            val server = identity.context.serverSocketFactory.createServerSocket() as SSLServerSocket
            synchronized(sockets) {
                if (!fence.accepts(owner)) { server.close(); return }
                listener = server
                invitationGate = gate
            }
            server.enabledProtocols = arrayOf("TLSv1.3")
            server.bind(InetSocketAddress(binding.address, PORT), 1)
            main.post {
                if (fence.accepts(owner)) mutableState.value = state.value.copy(phase = HostPhase.INVITING,
                    status = "Invitation open for up to 2 minutes on $binding. Share it privately; verify the full pin.",
                    invitation = invitation)
            }
            var attempts = 0
            while (attempts < 3 && fence.accepts(owner)) {
                val socket = server.accept() as SSLSocket
                attempts++
                synchronized(sockets) {
                    if (!fence.accepts(owner)) { socket.close(); return }
                    handshaking = socket
                }
                var transferred = false
                val deadline = Runnable { closeHandshake(socket) }
                main.postDelayed(deadline, 3000)
                try {
                    check(binding.permits(socket.inetAddress) && binding.isAvailable(app)) {
                        "Peer is not on the selected LAN subnet."
                    }
                    val sessionId = PairingHandshake.host(socket, gate, ::now)
                    val peer = FramedConnection(socket,
                        { receive(sessionId, it) },
                        { reason -> main.post { stopIfSession(sessionId, reason) } },
                        { binding.isAvailable(app) })
                    server.close()
                    synchronized(sockets) {
                        if (listener === server) listener = null
                    }
                    transferred = true
                    main.post {
                        if (!fence.accepts(owner)) {
                            peer.close("Pairing was cancelled.")
                        } else {
                            synchronized(sockets) { if (handshaking === socket) handshaking = null }
                            connection = peer
                            phaseDeadline = now() + 120_000
                            mutableState.value = HostState(HostPhase.PAIRED,
                                "Client authenticated on ${socket.inetAddress.hostAddress}. View only; press Start screen sharing.",
                                sessionId = sessionId, controlAvailable = app.accessibility?.canControl == true)
                            try {
                                peer.start()
                            } catch (error: IOException) {
                                stop("Connection could not start: ${error.javaClass.simpleName}.")
                            }
                        }
                    }
                    return
                } catch (error: IOException) {
                    rejected(owner, error)
                } catch (error: IllegalStateException) {
                    rejected(owner, error)
                } finally {
                    main.removeCallbacks(deadline)
                    if (!transferred) closeHandshake(socket)
                }
            }
            main.post { if (fence.accepts(owner)) stop("Pairing closed after three rejected attempts.") }
        } catch (error: IOException) {
            failOwner(owner, "LAN listener failed", error)
        } catch (error: GeneralSecurityException) {
            failOwner(owner, "Host identity or TLS failed", error)
        } catch (error: IllegalStateException) {
            failOwner(owner, "Host setup failed", error)
        } catch (error: IllegalArgumentException) {
            failOwner(owner, "Host setup was invalid", error)
        } catch (error: SecurityException) {
            failOwner(owner, "Android denied host setup", error)
        }
    }

    private fun rejected(owner: Long, error: Exception) {
        if (!fence.accepts(owner)) return
        Log.w("GameLink", "Pairing attempt rejected: ${error.javaClass.simpleName}")
        main.post { if (fence.accepts(owner)) report("A pairing attempt was rejected. The same invitation remains open within its deadline.") }
    }

    private fun failOwner(owner: Long, label: String, error: Exception) {
        if (!fence.accepts(owner)) return
        Log.e("GameLink", label, error)
        main.post { if (fence.accepts(owner)) stop("$label: ${error.javaClass.simpleName}.") }
    }

    private fun closeHandshake(socket: Socket) {
        synchronized(sockets) {
            if (handshaking !== socket) return
            handshaking = null
        }
        closeSocket(socket)
    }

    fun requestProjection(): Long {
        checkMain()
        check(state.value.phase == HostPhase.PAIRED) { "Pair a Client before requesting screen capture." }
        awaitingProjection = true
        phaseDeadline = now() + 30_000
        report("Waiting for your Android screen-sharing consent.")
        return state.value.sessionId
    }

    fun startCapture(sessionId: Long, resultCode: Int, data: Intent) {
        checkMain()
        if (!matchesSession(sessionId) || !awaitingProjection || state.value.phase != HostPhase.PAIRED) {
            report("Stale screen consent was discarded. Start again explicitly.")
            return
        }
        awaitingProjection = false
        phaseDeadline = now() + 6000
        mutableState.value = state.value.copy(phase = HostPhase.STARTING, status = "Starting visible screen capture; control is off.")
        try {
            ContextCompat.startForegroundService(app, Intent(app, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_SESSION, sessionId)
                .putExtra(CaptureService.EXTRA_RESULT, resultCode)
                .putExtra(CaptureService.EXTRA_CONSENT, data))
        } catch (error: IllegalStateException) {
            Log.e("GameLink", "Foreground capture start denied", error)
            stop("Android did not allow foreground capture to start.")
        } catch (error: SecurityException) {
            Log.e("GameLink", "Capture permission denied", error)
            stop("Android denied capture permission.")
        }
    }

    fun attachCapture(sessionId: Long, service: CaptureService): Boolean {
        checkMain()
        if (!matchesSession(sessionId) || state.value.phase != HostPhase.STARTING || capture != null) return false
        capture = service
        return true
    }

    fun sendCapture(sessionId: Long, packet: Packet): Boolean {
        val current = state.value
        return current.sessionId == sessionId && current.phase in setOf(HostPhase.STARTING, HostPhase.STREAMING) &&
            connection?.send(packet) == true
    }

    fun captureConfigured(sessionId: Long, geometry: Geometry, controlSafe: Boolean) {
        if (!matchesSession(sessionId)) return
        geometrySafe = controlSafe
        mutableState.value = state.value.copy(geometry = geometry)
    }

    fun captureRunning(sessionId: Long) {
        if (!matchesSession(sessionId)) return
        mutableState.value = state.value.copy(phase = HostPhase.STREAMING,
            controlReady = geometrySafe && app.accessibility?.canControl == true,
            status = "Screen stream active; audio off; control off." +
                if (geometrySafe) " You may separately allow one selected app." else " This Android 12 capture geometry is view-only.")
    }

    fun captureClosed(sessionId: Long, service: CaptureService, error: String?) {
        checkMain()
        if (capture !== service) return
        capture = null
        if (matchesSession(sessionId)) stop(error ?: "Screen capture ended.")
        else if (state.value.phase == HostPhase.STOPPING) {
            mutableState.value = HostState(status = error ?: state.value.status + " Capture resources released.")
        }
        if (error != null) report(error)
    }

    fun requestControl(packageName: String) {
        checkMain()
        check(state.value.phase == HostPhase.STREAMING && geometrySafe && app.accessibility?.canControl == true) {
            "Control needs active full-display video and manually enabled Accessibility."
        }
        check(target == null && pendingTarget == null) { "Revoke the existing control grant first." }
        check(packageName != app.packageName) { "This app cannot control its own consent screens." }
        pendingTarget = packageName
        targetDeadline = now() + 10_000
        mutableState.value = state.value.copy(controlReady = false,
            status = "Waiting for the locally selected app to be foreground and unobscured. Control is still off.")
    }

    fun checkControlWindow() {
        checkMain()
        val service = app.accessibility
        val waiting = pendingTarget
        if (waiting != null && state.value.phase == HostPhase.STREAMING &&
            geometrySafe && service?.safeTarget(waiting) == true) {
            pendingTarget = null
            target = waiting
            val geometry = requireNotNull(state.value.geometry)
            val grant = PairingHandshake.newId()
            input.grant(grant, geometry.epoch, now())
            if (connection?.send(Packet.Grant(grant, geometry.epoch, waiting)) != true) {
                stop("Connection ended before control could be granted.")
                return
            }
            mutableState.value = state.value.copy(controlTarget = waiting,
                status = "Streaming. One-finger manual control allowed only for $waiting.")
            capture?.updateControlNotification(waiting)
        } else if (target != null && service?.safeTarget(requireNotNull(target)) != true) {
            stop("Control target left the foreground or was obscured. Session stopped.")
        }
    }

    fun revokeControl(reason: String) {
        checkMain()
        pendingTarget = null
        target = null
        input.revoke()
        app.accessibility?.releaseInputs()
        if (matchesSession(state.value.sessionId)) connection?.send(Packet.Revoke(reason))
        mutableState.value = state.value.copy(controlTarget = null, status = reason + " Control is off.",
            controlReady = state.value.phase == HostPhase.STREAMING && geometrySafe && app.accessibility?.canControl == true)
        capture?.updateControlNotification(null)
    }

    fun accessibilityChanged() {
        checkMain()
        mutableState.value = state.value.copy(controlAvailable = app.accessibility?.canControl == true,
            controlReady = state.value.phase == HostPhase.STREAMING && geometrySafe &&
                app.accessibility?.canControl == true && target == null && pendingTarget == null)
        if (app.accessibility == null && (input.isGranted || pendingTarget != null)) {
            stop("Accessibility permission or service was lost.")
        }
    }

    private fun receive(sessionId: Long, packet: Packet) {
        if (packet !is Packet.Input) throw ProtocolException("Client sent a packet outside its permitted role.")
        val owner = connection ?: throw ProtocolException("Input connection no longer exists.")
        val received = ReceivedInput(sessionId, packet, now())
        if (pendingInputs.incrementAndGet() > 1) {
            pendingInputs.decrementAndGet()
            throw ProtocolException("Only one acknowledged input may be in flight.")
        }
        main.post {
            val completion = InputCompletion { success ->
                pendingInputs.decrementAndGet()
                if (matchesSession(sessionId) && owner === connection && !owner.isClosed && input.grantId == packet.grantId) {
                    owner.send(Packet.Ack(packet.sequence, success))
                    if (!success) stop("Android declined manual input. Use the Host locally.")
                }
            }
            if (!matchesSession(sessionId)) {
                completion.finish(false)
                return@post
            }
            if (!received.mayDispatch(now(), state.value.sessionId, owner === connection && !owner.isClosed)) {
                completion.finish(false)
                stop("Queued manual input expired or its connection closed before dispatch.")
                return@post
            }
            try {
                val selected = target
                val service = app.accessibility
                val geometry = state.value.geometry
                val current = DisplaySnapshot.read(app)
                if (selected == null || service == null || geometry == null || !geometrySafe ||
                    current.rotation != geometry.rotation || current.width != geometry.displayWidth ||
                    current.height != geometry.displayHeight || !service.safeTarget(selected) ||
                    !input.accept(packet, now())) {
                    completion.finish(false)
                    stop("Manual input was stale, unapproved, or unsafe for the current window.")
                    return@post
                }
                service.execute(packet, geometry, {
                    matchesSession(sessionId) && input.grantId == packet.grantId &&
                        received.mayDispatch(now(), state.value.sessionId, owner === connection && !owner.isClosed)
                }, completion::finish)
            } catch (error: IllegalStateException) {
                Log.w("GameLink", "Input preflight failed", error)
                completion.finish(false)
                stop("Manual input was stale, unapproved, or unsafe for the current window.")
            } catch (error: SecurityException) {
                Log.w("GameLink", "Android denied input preflight", error)
                completion.finish(false)
                stop("Android denied manual input.")
            }
        }
    }

    fun hostUiStopped() {
        checkMain()
        if (state.value.phase in setOf(HostPhase.OPENING, HostPhase.INVITING) ||
            (state.value.phase == HostPhase.PAIRED && !awaitingProjection)) {
            stop("Host pairing screen was backgrounded before capture.")
        }
    }

    fun stopIfSession(sessionId: Long, reason: String) {
        if (matchesSession(sessionId)) stop(reason)
    }

    fun matchesSession(sessionId: Long) = sessionId > 0 && state.value.sessionId == sessionId && fence.accepts(ticket)

    fun stop(reason: String) {
        checkMain()
        if (!fence.stop()) return
        main.removeCallbacks(tick)
        invitationGate?.revoke()
        invitationGate = null
        pendingTarget = null
        target = null
        geometrySafe = false
        awaitingProjection = false
        input.revoke()
        val ownedCapture = capture
        val sessionId = state.value.sessionId
        mutableState.value = HostState(if (ownedCapture == null) HostPhase.IDLE else HostPhase.STOPPING,
            reason, sessionId = if (ownedCapture == null) 0 else sessionId)
        app.accessibility?.releaseInputs()
        val peer = connection
        connection = null
        peer?.close(reason)
        val owned = synchronized(sockets) {
            val pair = listener to handshaking
            listener = null
            handshaking = null
            pair
        }
        try {
            owned.first?.close()
        } catch (error: IOException) {
            Log.e("GameLink", "Listener close failed", error)
            report("$reason Listener close reported an error.")
        }
        owned.second?.let(::closeSocket)
        ownedCapture?.stopOwnedCapture()
    }

    fun report(message: String) {
        checkMain()
        mutableState.value = state.value.copy(status = message)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!fence.accepts(ticket)) return
            val phase = state.value.phase
            when {
                phase in setOf(HostPhase.OPENING, HostPhase.INVITING, HostPhase.PAIRED, HostPhase.STARTING) &&
                    now() >= phaseDeadline -> stop("Pairing, consent, or encoder startup deadline expired.")
                phase == HostPhase.STREAMING && (!deviceAvailable() || !CaptureService.notificationsVisible(app)) ->
                    stop("Host locked, screen turned off, or visible capture notification was disabled.")
                input.expired(now()) -> stop("Control grant or held-finger deadline expired.")
                pendingTarget != null && now() >= targetDeadline -> revokeControl("The selected app did not become ready in time.")
                phase == HostPhase.STREAMING -> checkControlWindow()
            }
            if (fence.accepts(ticket)) main.postDelayed(this, 50)
        }
    }

    private fun deviceAvailable(): Boolean {
        val lock = app.getSystemService(KeyguardManager::class.java)
        return app.getSystemService(PowerManager::class.java).isInteractive && !lock.isKeyguardLocked && !lock.isDeviceLocked
    }

    private fun closeSocket(socket: Socket) {
        try {
            socket.close()
        } catch (error: IOException) {
            Log.e("GameLink", "Owned socket close failed", error)
            main.post { report("An owned connection reported an error while closing.") }
        }
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Host state must be changed on the main thread." }
    private fun now() = SystemClock.elapsedRealtime()

    companion object { const val PORT = 48765 }
}
