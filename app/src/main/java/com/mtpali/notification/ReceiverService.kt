package com.mtpali.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ReceiverService : Service() {
    private lateinit var client: OkHttpClient
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor()
    private val reconnectScheduled = AtomicBoolean(false)

    @Volatile private var running = false
    @Volatile private var webSocket: WebSocket? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        client = OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification("Connecting…"))

        if (Prefs.mode(this) != Prefs.MODE_RECEIVER ||
            !CryptoBox.isValidPairCode(Prefs.pairCode(this))
        ) {
            stopReceiver()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
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

        val lastId = Prefs.lastMessageId(this)
        val since = if (lastId.isBlank()) "10m" else lastId
        val request = Request.Builder()
            .url(
                "wss://ntfy.sh/${CryptoBox.topic(pairCode)}/ws?since=" +
                    URLEncoder.encode(since, "UTF-8")
            )
            .header("User-Agent", "Notification-Android/0.6")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                updateServiceNotification("Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRelayLine(pairCode, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (this@ReceiverService.webSocket === webSocket) this@ReceiverService.webSocket = null
                if (running) {
                    updateServiceNotification("Reconnecting…")
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (this@ReceiverService.webSocket === webSocket) this@ReceiverService.webSocket = null
                if (running) {
                    updateServiceNotification("Reconnecting…")
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

            val payload = MirrorPayload.fromJson(CryptoBox.decrypt(pairCode, encrypted))
            showMirroredNotification(payload, id)
            if (id.isNotBlank()) Prefs.setLastMessageId(this, id)
        } catch (_: Exception) {
        }
    }

    private fun showMirroredNotification(payload: MirrorPayload, relayId: String) {
        val sourceApp = payload.appName.ifBlank {
            payload.packageName.substringAfterLast('.').ifBlank { "Notification" }
        }
        val originalTitle = payload.title.trim()
        val displayTitle = when {
            originalTitle.isBlank() -> sourceApp
            originalTitle.equals(sourceApp, ignoreCase = true) -> sourceApp
            else -> "$sourceApp • $originalTitle"
        }
        val body = payload.text.ifBlank { sourceApp }
        val stableKey = payload.notificationKey.ifBlank { relayId }
        val localId = (payload.packageName + ":" + stableKey).hashCode()

        val builder = Notification.Builder(this, CHANNEL_MIRRORED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(body)
            .setSubText(sourceApp)
            .setStyle(Notification.BigTextStyle().setBigContentTitle(displayTitle).bigText(body))
            .setAutoCancel(true)
            .setWhen(payload.postTime.takeIf { it > 0 } ?: System.currentTimeMillis())

        if (payload.canReply && payload.notificationKey.isNotBlank()) {
            builder.addAction(replyAction(payload, localId))
        }
        if (payload.canMarkRead && payload.notificationKey.isNotBlank()) {
            builder.addAction(markReadAction(payload, localId))
        }

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(localId, builder.build())
    }

    private fun replyAction(payload: MirrorPayload, localId: Int): Notification.Action {
        val intent = commandIntent(ActionCommandReceiver.ACTION_REPLY, payload, localId)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            (payload.notificationKey + ":reply").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val remoteInput = RemoteInput.Builder(ActionCommandReceiver.KEY_REPLY_TEXT)
            .setLabel("Reply")
            .build()

        return Notification.Action.Builder(R.drawable.ic_notification, "Reply", pendingIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun markReadAction(payload: MirrorPayload, localId: Int): Notification.Action {
        val intent = commandIntent(ActionCommandReceiver.ACTION_MARK_READ, payload, localId)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            (payload.notificationKey + ":read").hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(R.drawable.ic_notification, "Mark as read", pendingIntent)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()
    }

    private fun commandIntent(action: String, payload: MirrorPayload, localId: Int) =
        Intent(this, ActionCommandReceiver::class.java).apply {
            this.action = action
            putExtra(ActionCommandReceiver.EXTRA_PACKAGE, payload.packageName)
            putExtra(ActionCommandReceiver.EXTRA_NOTIFICATION_KEY, payload.notificationKey)
            putExtra(ActionCommandReceiver.EXTRA_LOCAL_ID, localId)
        }

    private fun createChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Receiver", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MIRRORED, "Mirrored", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun serviceNotification(status: String): Notification =
        Notification.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Notification")
            .setContentText(status)
            .setOngoing(true)
            .build()

    private fun updateServiceNotification(status: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(SERVICE_NOTIFICATION_ID, serviceNotification(status))
    }

    private fun stopReceiver() {
        running = false
        webSocket?.close(1000, "stop")
        webSocket = null
        stopForeground(true)
        stopSelf()
    }

    companion object {
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val CHANNEL_SERVICE = "receiver_service"
        private const val CHANNEL_MIRRORED = "mirrored_notifications"
    }
}
