package com.mtpali.notification

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

object RelayClient {
    private val executor = Executors.newFixedThreadPool(2)

    fun publish(
        context: Context,
        payload: MirrorPayload,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        publishEncrypted(
            context = context,
            topic = CryptoBox.topic(Prefs.pairCode(context)),
            kind = KIND_MIRROR,
            plaintext = payload.toJson(),
            onComplete = onComplete
        )
    }

    fun publishCommand(
        context: Context,
        payload: CommandPayload,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        publishEncrypted(
            context = context,
            topic = CryptoBox.commandTopic(Prefs.pairCode(context)),
            kind = KIND_COMMAND,
            plaintext = payload.toJson(),
            onComplete = onComplete
        )
    }

    private fun publishEncrypted(
        context: Context,
        topic: String,
        kind: String,
        plaintext: String,
        onComplete: ((Boolean) -> Unit)?
    ) {
        val appContext = context.applicationContext
        val pairCode = Prefs.pairCode(appContext)
        val relayUrl = Prefs.relayUrl(appContext).trimEnd('/')
        val relayToken = Prefs.relayToken(appContext)

        if (!CryptoBox.isValidPairCode(pairCode) ||
            !relayUrl.startsWith("https://") ||
            relayToken.length < 24
        ) {
            onComplete?.invoke(false)
            return
        }

        executor.execute {
            var connection: HttpURLConnection? = null
            val ok = try {
                val encrypted = CryptoBox.encrypt(pairCode, plaintext)
                if (encrypted.length > MAX_ENCRYPTED_PAYLOAD) {
                    false
                } else {
                    val body = JSONObject().apply {
                        put("topic", topic)
                        put("kind", kind)
                        put("payload", encrypted)
                        put("id", UUID.randomUUID().toString())
                    }.toString()

                    connection = URL("$relayUrl/v1/send").openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.doOutput = true
                    connection.setRequestProperty("Authorization", "Bearer $relayToken")
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("User-Agent", "Notification-Android/0.8")
                    connection.outputStream.use {
                        it.write(body.toByteArray(StandardCharsets.UTF_8))
                    }
                    connection.responseCode in 200..299
                }
            } catch (_: Exception) {
                false
            } finally {
                connection?.disconnect()
            }
            onComplete?.invoke(ok)
        }
    }

    private const val MAX_ENCRYPTED_PAYLOAD = 3500
    private const val KIND_MIRROR = "mirror"
    private const val KIND_COMMAND = "command"
}
