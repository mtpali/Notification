package com.mtpali.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class ReceiverService : Service() {
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var running = false

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopReceiver()
            return START_NOT_STICKY
        }

        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification())

        if (Prefs.mode(this) != Prefs.MODE_RECEIVER || Prefs.pairCode(this).isBlank()) {
            stopReceiver()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            executor.execute { receiveLoop() }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        activeConnection?.disconnect()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun receiveLoop() {
        while (running) {
            val pairCode = Prefs.pairCode(this)
            if (pairCode.isBlank() || Prefs.mode(this) != Prefs.MODE_RECEIVER) break

            var connection: HttpURLConnection? = null
            try {
                val topic = CryptoBox.topic(pairCode)
                val lastId = Prefs.lastMessageId(this)
                val since = if (lastId.isBlank()) "10m" else lastId
                val encodedSince = URLEncoder.encode(since, "UTF-8")
                val url = URL("https://ntfy.sh/$topic/json?since=$encodedSince")

                connection = url.openConnection() as HttpURLConnection
                activeConnection = connection
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                // ntfy sends keepalives on a long-lived stream, so keep this above that interval.
                connection.readTimeout = 90_000
                connection.setRequestProperty("Accept", "application/x-ndjson, application/json")

                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("Relay HTTP ${connection.responseCode}")
                }

                connection.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (!running) return@forEach
                        handleRelayLine(pairCode, line)
                    }
                }
            } catch (_: Exception) {
                // Reconnect below. Network changes and mobile handoffs are expected.
            } finally {
                if (activeConnection === connection) activeConnection = null
                connection?.disconnect()
            }

            if (running) {
                try {
                    Thread.sleep(3_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun handleRelayLine(pairCode: String, line: String) {
        if (line.isBlank()) return

        try {
            val envelope = JSONObject(line)
            if (envelope.optString("event") != "message") return

            val id = envelope.optString("id")
            if (id.isNotBlank() && id == Prefs.lastMessageId(this)) return

            val encrypted = envelope.optString("message")
            if (encrypted.isBlank()) return

            val plaintext = CryptoBox.decrypt(pairCode, encrypted)
            val payload = MirrorPayload.fromJson(plaintext)
            showMirroredNotification(payload, id)

            if (id.isNotBlank()) Prefs.setLastMessageId(this, id)
        } catch (_: Exception) {
            // Ignore malformed messages or traffic encrypted with another Pair Code.
        }
    }

    private fun showMirroredNotification(payload: MirrorPayload, relayId: String) {
        val title = payload.title.ifBlank { payload.appName.ifBlank { "Notification" } }
        val body = payload.text.ifBlank { payload.appName }

        val notification = Notification.Builder(this, CHANNEL_MIRRORED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(payload.appName)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setWhen(payload.postTime.takeIf { it > 0 } ?: System.currentTimeMillis())
            .build()

        val stableKey = payload.notificationKey.ifBlank { relayId }
        val notificationId = (payload.packageName + ":" + stableKey).hashCode()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
    }

    private fun createChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "Notification receiver service",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps the encrypted Internet receiver connected"
                setShowBadge(false)
            }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MIRRORED,
                "Mirrored notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications mirrored from the paired Sender phone"
            }
        )
    }

    private fun serviceNotification(): Notification =
        Notification.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Notification receiver")
            .setContentText("Encrypted Internet receiver is active")
            .setOngoing(true)
            .build()

    private fun stopReceiver() {
        running = false
        activeConnection?.disconnect()
        stopForeground(true)
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.mtpali.notification.STOP_RECEIVER"
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val CHANNEL_SERVICE = "receiver_service"
        private const val CHANNEL_MIRRORED = "mirrored_notifications"
    }
}
