package com.mtpali.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ReceiverService : Service() {
    private lateinit var client: OkHttpClient
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor()
    private val reconnectScheduled = AtomicBoolean(false)

    @Volatile
    private var running = false

    @Volatile
    private var webSocket: WebSocket? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        client = OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopReceiver()
            return START_NOT_STICKY
        }

        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification("Connecting…"))

        if (Prefs.mode(this) != Prefs.MODE_RECEIVER ||
            !CryptoBox.isValidPairCode(Prefs.pairCode(this))
        ) {
            stopReceiver()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            Prefs.setReceiverStatus(this, "connecting at ${timeNow()}")
            connectWebSocket()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        webSocket?.cancel()
        webSocket = null
        reconnectExecutor.shutdownNow()
        if (::client.isInitialized) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        super.onDestroy()
    }

    private fun connectWebSocket() {
        if (!running || webSocket != null) return

        val pairCode = Prefs.pairCode(this)
        if (!CryptoBox.isValidPairCode(pairCode) || Prefs.mode(this) != Prefs.MODE_RECEIVER) {
            stopReceiver()
            return
        }

        val topic = CryptoBox.topic(pairCode)
        val lastId = Prefs.lastMessageId(this)
        val since = if (lastId.isBlank()) "10m" else lastId
        val encodedSince = URLEncoder.encode(since, "UTF-8")
        val request = Request.Builder()
            .url("wss://ntfy.sh/$topic/ws?since=$encodedSince")
            .header("User-Agent", "Notification-Android/0.3")
            .build()

        Prefs.setReceiverStatus(this, "connecting WebSocket at ${timeNow()}")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Prefs.setReceiverStatus(this@ReceiverService, "CONNECTED WebSocket at ${timeNow()}")
                updateServiceNotification("Connected • WebSocket")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRelayLine(pairCode, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (this@ReceiverService.webSocket === webSocket) {
                    this@ReceiverService.webSocket = null
                }
                if (running) {
                    Prefs.setReceiverStatus(
                        this@ReceiverService,
                        "disconnected ($code), reconnecting at ${timeNow()}"
                    )
                    updateServiceNotification("Disconnected • reconnecting")
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@ReceiverService.webSocket === webSocket) {
                    this@ReceiverService.webSocket = null
                }
                if (running) {
                    val reason = t.message?.take(80) ?: t.javaClass.simpleName
                    Prefs.setReceiverStatus(
                        this@ReceiverService,
                        "FAILED ${t.javaClass.simpleName}: $reason"
                    )
                    updateServiceNotification("Connection lost • retrying")
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running || !reconnectScheduled.compareAndSet(false, true)) return

        reconnectExecutor.schedule({
            reconnectScheduled.set(false)
            if (running) connectWebSocket()
        }, 2, TimeUnit.SECONDS)
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
            Prefs.setLastReceive(this, "${payload.appName}: ${payload.title.take(50)} at ${timeNow()}")
        } catch (_: Exception) {
            // Ignore keepalives, malformed messages, and traffic encrypted with another Pair Code.
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

    private fun serviceNotification(status: String): Notification =
        Notification.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Notification receiver")
            .setContentText(status)
            .setOngoing(true)
            .build()

    private fun updateServiceNotification(status: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(SERVICE_NOTIFICATION_ID, serviceNotification(status))
    }

    private fun stopReceiver() {
        running = false
        webSocket?.close(1000, "Receiver stopped")
        webSocket = null
        Prefs.setReceiverStatus(this, "stopped at ${timeNow()}")
        stopForeground(true)
        stopSelf()
    }

    private fun timeNow(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    companion object {
        const val ACTION_STOP = "com.mtpali.notification.STOP_RECEIVER"
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val CHANNEL_SERVICE = "receiver_service"
        private const val CHANNEL_MIRRORED = "mirrored_notifications"
    }
}
