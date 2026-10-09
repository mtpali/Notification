package com.mtpali.notification

import java.util.Base64
import org.json.JSONObject

/** Explicit phone-to-phone clipboard transfer. Encoding is not encryption. Never log it. */
class ConnectionSetup private constructor(val pairCode: String, val relayUrl: String,
    val privateKey: String, val transport: String) {
    override fun toString(): String = "ConnectionSetup([redacted])"

    fun encode(): String {
        val json = JSONObject().put("version", 1).put("pairCode", pairCode)
            .put("relayUrl", relayUrl).put("privateKey", privateKey).put("transport", transport).toString()
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Connection setup is too large to copy" }
        return PREFIX + Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val PREFIX = "NotificationSetup1:"
        private const val MAX_BYTES = 4096
        private const val INVALID = "This is not valid Notification setup. Use Copy setup on the first phone."

        fun create(pairCode: String, relayUrl: String, privateKey: String, transport: String): ConnectionSetup {
            val pair = pairCode.trim()
            require(CryptoBox.isValidPairCode(pair)) { "Generate a pair code on the first phone" }
            require(transport == Prefs.RECEIVER_PUSH || transport == Prefs.RECEIVER_STABLE) { INVALID }
            val url = runCatching { RelayProtocol.baseUrl(relayUrl) }.getOrNull()
            require(url != null) { "Use a valid HTTPS Worker URL" }
            val key = if (transport == Prefs.RECEIVER_STABLE) "" else privateKey.trim()
            if (transport == Prefs.RECEIVER_PUSH) {
                val error = RelayProtocol.privateKeyError(key)
                require(error.isEmpty()) { error }
            }
            return ConnectionSetup(pair, url, key, transport)
        }

        fun decode(value: String): ConnectionSetup {
            require(value.length <= MAX_BYTES * 2) { INVALID }
            val text = value.trim()
            require(text.startsWith(PREFIX)) { INVALID }
            val json = runCatching {
                val bytes = Base64.getDecoder().decode(text.removePrefix(PREFIX))
                require(bytes.size <= MAX_BYTES)
                JSONObject(bytes.toString(Charsets.UTF_8)).also {
                    require(it.getInt("version") == 1)
                    require(listOf("pairCode", "relayUrl", "privateKey", "transport").all { field -> it.get(field) is String })
                }
            }.getOrNull()
            require(json != null) { INVALID }
            // Role, selected apps, permissions and start/stop state belong to this phone.
            return create(json.getString("pairCode"), json.getString("relayUrl"),
                json.getString("privateKey"), json.getString("transport"))
        }
    }
}
