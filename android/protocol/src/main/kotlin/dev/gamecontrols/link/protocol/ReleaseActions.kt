package dev.gamecontrols.link.protocol

object ReleaseActions {
    fun run(vararg actions: () -> Unit): RuntimeException? {
        var failure: RuntimeException? = null
        for (action in actions) {
            try {
                action()
            } catch (error: RuntimeException) {
                // Cleanup must attempt every owned resource, while preserving every failure for the caller.
                val previous = failure
                if (previous == null) failure = error else previous.addSuppressed(error)
            }
        }
        return failure
    }
}
