package dev.gamecontrols.link.protocol

data class ReceivedInput(val sessionId: Long, val packet: Packet.Input, val receivedAtMs: Long) {
    fun mayDispatch(nowMs: Long, currentSessionId: Long, owningConnectionOpen: Boolean): Boolean =
        sessionId == currentSessionId && sessionId > 0 && owningConnectionOpen &&
            nowMs >= receivedAtMs && nowMs - receivedAtMs < MAX_DISPATCH_DELAY_MS

    companion object { const val MAX_DISPATCH_DELAY_MS = 150L }
}
