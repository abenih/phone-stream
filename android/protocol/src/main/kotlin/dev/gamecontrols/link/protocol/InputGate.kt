package dev.gamecontrols.link.protocol

class InputGate {
    var grantId: Long? = null
        private set
    var held = false
        private set
    val isGranted get() = grantId != null
    private var epoch = 0
    private var sequence = 0L
    private var grantedAt = 0L
    private var lastInputAt = 0L
    private var holdStartedAt = 0L

    @Synchronized
    fun grant(id: Long, epoch: Int, nowMs: Long) {
        require(id > 0 && epoch > 0 && nowMs >= 0)
        revoke()
        grantId = id
        this.epoch = epoch
        grantedAt = nowMs
        lastInputAt = nowMs
    }

    @Synchronized
    fun accept(input: Packet.Input, nowMs: Long): Boolean {
        if (!isGranted) return false
        if (expired(nowMs)) { revoke(); return false }
        if (input.grantId != grantId || input.epoch != epoch || input.sequence <= sequence ||
            !input.x.isFinite() || !input.y.isFinite() || input.x !in 0f..1f || input.y !in 0f..1f) return false
        when (input.kind) {
            InputKind.DOWN -> if (held) return false else {
                held = true
                holdStartedAt = nowMs
            }
            InputKind.MOVE -> if (!held) return false
            InputKind.UP -> if (!held) return false else held = false
            InputKind.RELEASE -> held = false
        }
        sequence = input.sequence
        lastInputAt = nowMs
        return true
    }

    @Synchronized
    fun expired(nowMs: Long): Boolean = isGranted && (nowMs < lastInputAt ||
        nowMs - grantedAt >= GRANT_LIFETIME_MS ||
        (held && (nowMs - lastInputAt >= HOLD_TIMEOUT_MS || nowMs - holdStartedAt >= MAX_HOLD_MS)))

    @Synchronized
    fun revoke() {
        grantId = null
        held = false
        epoch = 0
        sequence = 0
        holdStartedAt = 0
    }

    companion object {
        const val GRANT_LIFETIME_MS = 600_000L
        const val HOLD_TIMEOUT_MS = 350L
        const val MAX_HOLD_MS = 10_000L
    }
}
