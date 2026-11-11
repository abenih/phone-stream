package dev.gamecontrols.link

import dev.gamecontrols.link.net.HostIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class HostIdentityPolicyTest {
    @Test fun tlsKeyAuthorizesBothCertificateAndConscryptPrehashedSignatures() {
        assertEquals(setOf("NONE", "SHA-256"), HostIdentity.TLS_DIGESTS.toSet())
    }
}
