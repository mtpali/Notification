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
import java.util.UUID

class MirrorNotificationListener : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fingerprints = linkedMapOf<String, String>()
    @Volatile private var listenerReady = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        listenerReady = true
        DeliveryRuntime.sync(this)
        val legacy = Prefs.pendingCommand(this)
        if (legacy.isNotBlank()) {
            runCatching { CommandInbox.add(this, CommandPayload.fromJson(legacy), "legacy") }
            Prefs.clearPendingCommand(this)
        }
        drainCommands()
        publishSnapshot()
    }

    override fun onListenerDisconnected() {
        listenerReady = false
        if (activeInstance === this) activeInstance = null
        super.onListenerDisconnected()
        if (Prefs.mode(this) == Prefs.MODE_SENDER) requestRebind(this)
    }

    override fun onDestroy() {
        listenerReady = false
        mainHandler.removeCallbacksAndMessages(null)
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !shouldForward(sbn)) return
        drainCommands()
        val payload = payloadFor(sbn)
        val fingerprint = listOf(payload.appName, payload.title, payload.text,
            payload.postTime, payload.canReply, payload.canMarkRead).joinToString("\u0000")
        if (fingerprints[sbn.key] == fingerprint) return
        if (RelayClient.publishAccepted(this, payload)) {
            fingerprints[sbn.key] = fingerprint
            while (fingerprints.size > 200) fingerprints.remove(fingerprints.keys.first())
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        fingerprints.remove(sbn.key)
        if (!shouldForward(sbn)) return
        RelayClient.publish(this, MirrorPayload(sbn.packageName, "", "", "", sbn.postTime, sbn.key,
            event = MirrorPayload.EVENT_REMOVE, eventTime = Prefs.nextEventTime(this)))
    }

    private fun shouldForward(sbn: StatusBarNotification): Boolean =
        Prefs.mode(this) == Prefs.MODE_SENDER && CryptoBox.isValidPairCode(Prefs.pairCode(this)) &&
            sbn.packageName != packageName &&
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
            (Prefs.forwardAllApps(this) || sbn.packageName in Prefs.selectedApps(this))

    private fun payloadFor(sbn: StatusBarNotification): MirrorPayload {
        val n = sbn.notification
        val actions = n.actions ?: emptyArray()
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        return MirrorPayload(sbn.packageName, app.take(128),
            n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(256),
            n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
                .ifBlank { n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() }.take(1800),
            sbn.postTime, sbn.key, actions.any(::isReplyAction), actions.any(::isMarkReadAction),
            eventTime = Prefs.nextEventTime(this))
    }

    private fun publishSnapshot() {
        if (!listenerReady || Prefs.mode(this) != Prefs.MODE_SENDER ||
            !CryptoBox.isValidPairCode(Prefs.pairCode(this))) return
        val notifications = runCatching { activeNotifications.filter(::shouldForward) }.getOrElse { return }
        if (notifications.size > 200) {
            Diagnostics.error(this, "Sync supports up to 200 active notifications")
            notifications.take(200).forEach { RelayClient.publish(this, payloadFor(it)) }
            return
        }
        val snapshotId = UUID.randomUUID().toString()
        val snapshotTime = Prefs.nextEventTime(this)
        val start = MirrorPayload(packageName, "", "", "", 0, "",
            event = MirrorPayload.EVENT_SNAPSHOT_START, eventTime = snapshotTime,
            snapshotId = snapshotId, snapshotTime = snapshotTime, snapshotCount = notifications.size)
        RelayClient.publish(this, start)
        notifications.forEach {
            RelayClient.publish(this, payloadFor(it).copy(snapshotId = snapshotId,
                snapshotTime = snapshotTime, snapshotCount = notifications.size))
        }
        RelayClient.publish(this, start.copy(event = MirrorPayload.EVENT_SNAPSHOT_END,
            eventTime = Prefs.nextEventTime(this), eventId = UUID.randomUUID().toString()))
    }

    private fun drainCommands() {
        if (!listenerReady || Prefs.mode(this) != Prefs.MODE_SENDER) return
        while (true) {
            val command = CommandInbox.take(this) ?: break
            if (command.type == CommandPayload.TYPE_SYNC) publishSnapshot()
            else if (!executeCommand(command)) Diagnostics.error(this, "Original notification action is no longer available")
        }
    }

    private fun executeCommand(command: CommandPayload): Boolean {
        return runCatching {
        val sbn = activeNotifications.firstOrNull {
            it.key == command.notificationKey && it.packageName == command.packageName &&
                (command.sourcePostTime == 0L || it.postTime == command.sourcePostTime)
        } ?: return false
        if (command.type == CommandPayload.TYPE_DISMISS) {
            cancelNotification(sbn.key)
            return true
        }
        val actions = sbn.notification.actions ?: return false
        when (command.type) {
            CommandPayload.TYPE_REPLY -> {
                if (command.text.isBlank()) return false
                val action = actions.firstOrNull(::isReplyAction) ?: return false
                val inputs = action.remoteInputs?.filter { it.allowFreeFormInput }?.toTypedArray() ?: return false
                val result = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, command.text) } }
                val fillIn = Intent()
                RemoteInput.addResultsToIntent(inputs, fillIn, result)
                RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
                action.actionIntent.send(this, 0, fillIn)
                true
            }
            CommandPayload.TYPE_MARK_READ -> {
                val action = actions.firstOrNull(::isMarkReadAction) ?: return false
                action.actionIntent.send()
                true
            }
            else -> false
        }
        }.getOrDefault(false)
    }

    private fun isReplyAction(action: Notification.Action): Boolean =
        action.remoteInputs?.any { it.allowFreeFormInput } == true

    private fun isMarkReadAction(action: Notification.Action): Boolean {
        if (action.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) return true
        val title = action.title?.toString()?.lowercase(Locale.ROOT).orEmpty()
        return title.contains("mark as read") || title == "read" || title.contains("خوانده")
    }

    companion object {
        @Volatile private var activeInstance: MirrorNotificationListener? = null

        fun refresh(context: Context) {
            activeInstance?.takeIf { it.listenerReady }?.let { instance ->
                instance.mainHandler.post { instance.drainCommands() }
            } ?: requestRebind(context)
        }

        fun requestSnapshot(context: Context) {
            activeInstance?.takeIf { it.listenerReady }?.let { instance ->
                instance.mainHandler.post { instance.publishSnapshot() }
            } ?: requestRebind(context)
        }

        fun dispatchCommand(context: Context, command: CommandPayload, relayId: String = ""): Boolean {
            if (Prefs.mode(context) != Prefs.MODE_SENDER || !command.isFresh()) return false
            if (!CommandInbox.add(context.applicationContext, command, relayId)) return false
            refresh(context)
            return true
        }

        private fun requestRebind(context: Context) {
            if (Prefs.mode(context) == Prefs.MODE_SENDER) runCatching {
                NotificationListenerService.requestRebind(ComponentName(context, MirrorNotificationListener::class.java))
            }
        }
    }
}
