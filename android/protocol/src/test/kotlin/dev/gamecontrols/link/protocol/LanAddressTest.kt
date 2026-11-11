package dev.gamecontrols.link.protocol

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class LanAddressTest {
    @Test fun acceptsOnlyCanonicalPrivateLiterals() {
        listOf("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.254",
            "192.168.0.1", "192.168.255.254").forEach {
            assertEquals(it, LanAddress.parseIpv4(it).hostAddress)
        }
    }

    @Test fun rejectsPublicLoopbackLinkLocalCgnatDnsAndAmbiguity() {
        listOf("8.8.8.8", "127.0.0.1", "172.15.1.1", "172.32.1.1", "192.167.1.1",
            "100.64.1.1", "169.254.1.1", "224.0.0.1", "0.0.0.0", "255.255.255.255",
            "192.168.1.256", "192.168.01.1", "192.168.1.-1", "10.1", "localhost",
            "example.invalid", "[::1]", " 10.0.0.1", "0xa.0.0.1").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { LanAddress.parseIpv4(it) }
        }
    }

    @Test fun onLinkPolicyRejectsRoutedNetworkAndBroadcastAddresses() {
        val local = LanAddress.parseIpv4("192.168.43.1")
        assertTrue(LanAddress.sameSubnet(LanAddress.parseIpv4("192.168.43.2"), local, 24))
        listOf("192.168.44.1", "192.168.43.0", "192.168.43.255").forEach {
            assertFalse(LanAddress.sameSubnet(LanAddress.parseIpv4(it), local, 24))
        }
        assertFalse(LanAddress.sameSubnet(local, LanAddress.parseIpv4("192.168.43.0"), 24))
    }

    @Test fun arbitraryValidSubnetPrefixesAndInvalidFamilies() {
        val local = LanAddress.parseIpv4("10.2.3.5")
        assertTrue(LanAddress.sameSubnet(LanAddress.parseIpv4("10.2.3.6"), local, 30))
        assertFalse(LanAddress.sameSubnet(LanAddress.parseIpv4("10.2.3.7"), local, 30))
        assertTrue(LanAddress.sameSubnet(LanAddress.parseIpv4("10.2.200.1"), local, 16))
        listOf(-1, 0, 31, 32, 64).forEach { assertFalse(LanAddress.sameSubnet(local, local, it)) }
        assertFalse(LanAddress.sameSubnet(InetAddress.getByAddress(ByteArray(16)), local, 24))
        assertFalse(LanAddress.sameSubnet(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)), local, 8))
    }
}
