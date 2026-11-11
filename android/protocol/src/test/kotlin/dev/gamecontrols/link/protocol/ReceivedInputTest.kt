package dev.gamecontrols.link.protocol

import org.junit.Assert.*
import org.junit.Test

class ReceivedInputTest {
    private val receipt = ReceivedInput(5, Packet.Input(1, 1, 1, InputKind.DOWN), 1000)

    @Test fun initialDownCannotBecomeFreshJustBecauseDispatchWasDelayed() {
        assertTrue(receipt.mayDispatch(1149, 5, true))
        assertFalse(receipt.mayDispatch(1150, 5, true))
        assertFalse(receipt.mayDispatch(1400, 5, true))
    }

    @Test fun transportClosureRejectsInputEvenBeforeTheMainThreadProcessesStop() {
        assertFalse(receipt.mayDispatch(1001, 5, false))
    }

    @Test fun newSessionAndClockRollbackCannotReviveAQueuedTouch() {
        assertFalse(receipt.mayDispatch(1001, 6, true))
        assertFalse(receipt.mayDispatch(999, 5, true))
    }
}
