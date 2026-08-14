package com.mtpali.notification

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

class MirrorNotificationListener : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var listenerReady = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        listenerReady = true
        refreshState()
    }

    override fun onListenerDisconnected() {
        val shouldRebind = shouldKeepListenerBound()
        listenerReady = false
        if (activeInstance === this) activeInstance = null
        super.onListenerDisconnected()
        if (shouldRebind) requestSelfRebind()
    }

    override fun onDestroy() {
        listenerReady = false
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (Prefs.mode(this) != Prefs.MODE_SENDER ||
            !CryptoBox.isValidPairCode(Prefs.pairCode(this))
        ) return

        drainPendingCommand()
        if (!shouldForward(sbn)) return

        val notification = sbn.notification ?: return
        val actions = notification.actions ?: emptyArray()
        val canReply = actions.any(::isReplyAction)
        val canMarkRead = actions.any(::isMarkReadAction)

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

    private fun refreshState() {
        if (!listenerReady) return
        if (Prefs.mode(this) == Prefs.MODE_SENDER) drainPendingCommand()
    }

    private fun shouldKeepListenerBound(): Boolean {
        if (Prefs.mode(this) == Prefs.MODE_SENDER) return true
        return Prefs.mode(this) == Prefs.MODE_RECEIVER &&
            Prefs.receiverTransport(this) == Prefs.RECEIVER_HIDDEN &&
            Prefs.receiverEnabled(this) &&
            CryptoBox.isValidPairCode(Prefs.pairCode(this))
    }

    private fun requestSelfRebind() {
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, MirrorNotificationListener::class.java)
            )
        } catch (_: Exception) {
        }
    }

    private fun shouldForward(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        return Prefs.forwardAllApps(this) || sbn.packageName in Prefs.selectedApps(this)
    }

    private fun isReplyAction(action: Notification.Action): Boolean =
        !action.remoteInputs.isNullOrEmpty()

    private fun isMarkReadAction(action: Notification.Action): Boolean {
        if (action.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) return true
        val title = action.title?.toString()?.lowercase(Locale.ROOT).orEmpty()
        return title.contains("mark as read") || title == "read" || title.contains("خوانده")
    }

    private fun drainPendingCommand() {
        if (!listenerReady || Prefs.mode(this) != Prefs.MODE_SENDER) return

        val raw = Prefs.pendingCommand(this)
        if (raw.isBlank()) return

        val command = try {
            CommandPayload.fromJson(raw)
        } catch (_: Exception) {
            Prefs.clearPendingCommand(this)
            return
        }

        val now = System.currentTimeMillis()
        if (command.createdAt <= 0L ||
            command.createdAt > now + 60_000L ||
            now - command.createdAt > COMMAND_MAX_AGE_MS
        ) {
            Prefs.clearPendingCommand(this)
            return
        }

        if (executeCommand(command)) Prefs.clearPendingCommand(this)
    }

    private fun executeCommand(command: CommandPayload): Boolean {
        if (!listenerReady || Prefs.mode(this) != Prefs.MODE_SENDER) return false
        return try {
            val sbn = activeNotifications.firstOrNull {
                it.key == command.notificationKey &&
                    (command.packageName.isBlank() || it.packageName == command.packageName)
            } ?: return false

            val actions = sbn.notification.actions ?: return false
            when (command.type) {
                CommandPayload.TYPE_REPLY -> {
                    if (command.text.isBlank()) return false
                    val action = actions.firstOrNull(::isReplyAction) ?: return false
                    val remoteInputs = action.remoteInputs ?: return false
                    val fillInIntent = Intent()
                    val results = Bundle()
                    remoteInputs.forEach { results.putCharSequence(it.resultKey, command.text) }
                    RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, results)
                    RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
                    action.actionIntent.send(this, 0, fillInIntent)
                    true
                }

                CommandPayload.TYPE_MARK_READ -> {
                    val action = actions.firstOrNull(::isMarkReadAction) ?: return false
                    action.actionIntent.send()
                    true
                }

                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val COMMAND_MAX_AGE_MS = 10 * 60_000L

        @Volatile private var activeInstance: MirrorNotificationListener? = null

        fun refresh(context: Context) {
            val instance = activeInstance
            if (instance != null && instance.listenerReady) {
                instance.mainHandler.post { instance.refreshState() }
                return
            }

            if (shouldRequestBinding(context)) requestRebind(context)
        }

        fun dispatchCommand(context: Context, command: CommandPayload) {
            val appContext = context.applicationContext
            if (Prefs.mode(appContext) != Prefs.MODE_SENDER) return

            Prefs.setPendingCommand(appContext, command.toJson())
            val instance = activeInstance
            if (instance != null && instance.listenerReady) {
                instance.mainHandler.post { instance.drainPendingCommand() }
            } else {
                requestRebind(appContext)
            }
        }

        private fun shouldRequestBinding(context: Context): Boolean {
            if (Prefs.mode(context) == Prefs.MODE_SENDER) return true
            return Prefs.mode(context) == Prefs.MODE_RECEIVER &&
                Prefs.receiverTransport(context) == Prefs.RECEIVER_HIDDEN &&
                Prefs.receiverEnabled(context) &&
                CryptoBox.isValidPairCode(Prefs.pairCode(context))
        }

        private fun requestRebind(context: Context) {
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(context, MirrorNotificationListener::class.java)
                )
            } catch (_: Exception) {
            }
        }
    }
}
