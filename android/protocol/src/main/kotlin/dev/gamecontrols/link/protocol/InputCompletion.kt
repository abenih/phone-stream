package dev.gamecontrols.link.protocol

import java.util.concurrent.atomic.AtomicBoolean

class InputCompletion(private val callback: (Boolean) -> Unit) {
    private val finished = AtomicBoolean(false)
    fun finish(accepted: Boolean) {
        if (finished.compareAndSet(false, true)) callback(accepted)
    }
}
