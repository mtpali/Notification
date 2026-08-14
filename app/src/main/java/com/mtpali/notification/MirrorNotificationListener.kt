package com.mtpali.notification

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MirrorNotificationListener : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor()
    private val reconnectScheduled = AtomicBoolean(false)
    private val actionableKeys = HashSet<String>()

    @Volatile private var listenerReady = false
    @Volatile private var commandSocket: WebSocket? = null
    private var commandClient: OkHttpClient? = null
    private var socketPair = ""

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerReady = true
        rebuildActionableKeys()
        updateCommandSocketState()
    }

    override fun onListenerDisconnected() {
        listenerReady = false
        actionableKeys.clear()
        stopCommandSocket()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        listenerReady = false
        actionableKeys.clear()
        stopCommandSocket()
        reconnectExecutor.shutdownNow()
        commandClient?.dispatcher?.executorService?.shutdown()
        commandClient?.connectionPool?.evictAll()
        commandClient = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (Prefs.mode(this) != Prefs.MODE_SENDER ||
            !CryptoBox.isValidPairCode(Prefs.pairCode(this))
        ) {
            actionableKeys.clear()
            updateCommandSocketState()
            return
        }

        if (!shouldForward(sbn)) {
            actionableKeys.remove(sbn.key)
            updateCommandSocketState()
            return
        }

        val notification = sbn.notification ?: return
        val actions = notification.actions ?: emptyArray()
        val canReply = actions.any(::isReplyAction)
        val canMarkRead = actions.any(::isMarkReadAction)

        if (canReply || canMarkRead) actionableKeys.add(sbn.key)
        else actionableKeys.remove(sbn.key)
        updateCommandSocketState()

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val text = bigText.ifBlank { normalText }

        val appName = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        RelayClient.publish(
            applicationContext,
            MirrorPayload(
                packageName = sbn.packageName,
                appName = appName,
                title = title,
                text = text,
                postTime = sbn.postTime,
                notificationKey = sbn.key,
                canReply = canReply,
                canMarkRead = canMarkRead
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && actionableKeys.remove(sbn.key)) updateCommandSocketState()
    }

    private fun shouldForward(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        return Prefs.forwardAllApps(this) || sbn.packageName in Prefs.selectedApps(this)
    }

    private fun rebuildActionableKeys() {
        actionableKeys.clear()
        if (Prefs.mode(this) != Prefs.MODE_SENDER ||
            !CryptoBox.isValidPairCode(Prefs.pairCode(this))
        ) return

        try {
            activeNotifications.forEach { sbn ->
                if (!shouldForward(sbn)) return@forEach
                val actions = sbn.notification?.actions ?: return@forEach
                if (actions.any(::isReplyAction) || actions.any(::isMarkReadAction)) {
                    actionableKeys.add(sbn.key)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun isReplyAction(action: Notification.Action): Boolean =
        !action.remoteInputs.isNullOrEmpty()

    private fun isMarkReadAction(action: Notification.Action): Boolean {
        if (action.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) return true
        val title = action.title?.toString()?.lowercase(Locale.ROOT).orEmpty()
        return title.contains("mark as read") || title == "read" || title.contains("خوانده")
    }

    private fun updateCommandSocketState() {
        if (actionableKeys.isEmpty()) stopCommandSocket() else ensureCommandSocket()
    }

    private fun ensureCommandSocket() {
        if (!listenerReady || actionableKeys.isEmpty()) return

        val pairCode = Prefs.pairCode(this)
        if (Prefs.mode(this) != Prefs.MODE_SENDER || !CryptoBox.isValidPairCode(pairCode)) {
            stopCommandSocket()
            return
        }

        if (commandSocket != null && socketPair == pairCode) return
        stopCommandSocket()
        socketPair = pairCode

        val client = commandClient ?: OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
            .also { commandClient = it }

        val lastId = Prefs.lastCommandId(this)
        val since = if (lastId.isBlank()) "2m" else lastId
        val request = Request.Builder()
            .url(
                "wss://ntfy.sh/${CryptoBox.commandTopic(pairCode)}/ws?since=" +
                    URLEncoder.encode(since, "UTF-8")
            )
            .header("User-Agent", "Notification-Android/0.6")
            .build()

        commandSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleCommandLine(pairCode, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (commandSocket === webSocket) commandSocket = null
                if (listenerReady && actionableKeys.isNotEmpty() &&
                    Prefs.mode(this@MirrorNotificationListener) == Prefs.MODE_SENDER
                ) scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (commandSocket === webSocket) commandSocket = null
                if (listenerReady && actionableKeys.isNotEmpty() &&
                    Prefs.mode(this@MirrorNotificationListener) == Prefs.MODE_SENDER
                ) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!listenerReady || actionableKeys.isEmpty() ||
            !reconnectScheduled.compareAndSet(false, true)
        ) return

        reconnectExecutor.schedule({
            reconnectScheduled.set(false)
            if (listenerReady && actionableKeys.isNotEmpty()) ensureCommandSocket()
        }, 2, TimeUnit.SECONDS)
    }

    private fun stopCommandSocket() {
        commandSocket?.cancel()
        commandSocket = null
        socketPair = ""
    }

    private fun handleCommandLine(pairCode: String, line: String) {
        if (line.isBlank()) return
        try {
            val envelope = JSONObject(line)
            if (envelope.optString("event") != "message") return

            val id = envelope.optString("id")
            if (id.isNotBlank() && id == Prefs.lastCommandId(this)) return

            val encrypted = envelope.optString("message")
            if (encrypted.isBlank()) return

            val command = CommandPayload.fromJson(CryptoBox.decrypt(pairCode, encrypted))
            val now = System.currentTimeMillis()
            if (command.createdAt <= 0L || command.createdAt > now + 60_000L ||
                now - command.createdAt > 10 * 60_000L
            ) {
                if (id.isNotBlank()) Prefs.setLastCommandId(this, id)
                return
            }

            if (id.isNotBlank()) Prefs.setLastCommandId(this, id)
            mainHandler.post { executeCommand(command) }
        } catch (_: Exception) {
        }
    }

    private fun executeCommand(command: CommandPayload) {
        if (!listenerReady || Prefs.mode(this) != Prefs.MODE_SENDER) return
        try {
            val sbn = activeNotifications.firstOrNull {
                it.key == command.notificationKey &&
                    (command.packageName.isBlank() || it.packageName == command.packageName)
            } ?: return

            val actions = sbn.notification.actions ?: return
            when (command.type) {
                CommandPayload.TYPE_REPLY -> {
                    if (command.text.isBlank()) return
                    val action = actions.firstOrNull(::isReplyAction) ?: return
                    val remoteInputs = action.remoteInputs ?: return
                    val fillInIntent = Intent()
                    val results = Bundle()
                    remoteInputs.forEach { results.putCharSequence(it.resultKey, command.text) }
                    RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, results)
                    RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
                    action.actionIntent.send(this, 0, fillInIntent)
                }

                CommandPayload.TYPE_MARK_READ -> {
                    actions.firstOrNull(::isMarkReadAction)?.actionIntent?.send()
                }
            }
        } catch (_: Exception) {
        }
    }
}
