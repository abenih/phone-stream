package dev.gamecontrols.link.protocol

import org.junit.Assert.*
import org.junit.Test

class InputGateTest {
    private val gate = InputGate()
    private fun input(seq: Long, kind: InputKind) = Packet.Input(10, 1, seq, kind, 0.5f, 0.5f)

    @Test fun noCaptureOrPairingActionImplicitlyGrantsInput() {
        assertFalse(gate.isGranted)
        assertFalse(gate.accept(input(1, InputKind.DOWN), 0))
        assertFalse(gate.held)
    }

    @Test fun singlePointerDownMoveUpAndReleaseHaveValidOrdering() {
        gate.grant(10, 1, 0)
        assertTrue(gate.accept(input(1, InputKind.DOWN), 1))
        assertTrue(gate.held)
        assertFalse(gate.accept(input(2, InputKind.DOWN), 2))
        assertTrue(gate.accept(input(2, InputKind.MOVE), 3))
        assertTrue(gate.accept(input(3, InputKind.UP), 4))
        assertFalse(gate.held)
        assertFalse(gate.accept(input(4, InputKind.MOVE), 5))
        assertFalse(gate.accept(input(4, InputKind.UP), 5))
        assertTrue(gate.accept(input(4, InputKind.RELEASE), 6))
    }

    @Test fun grantEpochAndSequenceReplayCannotBeReused() {
        gate.grant(10, 1, 0)
        assertFalse(gate.accept(input(1, InputKind.DOWN).copy(grantId = 9), 1))
        assertFalse(gate.accept(input(1, InputKind.DOWN).copy(epoch = 2), 1))
        assertTrue(gate.accept(input(1, InputKind.DOWN), 1))
        assertFalse(gate.accept(input(1, InputKind.MOVE), 2))
        gate.revoke()
        assertFalse(gate.accept(input(2, InputKind.UP), 3))
        gate.grant(11, 1, 4)
        assertFalse(gate.accept(input(2, InputKind.DOWN), 5))
        assertFalse(gate.held)
    }

    @Test fun idleHoldExpiresAt350msAndCannotBeRevivedByLateMove() {
        gate.grant(10, 1, 0)
        assertTrue(gate.accept(input(1, InputKind.DOWN), 10))
        assertFalse(gate.expired(359))
        assertTrue(gate.expired(360))
        assertFalse(gate.accept(input(2, InputKind.MOVE), 360))
        assertFalse(gate.isGranted)
        assertFalse(gate.held)
    }

    @Test fun absoluteGrantTtlDoesNotRefreshWithInput() {
        gate.grant(10, 1, 10)
        assertTrue(gate.accept(input(1, InputKind.RELEASE), 600009))
        assertFalse(gate.accept(input(2, InputKind.RELEASE), 600010))
        assertFalse(gate.isGranted)
    }

    @Test fun backwardsTimeRevokesAndNonFinitePointsReject() {
        gate.grant(10, 1, 100)
        assertFalse(gate.accept(input(1, InputKind.DOWN).copy(x = Float.NaN), 101))
        assertFalse(gate.accept(input(1, InputKind.DOWN), 99))
        assertFalse(gate.isGranted)
    }

    @Test fun continuedInputCannotExceedTheAbsoluteTenSecondHoldBound() {
        gate.grant(10, 1, 0)
        assertTrue(gate.accept(input(1, InputKind.DOWN), 0))
        for (time in 100L..9900L step 100L) {
            assertTrue(gate.accept(input(time, InputKind.MOVE), time))
        }
        assertTrue(gate.expired(10_000))
        assertFalse(gate.accept(input(10_000, InputKind.MOVE), 10_000))
        assertFalse(gate.isGranted)
    }

    @Test fun nativeTimeoutAndLateCallbacksCannotCompleteAnInputTwice() {
        val results = mutableListOf<Boolean>()
        val completion = InputCompletion(results::add)
        completion.finish(false)
        completion.finish(true)
        assertEquals(listOf(false), results)
    }
}
