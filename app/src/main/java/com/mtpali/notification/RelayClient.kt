package com.mtpali.notification

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

object RelayClient {
    private val executor = Executors.newFixedThreadPool(2)

    fun publish(context: Context, payload: MirrorPayload) {
        publishEncrypted(context, CryptoBox.topic(Prefs.pairCode(context)), payload.toJson())
    }

    fun publishCommand(context: Context, payload: CommandPayload) {
        publishEncrypted(context, CryptoBox.commandTopic(Prefs.pairCode(context)), payload.toJson())
    }

    private fun publishEncrypted(context: Context, topic: String, plaintext: String) {
        val pairCode = Prefs.pairCode(context)
        if (!CryptoBox.isValidPairCode(pairCode)) return

        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val encrypted = CryptoBox.encrypt(pairCode, plaintext)
                connection = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                connection.setRequestProperty("Firebase", "no")
                connection.setRequestProperty("User-Agent", "Notification-Android/0.6")
                connection.outputStream.use {
                    it.write(encrypted.toByteArray(StandardCharsets.UTF_8))
                }
                connection.responseCode
            } catch (_: Exception) {
            } finally {
                connection?.disconnect()
            }
        }
    }
}
