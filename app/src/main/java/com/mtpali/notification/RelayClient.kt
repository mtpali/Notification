package com.mtpali.notification

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

object RelayClient {
    private val executor = Executors.newFixedThreadPool(2)

    fun publish(context: Context, payload: MirrorPayload) {
        val pairCode = Prefs.pairCode(context)
        if (!CryptoBox.isValidPairCode(pairCode)) return

        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                val encrypted = CryptoBox.encrypt(pairCode, payload.toJson())
                val topic = CryptoBox.topic(pairCode)
                connection = URL("https://ntfy.sh/$topic").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                connection.setRequestProperty("Firebase", "no")
                connection.setRequestProperty("User-Agent", "Notification-Android/0.5")
                connection.outputStream.use {
                    it.write(encrypted.toByteArray(StandardCharsets.UTF_8))
                }
                connection.responseCode
            } catch (_: Exception) {
                // Keep notification callbacks isolated from network failures.
            } finally {
                connection?.disconnect()
            }
        }
    }
}
