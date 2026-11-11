package dev.gamecontrols.link.protocol

import java.util.ArrayDeque

data class DecoderOutput(val token: Int, val ptsUs: Long)

interface DecoderDevice {
    fun queue(frame: Packet.Video): Boolean
    fun dequeue(): DecoderOutput?
    fun release(output: DecoderOutput, render: Boolean)
    fun close()
}

class VideoPump(
    private val create: (Packet.Config) -> DecoderDevice,
    private val presented: () -> Unit,
    private val maxUnits: Int = 6,
    private val maxBytes: Int = 2 * 1024 * 1024,
    private val maxAgeMs: Long = 500,
) {
    private data class Frame(val video: Packet.Video, val arrivedAt: Long)
    private val queued = ArrayDeque<Frame>()
    private val submitted = ArrayDeque<Frame>()
    private var config: Packet.Config? = null
    private var device: DecoderDevice? = null
    private var bytes = 0
    private var closing = false
    private var firstKey = false
    private var displayed = false

    @Synchronized fun configure(value: Packet.Config) {
        check(!closing && config == null) { "Decoder configuration cannot restart in this session." }
        Wire.validate(value)
        config = value
    }

    @Synchronized fun offer(video: Packet.Video, nowMs: Long) {
        check(!closing && config != null) { "Video arrived outside an active configuration." }
        Wire.validate(video)
        if (!firstKey && !video.keyFrame) throw ProtocolException("First video frame must be a keyframe.")
        if (queued.size + submitted.size >= maxUnits || bytes + video.bytes.size > maxBytes) {
            throw ProtocolException("Decoder backlog exceeded its safe bound.")
        }
        firstKey = true
        queued.addLast(Frame(video, nowMs))
        bytes += video.bytes.size
    }

    @Synchronized fun step(nowMs: Long) {
        if (closing) return
        val selected = config ?: return
        if (device == null) device = create(selected)
        val decoder = requireNotNull(device)
        val oldest = submitted.peekFirst() ?: queued.peekFirst()
        if (oldest != null && (nowMs < oldest.arrivedAt || nowMs - oldest.arrivedAt >= maxAgeMs)) {
            throw ProtocolException("Decoder frames became stale.")
        }
        drain(decoder)
        val next = queued.peekFirst()
        if (next != null && decoder.queue(next.video)) {
            queued.removeFirst()
            submitted.addLast(next)
        }
        // A codec can produce its last output long after the final input packet arrives.
        drain(decoder)
    }

    private fun drain(decoder: DecoderDevice) {
        repeat(8) {
            val output = decoder.dequeue() ?: return
            val frame = submitted.pollFirst()
            if (frame == null || frame.video.ptsUs != output.ptsUs) {
                decoder.release(output, false)
                throw ProtocolException("Unexpected or reordered decoder output.")
            }
            bytes -= frame.video.bytes.size
            decoder.release(output, true)
            if (!displayed) { displayed = true; presented() }
        }
    }

    @Synchronized fun close() {
        if (closing) return
        closing = true
        queued.clear()
        submitted.clear()
        bytes = 0
        val owned = device
        device = null
        config = null
        owned?.close()
    }
}
