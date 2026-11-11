package dev.gamecontrols.link.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class ProtocolException(message: String) : IOException(message)

enum class InputKind { DOWN, MOVE, UP, RELEASE }

sealed interface Packet {
    data class Hello(val token: String) : Packet {
        override fun toString() = "Hello(token=<redacted>)"
    }
    data class Ready(val sessionId: Long) : Packet
    data class Config(val geometry: Geometry, val csd0: ByteArray, val csd1: ByteArray) : Packet
    data class Video(val ptsUs: Long, val keyFrame: Boolean, val bytes: ByteArray) : Packet
    data class Grant(val id: Long, val epoch: Int, val target: String) : Packet
    data class Revoke(val reason: String) : Packet
    data class Input(
        val grantId: Long, val epoch: Int, val sequence: Long, val kind: InputKind,
        val x: Float = 0f, val y: Float = 0f,
    ) : Packet
    data class Ack(val sequence: Long, val accepted: Boolean) : Packet
    data class Ping(val id: Long) : Packet
    data class Pong(val id: Long) : Packet
    data class Stop(val reason: String) : Packet
}

object Wire {
    const val MAX_VIDEO_BYTES = 1024 * 1024
    const val MAX_CSD_BYTES = 64 * 1024
    private const val VERSION = 1

    fun write(output: OutputStream, packet: Packet) {
        output.write(encode(packet))
    }

    fun encode(packet: Packet): ByteArray {
        validate(packet)
        val payload = ByteArrayOutputStream()
        val writer = DataOutputStream(payload)
        val type = when (packet) {
            is Packet.Hello -> { writer.text(packet.token); 1 }
            is Packet.Ready -> { writer.writeLong(packet.sessionId); 2 }
            is Packet.Config -> {
                with(packet.geometry) {
                    listOf(displayWidth, displayHeight, encodedWidth, encodedHeight, rotation, epoch)
                        .forEach(writer::writeInt)
                }
                writer.blob(packet.csd0); writer.blob(packet.csd1); 3
            }
            is Packet.Video -> {
                writer.writeLong(packet.ptsUs); writer.writeBoolean(packet.keyFrame); writer.blob(packet.bytes); 4
            }
            is Packet.Grant -> {
                writer.writeLong(packet.id); writer.writeInt(packet.epoch); writer.text(packet.target); 5
            }
            is Packet.Revoke -> { writer.text(packet.reason); 6 }
            is Packet.Input -> {
                writer.writeLong(packet.grantId); writer.writeInt(packet.epoch); writer.writeLong(packet.sequence)
                writer.writeByte(packet.kind.ordinal); writer.writeFloat(packet.x); writer.writeFloat(packet.y); 7
            }
            is Packet.Ack -> { writer.writeLong(packet.sequence); writer.writeBoolean(packet.accepted); 8 }
            is Packet.Ping -> { writer.writeLong(packet.id); 9 }
            is Packet.Pong -> { writer.writeLong(packet.id); 10 }
            is Packet.Stop -> { writer.text(packet.reason); 11 }
        }
        return ByteArrayOutputStream().apply {
            val header = DataOutputStream(this)
            header.writeByte('G'.code); header.writeByte('C'.code)
            header.writeByte(VERSION); header.writeByte(type); header.writeInt(payload.size())
            payload.writeTo(this)
        }.toByteArray()
    }

