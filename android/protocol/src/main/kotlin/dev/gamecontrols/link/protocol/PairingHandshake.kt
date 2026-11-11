package dev.gamecontrols.link.protocol

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

object PairingHandshake {
    fun host(socket: SSLSocket, invitation: InvitationGate, nowMs: () -> Long): Long {
        socket.useClientMode = false
        socket.needClientAuth = false
        prepare(socket)
        val hello = Wire.read(socket.inputStream)
        if (hello !is Packet.Hello || !invitation.consume(hello.token, nowMs())) {
            throw ProtocolException("Invitation authentication failed.")
        }
        val sessionId = newId()
        Wire.write(socket.outputStream, Packet.Ready(sessionId))
        socket.outputStream.flush()
        return sessionId
    }

    fun client(socket: SSLSocket, invitation: Invitation): Long {
        socket.useClientMode = true
        prepare(socket)
        val certificates = socket.session.peerCertificates.map {
            it as? X509Certificate ?: throw ProtocolException("Non-X509 host identity.")
        }.toTypedArray()
        PinnedTrustManager(invitation.pin).checkServerTrusted(certificates, "EC")
        Wire.write(socket.outputStream, Packet.Hello(invitation.token))
        socket.outputStream.flush()
        val ready = Wire.read(socket.inputStream)
        if (ready !is Packet.Ready) throw ProtocolException("Host did not authenticate the invitation.")
        return ready.sessionId
    }

    fun newId(): Long = (SecureRandom().nextLong() ushr 1).coerceAtLeast(1)

    private fun prepare(socket: SSLSocket) {
        socket.enabledProtocols = arrayOf("TLSv1.3")
        socket.soTimeout = 2000
        socket.tcpNoDelay = true
        socket.startHandshake()
        check(socket.session.protocol == "TLSv1.3") { "TLS 1.3 is required." }
    }
}
