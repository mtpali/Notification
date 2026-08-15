package com.mtpali.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
    private lateinit var connectivityManager: ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor()
    private val reconnectScheduled = AtomicBoolean(false)

    @Volatile private var running = false
    @Volatile private var networkAvailable = false
    @Volatile private var webSocket: WebSocket? = null
    private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetworkState()
        override fun onLost(network: Network) = updateNetworkState()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            updateNetworkState()
    }

    override fun onCreate() {
        super.onCreate()
        createServiceChannel()
        MirrorNotifier.ensureChannel(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        client = OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification("Connecting…"))

        if (!shouldRun()) {
            stopReceiver()
            return START_NOT_STICKY
        }

        if (!running) {
            running = true
            startNetworkMonitoring()
        } else {
            applyNetworkState()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        reconnectScheduled.set(false)
        stopNetworkMonitoring()
        webSocket?.cancel()
        webSocket = null
        reconnectExecutor.shutdownNow()
        if (::client.isInitialized) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        super.onDestroy()
    }

    private fun shouldRun(): Boolean =
        Prefs.mode(this) == Prefs.MODE_RECEIVER &&
            Prefs.receiverTransport(this) == Prefs.RECEIVER_STABLE &&
            Prefs.receiverEnabled(this) &&
            CryptoBox.isValidPairCode(Prefs.pairCode(this))

    private fun startNetworkMonitoring() {
        if (!networkCallbackRegistered) {
            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
                networkCallbackRegistered = true
            } catch (_: Exception) {
            }
        }
        applyNetworkState()
    }

    private fun stopNetworkMonitoring() {
        if (!networkCallbackRegistered) return
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        networkCallbackRegistered = false
    }

    private fun updateNetworkState() {
        mainHandler.post { applyNetworkState() }
    }

    private fun applyNetworkState() {
        if (!running || !shouldRun()) {
            if (running) stopReceiver()
            return
        }

        networkAvailable = isInternetAvailable()
        if (networkAvailable) {
            if (webSocket == null) {
                updateServiceNotification("Connecting…")
                connectWebSocket()
            }
        } else {
            webSocket?.cancel()
            webSocket = null
            updateServiceNotification("Offline")
        }
    }

    private fun isInternetAvailable(): Boolean {
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            true
        }
    }

    private fun connectWebSocket() {
        if (!running || !networkAvailable || webSocket != null || !shouldRun()) return

        val pairCode = Prefs.pairCode(this)
        val lastId = Prefs.lastMessageId(this)
        val since = if (lastId.isBlank()) "10m" else lastId

        try {
            val request = Request.Builder()
                .url(
                    "wss://ntfy.sh/${CryptoBox.topic(pairCode)}/ws?since=" +
                        URLEncoder.encode(since, "UTF-8")
                )
                .header("User-Agent", "Notification-Android/1.0")
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
                    if (this@ReceiverService.webSocket === webSocket) {
                        this@ReceiverService.webSocket = null
                    }
                    handleDisconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (this@ReceiverService.webSocket === webSocket) {
                        this@ReceiverService.webSocket = null
                    }
                    handleDisconnect()
                }
            })
        } catch (_: Exception) {
            webSocket = null
            if (running && shouldRun()) scheduleReconnect()
        }
    }

    private fun handleDisconnect() {
        if (!running || !shouldRun()) return
        networkAvailable = isInternetAvailable()
        if (networkAvailable) {
            updateServiceNotification("Reconnecting…")
            scheduleReconnect()
        } else {
            updateServiceNotification("Offline")
        }
    }

    private fun scheduleReconnect() {
        if (!running || !shouldRun() || !networkAvailable || reconnectExecutor.isShutdown ||
            !reconnectScheduled.compareAndSet(false, true)
        ) return

        try {
            reconnectExecutor.schedule({
                reconnectScheduled.set(false)
                networkAvailable = isInternetAvailable()
                if (running && shouldRun() && networkAvailable) connectWebSocket()
            }, 2, TimeUnit.SECONDS)
        } catch (_: RuntimeException) {
            reconnectScheduled.set(false)
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

            val payload = MirrorPayload.fromJson(CryptoBox.decrypt(pairCode, encrypted))
            MirrorNotifier.show(applicationContext, payload, id)
            if (id.isNotBlank()) Prefs.setLastMessageId(this, id)
        } catch (_: Exception) {
        }
    }

    private fun createServiceChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Receiver", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
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
        stopNetworkMonitoring()
        webSocket?.close(1000, "stop")
        webSocket = null
        stopForeground(true)
        stopSelf()
    }

    companion object {
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val CHANNEL_SERVICE = "receiver_service"
    }
}
