package com.mtpali.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.RejectedExecutionException
import kotlin.random.Random

/** One ntfy socket on either phone; Sender listens for actions, Receiver for mirrors. */
class ReceiverService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var client: OkHttpClient
    private lateinit var connectivity: ConnectivityManager
    @Volatile private var running = false
    @Volatile private var receiveEpoch = 0L
    private val receiveIo = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(256),
        { runnable -> Thread(runnable, "notification-compatibility") }).apply { allowCoreThreadTimeOut(true) }
    private var registered = false
    private var socket: WebSocket? = null
    private var session = ""
    private var failures = 0
    private var reconnectPending = false
    private var lastSync = 0L

    private val reconnect = Runnable {
        reconnectPending = false
        applyNetwork()
    }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { handler.post { applyNetwork(true) } }
        override fun onLost(network: Network) { handler.post { applyNetwork() } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            handler.post { applyNetwork() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        manager().createNotificationChannel(NotificationChannel(CHANNEL, "Compatibility", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) })
        connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        client = OkHttpClient.Builder().pingInterval(60, TimeUnit.SECONDS).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(SERVICE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else startForeground(SERVICE_ID, notification())
        } catch (_: RuntimeException) {
            Diagnostics.error(this, "Compatibility service could not start")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!shouldRun()) {
            stopReceiver()
            return START_NOT_STICKY
        }
        running = true
        val newSession = Prefs.mode(this) + ":" + Prefs.pairCode(this)
        if (session != newSession) {
            disconnect()
            session = newSession
            failures = 0
        }
        if (!registered) {
            try {
                connectivity.registerDefaultNetworkCallback(networkCallback)
                registered = true
            } catch (_: RuntimeException) { Diagnostics.error(this, "Network monitoring unavailable") }
        }
        applyNetwork()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        disconnect()
        receiveIo.shutdownNow()
        if (registered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        if (::client.isInitialized) {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
        super.onDestroy()
    }

    private fun shouldRun(): Boolean = Prefs.receiverTransport(this) == Prefs.RECEIVER_STABLE &&
        CryptoBox.isValidPairCode(Prefs.pairCode(this)) &&
        (Prefs.mode(this) == Prefs.MODE_SENDER || Prefs.receiverEnabled(this))

    private fun online(): Boolean {
        return runCatching {
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
    }

    private fun applyNetwork(resetBackoff: Boolean = false) {
        if (!running) return
        if (!shouldRun()) { stopReceiver(); return }
        if (!online()) {
            disconnect()
            updateStatus("Offline")
        } else if (resetBackoff) {
            failures = 0
            handler.removeCallbacks(reconnect)
            reconnectPending = false
            if (socket == null) connect()
        } else if (socket == null && !reconnectPending) connect()
    }

    private fun connect() {
        if (!running || !shouldRun() || socket != null || !online()) return
        val pairCode = Prefs.pairCode(this)
        val commandMode = Prefs.mode(this) == Prefs.MODE_SENDER
        val activeSession = session
        val topic = if (commandMode) CryptoBox.commandTopic(pairCode) else CryptoBox.topic(pairCode)
        val cursor = if (commandMode) Prefs.lastCommandId(this) else Prefs.lastMessageId(this)
        val since = cursor.ifBlank { "10m" }
        updateStatus("Connecting…")
        try {
            val request = Request.Builder().url("wss://ntfy.sh/$topic/ws?since=" + URLEncoder.encode(since, "UTF-8"))
                .header("User-Agent", "Notification-Android/1.1").build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    handler.post {
                        if (socket !== webSocket || !running || session != activeSession) return@post
                        failures = 0
                        updateStatus("Connected")
                        if (!commandMode && System.currentTimeMillis() - lastSync > 30_000) {
                            lastSync = System.currentTimeMillis()
                            RelayClient.requestSync(applicationContext)
                        }
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    handler.post {
                        if (socket !== webSocket || !running || session != activeSession || !shouldRun()) return@post
                        val epoch = receiveEpoch
                        try {
                            receiveIo.execute {
                                if (running && epoch == receiveEpoch) receive(pairCode, commandMode, text, epoch)
                            }
                        } catch (_: RejectedExecutionException) {
                            // Leave the cursor before the gap. Reconnection replays the uncommitted batch.
                            disconnect()
                            Diagnostics.error(applicationContext, "Compatibility backlog; reconnecting to replay")
                            scheduleReconnect()
                        }
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { failed(webSocket, activeSession, false) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failed(webSocket, activeSession, response?.code == 400 && cursor.isNotBlank())
                }
            })
        } catch (_: RuntimeException) { scheduleReconnect() }
    }

    private fun failed(webSocket: WebSocket, activeSession: String, resetCursor: Boolean) {
        handler.post {
            if (!running || socket !== webSocket || session != activeSession) return@post
            socket = null
            if (resetCursor) {
                if (Prefs.mode(this) == Prefs.MODE_SENDER) Prefs.setLastCommandId(this, "")
                else Prefs.setLastMessageId(this, "")
            }
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!running || !shouldRun() || reconnectPending) return
        if (!online()) { updateStatus("Offline"); return }
        reconnectPending = true
        val delay = (2_000L * (1L shl failures.coerceAtMost(5))).coerceAtMost(60_000) + Random.nextLong(1_000)
        failures++
        updateStatus("Reconnecting…")
        handler.postDelayed(reconnect, delay)
    }

    private fun receive(pairCode: String, commandMode: Boolean, line: String, epoch: Long) {
        runCatching {
            val envelope = JSONObject(line)
            if (envelope.optString("event") != "message") return
            val id = envelope.optString("id")
            val raw = CryptoBox.decrypt(pairCode, envelope.getString("message"))
            if (!running || epoch != receiveEpoch || pairCode != Prefs.pairCode(this)) return
            val accepted: Boolean
            if (commandMode) {
                val command = CommandPayload.fromJson(raw)
                accepted = !command.isFresh() || MirrorNotificationListener.dispatchCommand(this, command, id, pairCode)
            } else {
                accepted = SyncRepository.receive(this, MirrorPayload.fromJson(raw), id)
            }
            if (accepted) handler.post {
                if (running && epoch == receiveEpoch && pairCode == Prefs.pairCode(this)) {
                    if (commandMode) Prefs.setLastCommandId(this, id) else Prefs.setLastMessageId(this, id)
                }
            }
        }
    }

    private fun disconnect() {
        receiveEpoch++
        handler.removeCallbacks(reconnect)
        reconnectPending = false
        val old = socket
        socket = null
        old?.cancel()
    }

    private fun manager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun notification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification).setContentTitle("Notification • Compatibility")
        .setShowWhen(false).setOngoing(true).setOnlyAlertOnce(true).build()

    private fun updateStatus(status: String) {
        Diagnostics.connection(this, status)
    }

    private fun stopReceiver() {
        running = false
        disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val SERVICE_ID = 1001
        private const val CHANNEL = "receiver_service"
    }
}
