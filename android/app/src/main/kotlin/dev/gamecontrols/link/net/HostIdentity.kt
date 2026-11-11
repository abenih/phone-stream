package dev.gamecontrols.link.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import dev.gamecontrols.link.protocol.PinnedTrustManager
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

class HostIdentity {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val certificate: X509Certificate
    val pin: String
    val context: SSLContext

    init {
        if (!store.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(*TLS_DIGESTS)
                    .setCertificateSubject(X500Principal("CN=Game Controls Link Host"))
                    .setCertificateSerialNumber(BigInteger(128, SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(Date(now - 86_400_000))
                    .setCertificateNotAfter(Date(now + 10L * 365 * 86_400_000))
                    .setUserAuthenticationRequired(false)
                    .build())
                generateKeyPair()
            }
        }
        certificate = store.getCertificate(ALIAS) as? X509Certificate ?: error("Host certificate is unavailable.")
        certificate.checkValidity()
        pin = PinnedTrustManager.pinOf(certificate)
        val privateKey = store.getKey(ALIAS, null) as? PrivateKey ?: error("Host signing key is unavailable.")
        val keyInfo = KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore")
            .getKeySpec(privateKey, KeyInfo::class.java)
        check(keyInfo.digests.toSet().containsAll(TLS_DIGESTS.toList())) {
            "The stored Host identity cannot sign TLS handshakes. No certificate was silently replaced."
        }
        val manager = object : X509ExtendedKeyManager() {
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) =
                if (keyType == "EC") arrayOf(ALIAS) else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) =
                if (keyType == "EC") ALIAS else null
            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) =
                if (keyType == "EC") ALIAS else null
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getCertificateChain(alias: String?) = if (alias == ALIAS) arrayOf(certificate) else null
            override fun getPrivateKey(alias: String?) = if (alias == ALIAS) privateKey else null
        }
        context = SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(manager), null, SecureRandom()) }
    }

    companion object {
        private const val ALIAS = "game-controls-link-host-tls-v1"
        // Conscrypt passes a precomputed digest to AndroidKeyStore through NONEwithECDSA.
        internal val TLS_DIGESTS = arrayOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
        fun clientContext(pin: String): SSLContext = SSLContext.getInstance("TLSv1.3").apply {
            init(null, arrayOf(PinnedTrustManager(pin)), SecureRandom())
        }
    }
}
