package dev.gamecontrols.link.protocol

import java.net.Inet4Address
import java.net.InetAddress

object LanAddress {
    fun parseIpv4(text: String): Inet4Address {
        val parts = text.split('.')
        require(parts.size == 4) { "Use a private IPv4 address, not a hostname." }
        val octets = parts.map {
            require(it.matches(Regex("0|[1-9][0-9]{0,2}"))) { "Noncanonical IPv4 address." }
            requireNotNull(it.toIntOrNull()).also { value -> require(value in 0..255) }
        }
        require(octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)) { "Only private RFC1918 LAN addresses are supported." }
        return InetAddress.getByAddress(octets.map(Int::toByte).toByteArray()) as Inet4Address
    }

    fun sameSubnet(peer: InetAddress, local: InetAddress, prefix: Int): Boolean {
        if (peer !is Inet4Address || local !is Inet4Address || prefix !in 1..30) return false
        if (!privateAddress(peer) || !privateAddress(local)) return false
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val peerBits = bits(peer)
        val localBits = bits(local)
        val hostMask = mask xor 0xffffffffL
        return peerBits and mask == localBits and mask &&
            peerBits and hostMask != 0L && peerBits and hostMask != hostMask &&
            localBits and hostMask != 0L && localBits and hostMask != hostMask
    }

    fun privateAddress(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address.map { it.toInt() and 255 }
        return bytes[0] == 10 || (bytes[0] == 172 && bytes[1] in 16..31) ||
            (bytes[0] == 192 && bytes[1] == 168)
    }

    private fun bits(address: Inet4Address) =
        address.address.fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) }
}
