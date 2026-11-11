package dev.gamecontrols.link.protocol

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class WireTest {
    private val samples = listOf(
        Packet.Hello(Invitation.newToken()), Packet.Ready(5),
        Packet.Config(Geometry(3040, 1440, 1280, 606, 90), byteArrayOf(0, 0, 0, 1, 103), byteArrayOf(104)),
        Packet.Video(5000, true, byteArrayOf(0, 0, 1, 101)),
        Packet.Grant(7, 1, "com.example.fixture"), Packet.Revoke("Local user revoked control."),
        Packet.Input(7, 1, 9, InputKind.DOWN, 0.25f, 1f), Packet.Ack(9, true),
        Packet.Ping(12), Packet.Pong(12), Packet.Stop("Stopped"),
    )

    @Test fun everyPacketRoundTripsAcrossOneByteTcpFragments() {
        samples.forEach { sample ->
            val encoded = Wire.encode(sample)
            val fragmented = object : FilterInputStream(ByteArrayInputStream(encoded)) {
                override fun read(bytes: ByteArray, offset: Int, length: Int) =
                    super.read(bytes, offset, minOf(length, 1))
            }
            assertArrayEquals(encoded, Wire.encode(Wire.read(fragmented)))
        }
    }

    @Test fun consecutivePacketsDoNotConsumeEachOthersBytes() {
        val input = ByteArrayInputStream(samples.flatMap { Wire.encode(it).toList() }.toByteArray())
        samples.forEach { assertArrayEquals(Wire.encode(it), Wire.encode(Wire.read(input))) }
        assertEquals(0, input.available())
    }

    @Test fun everyTruncationFailsRatherThanReturningPartialSuccess() {
        samples.forEach { packet ->
            val encoded = Wire.encode(packet)
            for (length in encoded.indices) {
                assertThrows(EOFException::class.java) { Wire.read(ByteArrayInputStream(encoded.copyOf(length))) }
            }
        }
    }

    @Test fun headerRejectsWrongMagicVersionAndType() {
        listOf(0 to 0, 1 to 0, 2 to 9, 3 to 255).forEach { (index, value) ->
            val bytes = Wire.encode(Packet.Ping(1))
            bytes[index] = value.toByte()
            assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(bytes)) }
        }
    }

    @Test fun lengthLimitsAreCheckedBeforePayloadAllocation() {
        listOf(-1, 0, 9, Int.MAX_VALUE).forEach { length ->
            val header = Wire.encode(Packet.Ping(1)).copyOf(8)
            ByteBuffer.wrap(header).putInt(4, length)
            assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(header)) }
        }
        val video = Wire.encode(Packet.Video(0, false, byteArrayOf(1))).copyOf(8)
        ByteBuffer.wrap(video).putInt(4, Wire.MAX_VIDEO_BYTES + 14)
        assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(video)) }
    }

    @Test fun invalidBooleanTrailingBytesAndBlobLengthsFail() {
        val ack = Wire.encode(Packet.Ack(1, true))
        ack[16] = 2
        assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(ack)) }
        val reason = Wire.encode(Packet.Revoke("x")) + byteArrayOf(0)
        ByteBuffer.wrap(reason).putInt(4, reason.size - 8)
        assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(reason)) }
        val video = Wire.encode(Packet.Video(0, true, byteArrayOf(1)))
        ByteBuffer.wrap(video).putInt(17, Int.MAX_VALUE)
        assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(video)) }
    }

    @Test fun invalidUtf8AndUnsafeTextFail() {
        val bytes = Wire.encode(Packet.Stop("ab"))
        bytes[10] = 0xc0.toByte()
        bytes[11] = 0xaf.toByte()
        assertThrows(IOException::class.java) { Wire.read(ByteArrayInputStream(bytes)) }
        listOf("", "x".repeat(257), "reason\ninjection").forEach {
            assertThrows(ProtocolException::class.java) { Wire.encode(Packet.Stop(it)) }
        }
    }

    @Test fun encoderRejectsInvalidPacketsAsStrictlyAsReader() {
        listOf(Packet.Ready(0), Packet.Hello("wrong"), Packet.Grant(0, 1, "com.example"),
            Packet.Grant(1, 1, "invalid package"), Packet.Video(-1, false, byteArrayOf(1)),
            Packet.Video(0, false, ByteArray(0)), Packet.Ack(-1, true),
            Packet.Input(1, 1, 1, InputKind.DOWN, Float.NaN, 0f),
            Packet.Input(1, 1, 1, InputKind.DOWN, 0f, 1.1f)).forEach {
            assertThrows(ProtocolException::class.java) { Wire.encode(it) }
        }
    }

    @Test fun readerRejectsUnknownInputAndNonFiniteCoordinates() {
        val input = Packet.Input(1, 1, 1, InputKind.DOWN)
        val unknown = Wire.encode(input).also { it[28] = 99 }
        assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(unknown)) }
        val nonFinite = Wire.encode(input)
        ByteBuffer.wrap(nonFinite).putFloat(29, Float.POSITIVE_INFINITY)
        assertThrows(ProtocolException::class.java) { Wire.read(ByteArrayInputStream(nonFinite)) }
    }
}
