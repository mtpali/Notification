package com.mtpali.notification

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class MirrorPayload(
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val notificationKey: String,
    val canReply: Boolean = false,
    val canMarkRead: Boolean = false
) {
    fun toJson(): String = JSONObject().apply {
        put("v", 2)
        put("package", packageName)
        put("app", appName)
        put("title", title)
        put("text", text)
        put("postTime", postTime)
        put("key", notificationKey)
        put("reply", canReply)
        put("read", canMarkRead)
    }.toString()

    companion object {
        fun fromJson(raw: String): MirrorPayload {
            val json = JSONObject(raw)
            return MirrorPayload(
                packageName = json.optString("package"),
                appName = json.optString("app"),
                title = json.optString("title"),
                text = json.optString("text"),
                postTime = json.optLong("postTime"),
                notificationKey = json.optString("key"),
                canReply = json.optBoolean("reply"),
                canMarkRead = json.optBoolean("read")
            )
        }
    }
}

data class CommandPayload(
    val type: String,
    val packageName: String,
    val notificationKey: String,
    val text: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): String = JSONObject().apply {
        put("v", 1)
        put("type", type)
        put("package", packageName)
        put("key", notificationKey)
        put("text", text)
        put("time", createdAt)
    }.toString()

    companion object {
        const val TYPE_REPLY = "reply"
        const val TYPE_MARK_READ = "read"

        fun fromJson(raw: String): CommandPayload {
            val json = JSONObject(raw)
            return CommandPayload(
                type = json.optString("type"),
                packageName = json.optString("package"),
                notificationKey = json.optString("key"),
                text = json.optString("text"),
                createdAt = json.optLong("time")
            )
        }
    }
}

object CryptoBox {
    private val random = SecureRandom()

    fun generatePairCode(): String =
        (100000 + random.nextInt(900000)).toString()

    fun isValidPairCode(pairCode: String): Boolean =
        pairCode.length == 6 && pairCode.all { it.isDigit() }

    fun topic(pairCode: String): String =
        topicFrom("notification-topic-v1:$pairCode", "notification-")

    fun commandTopic(pairCode: String): String =
        topicFrom("notification-command-topic-v1:$pairCode", "notification-cmd-")

    fun encrypt(pairCode: String, plaintext: String): String {
        val key = SecretKeySpec(sha256("notification-key-v1:$pairCode"), "AES")
        val nonce = ByteArray(12)
        random.nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return encode(nonce + encrypted)
    }

    fun decrypt(pairCode: String, encoded: String): String {
        val combined = decode(encoded)
        require(combined.size > 12) { "Encrypted payload is too short" }

        val nonce = combined.copyOfRange(0, 12)
        val ciphertext = combined.copyOfRange(12, combined.size)
        val key = SecretKeySpec(sha256("notification-key-v1:$pairCode"), "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun topicFrom(value: String, prefix: String): String =
        prefix + encode(sha256(value)).take(32)

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
