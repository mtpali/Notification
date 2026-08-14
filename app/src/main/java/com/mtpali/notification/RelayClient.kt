package com.mtpali.notification

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object RelayClient {
    private val executor = Executors.newFixedThreadPool(2)

    fun publish(context: Context, payload: MirrorPayload) {
        val appContext = context.applicationContext
        val pairCode = Prefs.pairCode(appContext)
        if (pairCode.isBlank()) {
            Prefs.setLastPublish(appContext, "failed: Pair Code is empty")
            return
        }

        Prefs.setLastPublish(appContext, "sending at ${timeNow()}")

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
                connection.setRequestProperty("User-Agent", "Notification-Android/0.3")

                connection.outputStream.use { output ->
                    output.write(encrypted.toByteArray(StandardCharsets.UTF_8))
                }

                val code = connection.responseCode
                if (code in 200..299) {
                    Prefs.setLastPublish(appContext, "SUCCESS HTTP $code at ${timeNow()}")
                } else {
                    Prefs.setLastPublish(appContext, "FAILED HTTP $code at ${timeNow()}")
                }
            } catch (e: Exception) {
                val reason = e.message?.take(100) ?: e.javaClass.simpleName
                Prefs.setLastPublish(appContext, "FAILED ${e.javaClass.simpleName}: $reason")
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun timeNow(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
