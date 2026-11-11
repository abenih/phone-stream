package dev.gamecontrols.link.protocol

import org.junit.Assert.*
import org.junit.Test

class QueueLifecycleTest {
    private fun video(size: Int = 2) = Packet.Video(0, true, ByteArray(size) { 1 })

    @Test fun configurationAndControlRemainOrderedAheadOfVideo() {
        val queue = OutboundQueue()
        val config = Packet.Config(Geometry(100, 200, 100, 200, 0), byteArrayOf(103), byteArrayOf(104))
        val grant = Packet.Grant(1, 1, "com.example")
        assertTrue(queue.offer(config, 0))
        assertTrue(queue.offer(video(), 0))
        assertTrue(queue.offer(grant, 0))
        assertSame(config, queue.take(1))
        assertSame(grant, queue.take(1))
        assertTrue(queue.take(1) is Packet.Video)
    }

    @Test fun videoOverflowDoesNotSilentlyDropDependentFrames() {
        val queue = OutboundQueue(maxVideos = 2, maxBytes = 4)
        assertTrue(queue.offer(video(), 0))
        assertTrue(queue.offer(video(), 0))
        assertFalse(queue.offer(video(), 0))
        assertNotNull(queue.take(1))
        assertTrue(queue.offer(video(), 1))
    }

    @Test fun byteAndControlCapsFailExplicitly() {
        val queue = OutboundQueue(maxBytes = 3, maxControls = 2)
        assertFalse(queue.offer(video(4), 0))
        assertTrue(queue.offer(Packet.Ping(1), 0))
        assertTrue(queue.offer(Packet.Pong(1), 0))
        assertFalse(queue.offer(Packet.Ping(2), 0))
    }

    @Test fun staleBacklogFailsEvenWhenControlPacketsAreWaiting() {
        val queue = OutboundQueue()
        queue.offer(video(), 10)
        queue.offer(Packet.Ping(1), 10)
        assertThrows(ProtocolException::class.java) { queue.take(510) }
        queue.clear()
        assertNull(queue.take(511))
        assertTrue(queue.offer(video(), 512))
    }

    @Test fun inputOrderIsNeverCoalescedAcrossDownOrUp() {
        val queue = OutboundQueue()
        val events = InputKind.entries.mapIndexed { i, kind -> Packet.Input(1, 1, i + 1L, kind) }
        events.forEach { assertTrue(queue.offer(it, 0)) }
        events.forEach { assertEquals(it, queue.take(1)) }
    }

    @Test fun stoppedSessionRejectsAllLateCallbacksAndRequiresNewExplicitBegin() {
        val fence = SessionFence()
        val first = fence.begin()
        assertTrue(fence.accepts(first))
        assertTrue(fence.stop())
        assertFalse(fence.stop())
        assertFalse(fence.accepts(first))
        val next = fence.begin()
        assertFalse(fence.accepts(first))
        assertTrue(fence.accepts(next))
    }
}
