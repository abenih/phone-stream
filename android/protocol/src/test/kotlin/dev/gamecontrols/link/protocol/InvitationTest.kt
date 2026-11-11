package dev.gamecontrols.link.protocol

import org.junit.Assert.*
import org.junit.Test

class InvitationTest {
    private val token = Invitation.newToken()
    private val invitation = Invitation("192.168.43.1", 48765, "a".repeat(64), token)

    @Test fun canonicalRoundTripAndSecretsAreRedacted() {
        assertEquals(invitation, Invitation.parse(invitation.encode()))
        assertFalse(invitation.toString().contains(token))
        assertFalse(Packet.Hello(token).toString().contains(token))
    }

    @Test fun rejectsMissingUnknownAndDuplicateFields() {
        listOf(invitation.encode() + "&host=192.168.43.2", invitation.encode() + "&extra=1",
            invitation.encode().substringBefore("&token="), invitation.encode().replace("v=1", "v=2"))
            .forEach { assertThrows(IllegalArgumentException::class.java) { Invitation.parse(it) } }
    }

    @Test fun rejectsUriAndEncodingConfusion() {
        listOf(invitation.encode() + "#anything", invitation.encode().replace("pair?", "pair/?"),
            invitation.encode().replace("pair?", "user@pair?"), invitation.encode().replace("v=1", "v=%31"),
            invitation.encode().replace("gamecontrols:", "https:"), invitation.encode() + " ",
            "x".repeat(1025)).forEach {
            assertThrows(IllegalArgumentException::class.java) { Invitation.parse(it) }
        }
    }

    @Test fun rejectsInvalidEndpointsPinsAndTokens() {
        listOf("localhost", "8.8.8.8", "010.0.0.1").forEach {
            assertThrows(IllegalArgumentException::class.java) { invitation.copy(host = it) }
        }
        listOf(0, 443, 65536).forEach {
            assertThrows(IllegalArgumentException::class.java) { invitation.copy(port = it) }
        }
        assertThrows(IllegalArgumentException::class.java) { invitation.copy(pin = "A".repeat(64)) }
        assertThrows(IllegalArgumentException::class.java) { invitation.copy(token = token + "=") }
        assertThrows(IllegalArgumentException::class.java) {
            Invitation.parse(invitation.encode().replace("port=48765", "port=048765"))
        }
    }

    @Test fun tokenRequiresCanonicalUnusedBits() {
        val zero = "A".repeat(43)
        assertTrue(Invitation.validToken(zero))
        assertFalse(Invitation.validToken(zero.dropLast(1) + "B"))
    }

    @Test fun invitationIsConsumedOnceOnlyAfterCorrectAuthentication() {
        val gate = InvitationGate(token, 100)
        assertFalse(gate.consume(Invitation.newToken(), 101))
        assertFalse(gate.consume("invalid", 102))
        assertTrue(gate.consume(token, 103))
        assertTrue(gate.consumed)
        assertFalse(gate.consume(token, 104))
    }

    @Test fun invitationExpiryIsExclusiveAndIrreversible() {
        val gate = InvitationGate(token, 100)
        assertFalse(gate.consume(token, 120100))
        assertFalse(gate.consume(token, 101))
        assertTrue(InvitationGate(token, 100).consume(token, 120099))
    }

    @Test fun revokeAndClockRollbackFailClosed() {
        val gate = InvitationGate(token, 100)
        gate.revoke()
        assertFalse(gate.consume(token, 101))
        val backwards = InvitationGate(token, 100)
        assertFalse(backwards.consume(token, 99))
        assertFalse(backwards.consume(token, 101))
    }
}