    fun read(input: InputStream): Packet {
        val header = DataInputStream(input)
        wire(header.readUnsignedByte() == 'G'.code && header.readUnsignedByte() == 'C'.code, "Bad magic.")
        wire(header.readUnsignedByte() == VERSION, "Unsupported wire version.")
        val type = header.readUnsignedByte()
        val maximum = when (type) {
            1 -> 45
            2, 9, 10 -> 8
            3 -> 32 + 2 * MAX_CSD_BYTES
            4 -> 13 + MAX_VIDEO_BYTES
            5 -> 270
            6, 11 -> 258
            7 -> 29
            8 -> 9
            else -> throw ProtocolException("Unknown packet type.")
        }
        val size = header.readInt()
        wire(size in 1..maximum, "Invalid packet length.")
        val payload = ByteArray(size)
        header.readFully(payload)
        val reader = DataInputStream(ByteArrayInputStream(payload))
        val packet = try {
            when (type) {
                1 -> Packet.Hello(reader.text(43))
                2 -> Packet.Ready(reader.readLong())
                3 -> Packet.Config(Geometry(reader.readInt(), reader.readInt(), reader.readInt(),
                    reader.readInt(), reader.readInt(), reader.readInt()),
                    reader.blob(MAX_CSD_BYTES), reader.blob(MAX_CSD_BYTES))
                4 -> Packet.Video(reader.readLong(), reader.strictBoolean(), reader.blob(MAX_VIDEO_BYTES))
                5 -> Packet.Grant(reader.readLong(), reader.readInt(), reader.text(256))
                6 -> Packet.Revoke(reader.text(256))
                7 -> {
                    val id = reader.readLong()
                    val epoch = reader.readInt()
                    val sequence = reader.readLong()
                    val kind = reader.readUnsignedByte()
                    wire(kind in InputKind.entries.indices, "Unknown input kind.")
                    Packet.Input(id, epoch, sequence, InputKind.entries[kind], reader.readFloat(), reader.readFloat())
                }
                8 -> Packet.Ack(reader.readLong(), reader.strictBoolean())
                9 -> Packet.Ping(reader.readLong())
                10 -> Packet.Pong(reader.readLong())
                11 -> Packet.Stop(reader.text(256))
                else -> throw ProtocolException("Unknown packet type.")
            }
        } catch (error: IllegalArgumentException) {
            throw ProtocolException("Invalid packet: ${error.message}")
        }
        wire(reader.available() == 0, "Trailing packet data.")
        validate(packet)
        return packet
    }

    fun validate(packet: Packet) {
        when (packet) {
            is Packet.Hello -> wire(Invitation.validToken(packet.token), "Invalid token.")
            is Packet.Ready -> wire(packet.sessionId > 0, "Invalid session.")
            is Packet.Config -> wire(packet.csd0.size in 1..MAX_CSD_BYTES &&
                packet.csd1.size <= MAX_CSD_BYTES, "Invalid codec configuration.")
            is Packet.Video -> wire(packet.ptsUs >= 0 && packet.bytes.size in 1..MAX_VIDEO_BYTES, "Invalid video.")
            is Packet.Grant -> {
                wire(packet.id > 0 && packet.epoch > 0, "Invalid grant.")
                textValid(packet.target)
                wire(packet.target.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")), "Invalid target.")
            }
            is Packet.Revoke -> textValid(packet.reason)
            is Packet.Input -> wire(packet.grantId > 0 && packet.epoch > 0 && packet.sequence > 0 &&
                packet.x.isFinite() && packet.y.isFinite() && packet.x in 0f..1f && packet.y in 0f..1f, "Invalid input.")
            is Packet.Ack -> wire(packet.sequence > 0, "Invalid acknowledgement.")
            is Packet.Ping -> wire(packet.id > 0, "Invalid heartbeat.")
            is Packet.Pong -> wire(packet.id > 0, "Invalid heartbeat.")
            is Packet.Stop -> textValid(packet.reason)
        }
    }

    private fun textValid(value: String) {
        wire(value.toByteArray(Charsets.UTF_8).size in 1..256 && value.none { it.isISOControl() }, "Invalid text.")
    }
    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeShort(bytes.size); write(bytes)
    }
    private fun DataInputStream.text(maximum: Int): String {
        val size = readUnsignedShort()
        wire(size in 1..maximum && size <= available(), "Invalid text length.")
        val bytes = ByteArray(size)
        readFully(bytes)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }
    private fun DataOutputStream.blob(bytes: ByteArray) {
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.blob(maximum: Int): ByteArray {
        val size = readInt()
        wire(size in 0..maximum && size <= available(), "Invalid byte-array length.")
        return ByteArray(size).also(::readFully)
    }
    private fun DataInputStream.strictBoolean(): Boolean {
        val value = readUnsignedByte()
        wire(value in 0..1, "Invalid boolean.")
        return value == 1
    }
    private fun wire(accepted: Boolean, message: String) {
        if (!accepted) throw ProtocolException(message)
    }
}
