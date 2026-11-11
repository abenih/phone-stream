package dev.gamecontrols.link.protocol

import java.util.ArrayDeque

class OutboundQueue(
    private val maxVideos: Int = 4,
    private val maxBytes: Int = 2 * 1024 * 1024,
    private val maxControls: Int = 32,
    private val maxAgeMs: Long = 500,
) {
    private data class Queued(val packet: Packet, val time: Long)
    private val controls = ArrayDeque<Queued>()
    private val videos = ArrayDeque<Queued>()
    private var bytes = 0

    @Synchronized
    fun offer(packet: Packet, nowMs: Long): Boolean {
        Wire.validate(packet)
        if (packet is Packet.Video) {
            if (videos.size >= maxVideos || bytes + packet.bytes.size > maxBytes) return false
            videos.addLast(Queued(packet, nowMs))
            bytes += packet.bytes.size
        } else {
            if (controls.size >= maxControls) return false
            controls.addLast(Queued(packet, nowMs))
        }
        return true
    }

    @Synchronized
    fun take(nowMs: Long): Packet? {
        val oldest = videos.peekFirst()
        if (oldest != null && (nowMs < oldest.time || nowMs - oldest.time >= maxAgeMs)) {
            throw ProtocolException("Video backlog expired. Session must stop.")
        }
        controls.pollFirst()?.let { return it.packet }
        val next = videos.pollFirst()?.packet as? Packet.Video ?: return null
        bytes -= next.bytes.size
        return next
    }

    @Synchronized
    fun clear() {
        controls.clear()
        videos.clear()
        bytes = 0
    }
}
