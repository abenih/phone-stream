package dev.gamecontrols.link.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.gamecontrols.link.LinkApp
import dev.gamecontrols.link.R
import dev.gamecontrols.link.databinding.ActivityViewerBinding
import dev.gamecontrols.link.media.SurfaceDecoder
import dev.gamecontrols.link.net.HostIdentity
import dev.gamecontrols.link.net.LanBinding
import dev.gamecontrols.link.protocol.FramedConnection
import dev.gamecontrols.link.protocol.Geometry
import dev.gamecontrols.link.protocol.InputKind
import dev.gamecontrols.link.protocol.Invitation
import dev.gamecontrols.link.protocol.LanAddress
import dev.gamecontrols.link.protocol.ManualTouchQueue
import dev.gamecontrols.link.protocol.Packet
import dev.gamecontrols.link.protocol.PairingHandshake
import dev.gamecontrols.link.protocol.ProtocolException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import kotlin.math.roundToInt

class ViewerActivity : ComponentActivity(), SurfaceHolder.Callback {
    private lateinit var binding: ActivityViewerBinding
    private var invitation: Invitation? = null
    private val main = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)
    private val sockets = Any()
    private var connectingSocket: Socket? = null
    @Volatile private var connection: FramedConnection? = null
    @Volatile private var decoder: SurfaceDecoder? = null
    @Volatile private var geometry: Geometry? = null
    private val manual = ManualTouchQueue()
    private var grant: Packet.Grant? = null
    private var rendered = false
    private var attempted = false
    private var tracking = false
    private var ignoreGesture = false
    private var hadFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val app = application as LinkApp
        invitation = app.pendingClientInvitation
        app.pendingClientInvitation = null
        binding.disconnect.setOnClickListener { stop("Client disconnected."); finish() }
        binding.releaseInput.setOnClickListener { cancelTouch() }
        binding.touchLayer.setOnTouchListener(::touch)
        binding.video.holder.addCallback(this)
        binding.videoContainer.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) {
                if (oldR > oldL && oldB > oldT) cancelTouch()
                fitVideo()
            }
        }
        onBackPressedDispatcher.addCallback(this) { stop("Client returned to setup."); finish() }
        if (invitation == null) {
            stop("No in-memory invitation. Return to setup; this session cannot auto-resume.")
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (attempted || stopped.get()) return
        val selected = invitation ?: return
        invitation = null
        attempted = true
        decoder = SurfaceDecoder(holder.surface, { main.post {
            if (!stopped.get()) {
                rendered = true
                binding.releaseInput.isEnabled = grant != null
                binding.status.text = grant?.let { getString(R.string.viewer_manual_control, it.target) }
                    ?: getString(R.string.viewer_live_view_only)
            }
        } }, { reason -> main.post {
            if (!stopped.get()) stop(reason) else binding.status.text = reason
        } })
        main.postDelayed(connectDeadline, 6000)
        Thread({ connect(selected) }, "GameLink-connect").apply { isDaemon = true; start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) = stop("Video surface was destroyed.")

    private fun connect(invitation: Invitation) {
        try {
            val host = LanAddress.parseIpv4(invitation.host)
            val binding = LanBinding.client(this, host)
            check(binding.isAvailable(this)) { "Selected LAN is unavailable." }
            val plain = requireNotNull(binding.network).socketFactory.createSocket()
            if (!own(plain)) return
            plain.connect(InetSocketAddress(host, invitation.port), 2000)
            val socket = HostIdentity.clientContext(invitation.pin).socketFactory
                .createSocket(plain, invitation.host, invitation.port, true) as SSLSocket
            if (!own(socket)) return
            PairingHandshake.client(socket, invitation)
            val peer = FramedConnection(socket, ::receive,
                { reason -> main.post { stop(reason) } }, { binding.isAvailable(this) })
            main.post {
                main.removeCallbacks(connectDeadline)
                if (stopped.get()) {
                    peer.close("Client was backgrounded before pairing completed.")
                } else {
                    connection = peer
                    synchronized(sockets) { connectingSocket = null }
                    bindingStatus("Paired and pinned. Waiting for the Host to grant screen-sharing consent.")
                    try {
                        peer.start()
                        main.post(inputTick)
                    } catch (error: IOException) {
                        stop("Connection could not start: ${error.javaClass.simpleName}.")
                    }
                }
            }
        } catch (error: IOException) {
            connectionFailure(error)
        } catch (error: GeneralSecurityException) {
            connectionFailure(error)
        } catch (error: IllegalStateException) {
            connectionFailure(error)
        } catch (error: IllegalArgumentException) {
            connectionFailure(error)
        } catch (error: SecurityException) {
            connectionFailure(error)
        }
    }

    private fun own(socket: Socket): Boolean {
        synchronized(sockets) {
            if (stopped.get()) {
                socket.close()
                return false
            }
            connectingSocket = socket
            return true
        }
    }

    private fun connectionFailure(error: Exception) {
        Log.w("GameLink", "Client connection failed", error)
        main.post { stop("Connection failed: ${error.javaClass.simpleName}. Check the invitation, pin, and existing LAN.") }
    }

    private fun receive(packet: Packet) {
        if (stopped.get()) return
        when (packet) {
            is Packet.Config -> {
                if (geometry != null) throw ProtocolException("Host attempted to reconfigure without a fresh session.")
                val target = decoder ?: throw ProtocolException("Video surface is unavailable.")
                target.configure(packet)
                geometry = packet.geometry
                main.post { if (!stopped.get()) fitVideo() }
            }
            is Packet.Video -> (decoder ?: throw ProtocolException("Decoder is unavailable.")).offer(packet)
            is Packet.Grant -> {
                if (packet.epoch != geometry?.epoch) throw ProtocolException("Control grant has stale screen geometry.")
                main.post {
                    if (stopped.get()) return@post
                    if (grant != null) { stop("Host replaced a grant without revocation."); return@post }
                    grant = packet
                    manual.grant(packet)
                    tracking = false
                    binding.releaseInput.isEnabled = rendered
                    binding.status.text = getString(R.string.viewer_manual_control, packet.target)
                }
            }
            is Packet.Revoke -> main.post {
                if (!stopped.get()) {
                    grant = null
                    manual.reset()
                    tracking = false
                    binding.releaseInput.isEnabled = false
                    binding.status.text = getString(R.string.viewer_revoked, packet.reason)
                }
            }
            is Packet.Ack -> main.post {
                if (!stopped.get()) {
                    try { manual.acknowledge(packet) }
                    catch (error: ProtocolException) { stop(requireNotNull(error.message)) }
                }
            }
            else -> throw ProtocolException("Host sent a packet outside its permitted role.")
        }
    }

    private fun fitVideo() {
        val current = geometry ?: return
        val width = binding.videoContainer.width
        val height = binding.videoContainer.height
        if (width <= 0 || height <= 0) return
        val box = current.videoRect(width, height)
        binding.video.layoutParams = FrameLayout.LayoutParams(box.width.roundToInt(), box.height.roundToInt(), Gravity.CENTER)
    }

    private fun touch(view: View, event: MotionEvent): Boolean {
        if (stopped.get() || !rendered || grant == null) return true
        val current = geometry ?: return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) ignoreGesture = false
        if (ignoreGesture) return true
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            cancelTouch()
            ignoreGesture = true
            binding.status.setText(R.string.viewer_no_multitouch)
            return true
        }
        val point = current.mapTouch(event.x, event.y, binding.videoContainer.width, binding.videoContainer.height)
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (point != null) {
                    manual.touch(InputKind.DOWN, point, now())
                    tracking = true
                }
                MotionEvent.ACTION_MOVE -> if (tracking) {
                    if (point == null) { cancelTouch(); ignoreGesture = true }
                    else manual.touch(InputKind.MOVE, point, now())
                }
                MotionEvent.ACTION_UP -> {
                    view.performClick()
                    if (tracking) {
                        if (point == null) cancelTouch() else manual.touch(InputKind.UP, point, now())
                    }
                    tracking = false
                }
                MotionEvent.ACTION_CANCEL -> cancelTouch()
            }
        } catch (error: IllegalStateException) {
            stop("Manual touch state was interrupted. Start a fresh session.")
        } catch (error: ProtocolException) {
            stop(requireNotNull(error.message))
        }
        return true
    }

    private fun cancelTouch() {
        tracking = false
        if (grant != null && !stopped.get()) manual.cancel(now())
    }

    private val inputTick = object : Runnable {
        override fun run() {
            if (stopped.get()) return
            try {
                if (rendered) manual.next(now())?.let {
                    if (connection?.send(it) != true) stop("Input transport closed.")
                }
            } catch (error: ProtocolException) {
                stop(requireNotNull(error.message))
            }
            if (!stopped.get()) main.postDelayed(this, 16)
        }
    }

    private val connectDeadline = Runnable { stop("Pairing connection deadline expired.") }

    private fun bindingStatus(message: String) { binding.status.text = message }

    private fun stop(reason: String) {
        if (!stopped.compareAndSet(false, true)) return
        main.removeCallbacks(connectDeadline)
        main.removeCallbacks(inputTick)
        invitation = null
        manual.reset()
        grant = null
        tracking = false
        binding.releaseInput.isEnabled = false
        binding.status.text = getString(R.string.viewer_stopped, reason)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        connection?.close(reason)
        connection = null
        val socket = synchronized(sockets) { connectingSocket.also { connectingSocket = null } }
        try {
            socket?.close()
        } catch (error: IOException) {
            Log.e("GameLink", "Client socket cleanup failed", error)
            binding.status.setText(R.string.viewer_socket_cleanup_error)
        }
        val owned = decoder
        decoder = null
        if (owned != null && !owned.close()) {
            Log.e("GameLink", "Decoder cleanup exceeded the one-second wait")
            binding.status.setText(R.string.viewer_decoder_cleanup_error)
        }
        binding.video.visibility = View.INVISIBLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hadFocus = true
        else if (hadFocus && attempted) stop("Client lost foreground focus.")
    }

    override fun onPause() {
        if (attempted) stop("Client was backgrounded or rotated.")
        super.onPause()
    }

    override fun onStop() {
        stop("Client left the foreground.")
        super.onStop()
    }

    override fun onDestroy() {
        stop("Client activity ended.")
        super.onDestroy()
    }

    private fun now() = SystemClock.elapsedRealtime()
}
