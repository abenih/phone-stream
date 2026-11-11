package dev.gamecontrols.link.protocol

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class TlsTransportTest {
    private fun listener() = (hostContext.serverSocketFactory.createServerSocket(
        0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) as SSLServerSocket).apply {
        enabledProtocols = arrayOf("TLSv1.3")
        soTimeout = 3000
    }

    private fun client(server: SSLServerSocket, pin: String = PinnedTrustManager.pinOf(certificate)): SSLSocket {
        val context = SSLContext.getInstance("TLSv1.3")
        context.init(null, arrayOf(PinnedTrustManager(pin)), null)
        return (context.socketFactory.createSocket(server.inetAddress, server.localPort) as SSLSocket)
    }

    private fun invitation(token: String = Invitation.newToken()) =
        Invitation("192.168.1.1", 48765, PinnedTrustManager.pinOf(certificate), token)

    @Test fun runtimeHandshakeAuthenticatesOnceBeforeAnySessionTraffic() {
        val invitation = invitation()
        val gate = InvitationGate(invitation.token, 0)
        val executor = Executors.newSingleThreadExecutor()
        listener().use { server ->
            val result = executor.submit<Long> {
                (server.accept() as SSLSocket).use { PairingHandshake.host(it, gate) { 1 } }
            }
            try {
                client(server).use { socket ->
                    val id = PairingHandshake.client(socket, invitation)
                    assertEquals(result.get(5, TimeUnit.SECONDS), id)
                    assertEquals("TLSv1.3", socket.session.protocol)
                    assertTrue(gate.consumed)
                    assertFalse(gate.consume(invitation.token, 2))
                }
            } finally {
                executor.shutdown()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun wrongPinFailsBeforeSendingTheInvitationSecret() {
        val executor = Executors.newSingleThreadExecutor()
        listener().use { server ->
            val gate = InvitationGate(Invitation.newToken(), 0)
            val result = executor.submit<Boolean> {
                (server.accept() as SSLSocket).use {
                    try {
                        PairingHandshake.host(it, gate) { 1 }
                        false
                    } catch (_: java.io.IOException) {
                        !gate.consumed
                    }
                }
            }
            try {
                client(server, "0".repeat(64)).use {
                    assertThrows(java.io.IOException::class.java) {
                        PairingHandshake.client(it, invitation().copy(pin = "0".repeat(64)))
                    }
                }
                assertTrue(result.get(5, TimeUnit.SECONDS))
            } finally {
                executor.shutdown()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun wrongTokenNeverAdmitsInputOrConsumesCorrectInvitation() {
        val executor = Executors.newSingleThreadExecutor()
        val expected = invitation()
        val gate = InvitationGate(expected.token, 0)
        listener().use { server ->
            val result = executor.submit<Boolean> {
                (server.accept() as SSLSocket).use {
                    try {
                        PairingHandshake.host(it, gate) { 1 }
                        false
                    } catch (_: ProtocolException) {
                        true
                    }
                }
            }
            try {
                client(server).use {
                    assertThrows(java.io.IOException::class.java) { PairingHandshake.client(it, invitation()) }
                }
                assertTrue(result.get(5, TimeUnit.SECONDS))
                assertFalse(gate.consumed)
            } finally {
                executor.shutdown()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun pinnedTrustRejectsMissingIdentityAndClientCertificateTrust() {
        val trust = PinnedTrustManager(PinnedTrustManager.pinOf(certificate))
        trust.checkServerTrusted(arrayOf(certificate), "EC")
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(emptyArray(), "EC") }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(certificate), "") }
        assertThrows(CertificateException::class.java) { trust.checkClientTrusted(arrayOf(certificate), "EC") }
        assertThrows(CertificateException::class.java) {
            PinnedTrustManager("f".repeat(64)).checkServerTrusted(arrayOf(certificate), "EC")
        }
    }

    @Test fun realEncryptedFramedConnectionsCarryVideoControlAndStopExactlyOnce() {
        val invitation = invitation()
        val executor = Executors.newSingleThreadExecutor()
        val hostClosed = CountDownLatch(1)
        val clientClosed = CountDownLatch(1)
        val hostCloseCount = AtomicInteger()
        val received = CountDownLatch(2)
        listener().use { server ->
            val result = executor.submit<FramedConnection> {
                val socket = server.accept() as SSLSocket
                PairingHandshake.host(socket, InvitationGate(invitation.token, 0)) { 1 }
                FramedConnection(socket, { packet ->
                    if (packet is Packet.Input) received.countDown()
                }, { hostCloseCount.incrementAndGet(); hostClosed.countDown() }).apply { start() }
            }
            var host: FramedConnection? = null
            var viewer: FramedConnection? = null
            try {
                val socket = client(server)
                PairingHandshake.client(socket, invitation)
                viewer = FramedConnection(socket, { if (it is Packet.Video) received.countDown() },
                    { clientClosed.countDown() }).apply { start() }
                host = result.get(5, TimeUnit.SECONDS)
                assertTrue(host.send(Packet.Video(1000, true, byteArrayOf(0, 0, 0, 1, 101))))
                assertTrue(viewer.send(Packet.Input(1, 1, 1, InputKind.DOWN, 0.5f, 0.5f)))
                assertTrue(received.await(3, TimeUnit.SECONDS))
                // Keep both readers/writers alive long enough to exercise the runtime ping/pong path.
                Thread.sleep(1200)
                assertFalse(host.isClosed)
                assertFalse(viewer.isClosed)
                viewer.close("Client backgrounded.")
                assertTrue(hostClosed.await(3, TimeUnit.SECONDS))
                assertTrue(clientClosed.await(3, TimeUnit.SECONDS))
                host.close("Repeated stop.")
                assertEquals(1, hostCloseCount.get())
                assertFalse(host.send(Packet.Ping(99)))
            } finally {
                viewer?.close("Test cleanup.")
                host?.close("Test cleanup.")
                executor.shutdown()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    companion object {
        private lateinit var directory: Path
        private lateinit var keyFile: Path
        private lateinit var certificate: X509Certificate
        private lateinit var hostContext: SSLContext

        @JvmStatic @BeforeClass fun generateIsolatedTestIdentity() {
            directory = Files.createTempDirectory("game-link-tls-test-")
            keyFile = directory.resolve("fixture.p12")
            val password = "ephemeral-jvm-fixture"
            val process = ProcessBuilder(System.getProperty("java.home") + "/bin/keytool",
                "-genkeypair", "-alias", "fixture", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=Ephemeral JVM Test", "-validity", "2", "-storetype", "PKCS12",
                "-keystore", keyFile.toString(), "-storepass", password, "-noprompt")
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) { output }
            val store = KeyStore.getInstance("PKCS12")
            Files.newInputStream(keyFile).use { store.load(it, password.toCharArray()) }
            certificate = store.getCertificate("fixture") as X509Certificate
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            keys.init(store, password.toCharArray())
            hostContext = SSLContext.getInstance("TLSv1.3")
            hostContext.init(keys.keyManagers, null, null)
        }

        @JvmStatic @AfterClass fun removeOnlyGeneratedFixture() {
            if (::keyFile.isInitialized) Files.deleteIfExists(keyFile)
            if (::directory.isInitialized) Files.deleteIfExists(directory)
        }
    }
}
