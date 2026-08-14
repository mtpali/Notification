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
        if (pairCode.isBlank()) return

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
                // This app has its own streaming receiver, so ntfy does not need to fan out
                // the already-encrypted relay message through its Firebase integration.
                connection.setRequestProperty("Firebase", "no")

                connection.outputStream.use { output ->
                    output.write(encrypted.toByteArray(StandardCharsets.UTF_8))
                }

                // Force the request to complete. 2xx is success; failures are intentionally
                // ignored for v0.1 and can be surfaced in the UI in a later version.
                connection.responseCode
            } catch (_: Exception) {
                // Notification callbacks must remain lightweight and never crash the listener.
            } finally {
                connection?.disconnect()
            }
        }
    }
}
