package dev.gamecontrols.link.protocol

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class PinnedTrustManager(pin: String) : X509TrustManager {
    private val expected = pin.also { require(Invitation.validPin(it)) }
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain.isNullOrEmpty() || authType.isNullOrBlank()) throw CertificateException("Missing host identity.")
        val leaf = chain[0]
        leaf.checkValidity()
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
        if (!MessageDigest.isEqual(expected, actual)) throw CertificateException("Host TLS pin mismatch.")
        if (leaf.publicKey.algorithm != "EC" || leaf.subjectX500Principal != leaf.issuerX500Principal) {
            throw CertificateException("Expected a self-signed EC host identity.")
        }
        // The complete out-of-band SPKI pin replaces CA/hostname trust for a direct LAN identity.
        try {
            leaf.verify(leaf.publicKey)
        } catch (error: GeneralSecurityException) {
            throw CertificateException("Invalid host certificate signature.", error)
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Clients authenticate with a one-use invitation inside pinned TLS.")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun pinOf(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
