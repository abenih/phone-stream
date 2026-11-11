package dev.gamecontrols.link.protocol

import java.util.ArrayDeque

class ManualTouchQueue {
    private data class Pending(val kind: InputKind, val point: Point, val at: Long)
    private val pending = ArrayDeque<Pending>()
    private var grant: Packet.Grant? = null
    private var sequence = 0L
    private var inFlight: Packet.Input? = null
    private var sentAt = 0L
    private var held = false
    private var point = Point(0f, 0f)
    private var holdStarted = 0L

    fun grant(value: Packet.Grant) {
        reset()
        grant = value
    }

    fun touch(kind: InputKind, value: Point, nowMs: Long) {
        check(grant != null) { "Control has not been granted." }
        require(value.x.isFinite() && value.y.isFinite() && value.x in 0f..1f && value.y in 0f..1f)
        if (kind == InputKind.DOWN) {
            check(!held) { "Only one finger is supported." }
            held = true
            holdStarted = nowMs
        } else if (kind == InputKind.UP) {
            check(held) { "No manual touch to release." }
            held = false
        } else if (kind == InputKind.MOVE) {
            check(held) { "No manual touch to move." }
            if (pending.peekLast()?.kind == InputKind.MOVE) pending.removeLast()
        } else {
            cancel(nowMs)
            return
        }
        point = value
        if (pending.size >= 8) throw ProtocolException("Manual input backlog exceeded its bound.")
        pending.addLast(Pending(kind, value, nowMs))
    }

    fun cancel(nowMs: Long) {
        held = false
        pending.clear()
        if (grant != null) pending.addLast(Pending(InputKind.RELEASE, point, nowMs))
    }

    fun next(nowMs: Long): Packet.Input? {
        val selected = grant ?: return null
        if (inFlight != null) {
            if (nowMs < sentAt || nowMs - sentAt >= 300) throw ProtocolException("Input acknowledgement timed out.")
            return null
        }
        if (held && nowMs - holdStarted >= 10_000) {
            throw ProtocolException("The initial slice's 10-second single-finger hold limit was reached.")
        }
        val next = pending.pollFirst() ?: if (held && nowMs - sentAt >= 50)
            Pending(InputKind.MOVE, point, nowMs) else return null
        if (nowMs < next.at || nowMs - next.at >= 250) throw ProtocolException("Manual input became stale.")
        val event = Packet.Input(selected.id, selected.epoch, ++sequence, next.kind, next.point.x, next.point.y)
        inFlight = event
        sentAt = nowMs
        return event
    }

    fun acknowledge(packet: Packet.Ack) {
        if (packet.sequence != inFlight?.sequence || !packet.accepted) {
            throw ProtocolException("Host rejected or misordered manual input.")
        }
        inFlight = null
    }

    fun reset() {
        grant = null
        sequence = 0
        inFlight = null
        pending.clear()
        held = false
    }
}
