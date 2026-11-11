package dev.gamecontrols.link.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class FramedConnection(
    private val socket: Socket,
    private val onPacket: (Packet) -> Unit,
    private val onClosed: (String) -> Unit,
    private val networkAvailable: () -> Boolean = { true },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val queue = OutboundQueue()
    private val writerLock = ReentrantLock()
    private val wake = writerLock.newCondition()
    private val heartbeatLock = Any()
    private var outstandingPing = 0L
    private var pingCounter = 0L
    private var lastPong = now()
    private val reader = Thread({ readLoop() }, "GameLink-read")
    private val writer = Thread({ writeLoop() }, "GameLink-write")
    private val watchdog = Thread({ watchLoop() }, "GameLink-heartbeat")
    val isClosed get() = closed.get()

    fun start() {
        check(started.compareAndSet(false, true) && !closed.get()) { "Connection cannot restart." }
        socket.tcpNoDelay = true
        socket.soTimeout = 2500
        listOf(reader, writer, watchdog).forEach { it.isDaemon = true; it.start() }
    }

    fun send(packet: Packet): Boolean {
        if (closed.get()) return false
        if (!queue.offer(packet, now())) {
            close("Transport backlog exceeded its safe bound.")
            return false
        }
        writerLock.withLock { wake.signalAll() }
        return true
    }

    fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        queue.clear()
        var reported = reason
        try {
            socket.close()
        } catch (error: IOException) {
            reported = "$reason Socket close failed: ${error.javaClass.simpleName}."
        }
        writerLock.withLock { wake.signalAll() }
        onClosed(reported)
    }

    private fun readLoop() = ioBoundary {
        val input = BufferedInputStream(socket.getInputStream(), 32 * 1024)
        while (!closed.get()) {
            when (val packet = Wire.read(input)) {
                is Packet.Ping -> send(Packet.Pong(packet.id))
                is Packet.Pong -> synchronized(heartbeatLock) {
                    if (packet.id != outstandingPing || outstandingPing == 0L) {
                        throw ProtocolException("Unexpected heartbeat response.")
                    }
                    outstandingPing = 0
                    lastPong = now()
                }
                is Packet.Stop -> close("Peer stopped the session.")
                is Packet.Hello, is Packet.Ready -> throw ProtocolException("Repeated authentication packet.")
                else -> onPacket(packet)
            }
        }
    }

    private fun writeLoop() = ioBoundary {
        val output = BufferedOutputStream(socket.getOutputStream(), 32 * 1024)
        while (!closed.get()) {
            val packet = queue.take(now())
            if (packet == null) {
                writerLock.withLock { if (!closed.get()) wake.await(10, TimeUnit.MILLISECONDS) }
            } else {
                Wire.write(output, packet)
                output.flush()
            }
        }
    }

    private fun watchLoop() = ioBoundary {
        while (!closed.get()) {
            Thread.sleep(100)
            if (closed.get()) return@ioBoundary
            if (!networkAvailable()) {
                close("Selected LAN interface was lost.")
                return@ioBoundary
            }
            synchronized(heartbeatLock) {
                val time = now()
                if (time < lastPong || time - lastPong >= 2000) {
                    close("Peer heartbeat timed out.")
                } else if (outstandingPing == 0L && time - lastPong >= 500) {
                    outstandingPing = ++pingCounter
                    send(Packet.Ping(outstandingPing))
                }
            }
        }
    }

    private inline fun ioBoundary(block: () -> Unit) {
        try {
            block()
        } catch (error: IOException) {
            close("Transport stopped: ${error.javaClass.simpleName}.")
        } catch (error: IllegalStateException) {
            close("Session state failed: ${error.message}")
        } catch (error: IllegalArgumentException) {
            close("Invalid session data: ${error.javaClass.simpleName}.")
        } catch (error: SecurityException) {
            close("Android denied access to the selected network.")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            close("Connection worker interrupted.")
        }
    }
}
