package dev.gamecontrols.link.protocol

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class Invitation(val host: String, val port: Int, val pin: String, val token: String) {
    init {
        LanAddress.parseIpv4(host)
        require(port in 1024..65535) { "Invalid LAN port." }
        require(validPin(pin)) { "A complete lowercase SHA-256 pin is required." }
        require(validToken(token)) { "A canonical 256-bit invitation token is required." }
    }

    fun encode() = "gamecontrols://pair?v=1&host=$host&port=$port&pin=$pin&token=$token"
    override fun toString() = "Invitation(host=$host, port=$port, pin=$pin, token=<redacted>)"

    companion object {
        fun parse(text: String): Invitation {
            require(text.length <= 1024 && text.none { it.isWhitespace() } && '%' !in text) {
                "Invalid invitation encoding."
            }
            val uri = URI.create(text)
            require(uri.scheme == "gamecontrols" && uri.rawAuthority == "pair" &&
                uri.rawPath.orEmpty().isEmpty() && uri.rawFragment == null) { "Invalid invitation URI." }
            val fields = requireNotNull(uri.rawQuery) { "Missing invitation fields." }.split('&')
                .map {
                    val entry = it.split('=', limit = 2)
                    require(entry.size == 2) { "Invalid invitation field." }
                    entry[0] to entry[1]
                }
            require(fields.map { it.first }.toSet().size == fields.size) { "Duplicate invitation field." }
            val values = fields.toMap()
            require(values.keys == setOf("v", "host", "port", "pin", "token") && values["v"] == "1") {
                "Unsupported invitation version or fields."
            }
            val portText = values.getValue("port")
            val port = requireNotNull(portText.toIntOrNull()) { "Invalid port." }
            require(port.toString() == portText) { "Noncanonical port." }
            return Invitation(values.getValue("host"), port, values.getValue("pin"), values.getValue("token"))
        }

        fun validPin(value: String) = value.matches(Regex("[0-9a-f]{64}"))
        fun validToken(value: String): Boolean {
            if (!value.matches(Regex("[A-Za-z0-9_-]{43}"))) return false
            val bytes = Base64.getUrlDecoder().decode(value)
            return bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value
        }

        fun newToken(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    }
}

class InvitationGate(token: String, private val openedAtMs: Long, private val lifetimeMs: Long = 120_000) {
    private val expected = token.also { require(Invitation.validToken(it)) }.toByteArray(Charsets.US_ASCII)
    private var revoked = false
    var consumed = false
        private set

    init {
        require(openedAtMs >= 0 && lifetimeMs in 1..120_000)
    }

    @Synchronized
    fun consume(candidate: String, nowMs: Long): Boolean {
        if (revoked || consumed) return false
        if (nowMs < openedAtMs || nowMs - openedAtMs >= lifetimeMs) {
            revoke()
            return false
        }
        if (!Invitation.validToken(candidate) ||
            !MessageDigest.isEqual(expected, candidate.toByteArray(Charsets.US_ASCII))) return false
        consumed = true
        expected.fill(0)
        return true
    }

    @Synchronized
    fun revoke() {
        revoked = true
        expected.fill(0)
    }
}
