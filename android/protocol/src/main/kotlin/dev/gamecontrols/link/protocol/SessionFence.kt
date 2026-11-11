package dev.gamecontrols.link.protocol

class SessionFence {
    private var revision = 0L
    private var active = false

    @Synchronized
    fun begin(): Long {
        revision++
        active = true
        return revision
    }

    @Synchronized
    fun accepts(ticket: Long) = active && revision == ticket

    @Synchronized
    fun stop(): Boolean {
        if (!active) return false
        active = false
        return true
    }
}
