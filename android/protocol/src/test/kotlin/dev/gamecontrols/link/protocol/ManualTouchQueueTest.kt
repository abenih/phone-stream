package dev.gamecontrols.link.protocol

import org.junit.Assert.*
import org.junit.Test

class ManualTouchQueueTest {
    private val queue = ManualTouchQueue()
    private val point = Point(0.3f, 0.4f)
    private fun grant() = queue.grant(Packet.Grant(1, 1, "com.example"))

    @Test fun noInputBeforeExplicitGrantOrAfterReset() {
        assertNull(queue.next(0))
        assertThrows(IllegalStateException::class.java) { queue.touch(InputKind.DOWN, point, 0) }
        grant()
        queue.touch(InputKind.DOWN, point, 0)
        queue.reset()
        assertNull(queue.next(1))
    }

    @Test fun stopAndWaitPreservesUpWhileCoalescingOnlyMoves() {
        grant()
        queue.touch(InputKind.DOWN, point, 0)
        val down = requireNotNull(queue.next(0))
        queue.touch(InputKind.MOVE, Point(0.4f, 0.5f), 5)
        queue.touch(InputKind.MOVE, Point(0.8f, 0.9f), 6)
        queue.touch(InputKind.UP, Point(0.8f, 0.9f), 7)
        assertNull(queue.next(8))
        queue.acknowledge(Packet.Ack(down.sequence, true))
        val move = requireNotNull(queue.next(9))
        assertEquals(InputKind.MOVE, move.kind)
        assertEquals(0.8f, move.x)
        queue.acknowledge(Packet.Ack(move.sequence, true))
        assertEquals(InputKind.UP, queue.next(10)?.kind)
    }

    @Test fun stationaryContinuationExistsOnlyWhileFingerIsPhysicallyHeld() {
        grant()
        queue.touch(InputKind.DOWN, point, 0)
        queue.acknowledge(Packet.Ack(requireNotNull(queue.next(0)).sequence, true))
        assertNull(queue.next(49))
        val move = requireNotNull(queue.next(50))
        assertEquals(InputKind.MOVE, move.kind)
        queue.acknowledge(Packet.Ack(move.sequence, true))
        queue.touch(InputKind.UP, point, 51)
        queue.acknowledge(Packet.Ack(requireNotNull(queue.next(51)).sequence, true))
        assertNull(queue.next(1000))
    }

    @Test fun cancelDropsUnsentTouchesAndReleasesAfterOutstandingAck() {
        grant()
        queue.touch(InputKind.DOWN, point, 0)
        val down = requireNotNull(queue.next(0))
        queue.touch(InputKind.MOVE, point, 10)
        queue.cancel(15)
        assertNull(queue.next(16))
        queue.acknowledge(Packet.Ack(down.sequence, true))
        assertEquals(InputKind.RELEASE, queue.next(17)?.kind)
    }

    @Test fun staleQueuedInputAndMissingAckAreHardFailures() {
        grant()
        queue.touch(InputKind.DOWN, point, 0)
        assertThrows(ProtocolException::class.java) { queue.next(250) }
        grant()
        queue.touch(InputKind.DOWN, point, 0)
        queue.next(0)
        assertThrows(ProtocolException::class.java) { queue.next(300) }
    }

    @Test fun negativeOrUnexpectedAckCannotUnlockAnotherTouch() {
        grant()
        queue.touch(InputKind.DOWN, point, 0)
        val down = requireNotNull(queue.next(0))
        assertThrows(ProtocolException::class.java) { queue.acknowledge(Packet.Ack(down.sequence + 1, true)) }
        assertThrows(ProtocolException::class.java) { queue.acknowledge(Packet.Ack(down.sequence, false)) }
    }
}
