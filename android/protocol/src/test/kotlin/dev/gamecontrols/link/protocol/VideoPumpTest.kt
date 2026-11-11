package dev.gamecontrols.link.protocol

import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class VideoPumpTest {
    private class Device : DecoderDevice {
        val inputs = mutableListOf<Packet.Video>()
        val outputs = ArrayDeque<DecoderOutput>()
        val rendered = mutableListOf<Boolean>()
        var accept = true
        var closes = 0
        override fun queue(frame: Packet.Video): Boolean {
            if (accept) inputs.add(frame)
            return accept
        }
        override fun dequeue() = outputs.pollFirst()
        override fun release(output: DecoderOutput, render: Boolean) { rendered.add(render) }
        override fun close() { closes++ }
    }
    private val config = Packet.Config(Geometry(100, 200, 100, 200, 0), byteArrayOf(103), byteArrayOf(104))
    private fun video(pts: Long = 1, key: Boolean = true) = Packet.Video(pts, key, byteArrayOf(1, 2, 3))

    @Test fun outputDrainsWithoutAnyNewNetworkInput() {
        val device = Device()
        var presented = 0
        val pump = VideoPump({ device }, { presented++ })
        pump.configure(config)
        pump.offer(video(), 0)
        pump.step(0)
        assertEquals(1, device.inputs.size)
        assertEquals(0, presented)
        device.outputs.add(DecoderOutput(4, 1))
        pump.step(10)
        assertEquals(listOf(true), device.rendered)
        assertEquals(1, presented)
        pump.close()
        pump.close()
        assertEquals(1, device.closes)
    }

    @Test fun configurationAndFirstKeyframeAreMandatory() {
        val pump = VideoPump({ Device() }, {})
        assertThrows(IllegalStateException::class.java) { pump.offer(video(), 0) }
        pump.configure(config)
        assertThrows(ProtocolException::class.java) { pump.offer(video(key = false), 0) }
        assertThrows(IllegalStateException::class.java) { pump.configure(config) }
        pump.close()
        assertThrows(IllegalStateException::class.java) { pump.configure(config) }
    }

    @Test fun submittedFramesCountTowardBothBacklogAndAgeBounds() {
        val device = Device()
        val pump = VideoPump({ device }, {}, maxUnits = 2)
        pump.configure(config)
        pump.offer(video(1), 0)
        pump.step(0)
        pump.offer(video(2), 10)
        pump.step(10)
        assertThrows(ProtocolException::class.java) { pump.offer(video(3), 11) }
        assertThrows(ProtocolException::class.java) { pump.step(500) }
        pump.close()
        assertEquals(1, device.closes)
    }

    @Test fun fullCodecInputDoesNotDropAReferenceFrame() {
        val device = Device().apply { accept = false }
        val pump = VideoPump({ device }, {})
        pump.configure(config)
        pump.offer(video(), 0)
        pump.step(0)
        assertTrue(device.inputs.isEmpty())
        device.accept = true
        pump.step(20)
        assertEquals(1, device.inputs.size)
    }

    @Test fun mismatchedOutputIsDiscardedAndStopsInsteadOfMisrendering() {
        val device = Device()
        val pump = VideoPump({ device }, {})
        pump.configure(config)
        pump.offer(video(), 0)
        pump.step(0)
        device.outputs.add(DecoderOutput(1, 999))
        assertThrows(ProtocolException::class.java) { pump.step(1) }
        assertEquals(listOf(false), device.rendered)
    }

    @Test fun cleanupAttemptsAllResourcesAndReportsAggregatedErrors() {
        val calls = mutableListOf<String>()
        val failure = ReleaseActions.run(
            { calls.add("display"); error("display release failed") },
            { calls.add("codec"); throw IllegalArgumentException("codec release failed") },
            { calls.add("projection") },
        )
        assertEquals(listOf("display", "codec", "projection"), calls)
        assertNotNull(failure)
        assertEquals(1, failure!!.suppressed.size)
        assertNull(ReleaseActions.run({ calls.add("notification") }))
    }
}
