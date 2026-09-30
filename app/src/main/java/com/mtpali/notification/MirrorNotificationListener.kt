package com.mtpali.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.UUID

class MirrorNotificationListener : NotificationListenerService() {
    private val fingerprints = linkedMapOf<String, String>()
    private val appLabels = linkedMapOf<String, String>()
    private var captureScope = ""
    @Volatile private var listenerReady = false
    private val work = ListenerWorkQueue(
        onGap = { Diagnostics.error(this, "Notification backlog reconciled"); publishSnapshot() },
        onError = { Diagnostics.error(this, "Notification processing interrupted") })

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        listenerReady = true
        DeliveryRuntime.sync(this)
        work.commands {
            val legacy = Prefs.pendingCommand(this)
            if (legacy.isNotBlank()) {
                runCatching { CommandInbox.add(this, CommandPayload.fromJson(legacy), "legacy") }
                Prefs.clearPendingCommand(this)
            }
            drainCommands()
        }
        work.snapshot { publishSnapshot() }
    }

    override fun onListenerDisconnected() {
        listenerReady = false
        if (activeInstance === this) activeInstance = null
        super.onListenerDisconnected()
        requestRebind(this)
    }

    override fun onDestroy() {
        listenerReady = false
        work.close()
        if (activeInstance === this) activeInstance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !listenerReady || sbn.packageName == packageName) return
        work.event(sbn.key, true) {
            if (!listenerReady || !shouldForward(sbn)) return@event
            val current = getActiveNotifications(arrayOf(sbn.key)).orEmpty().firstOrNull() ?: return@event
            if (!shouldForward(current)) return@event
            resetCaptureScope()
            val payload = payloadFor(current)
            if (fingerprints[sbn.key] == payload.generation) return@event
            if (RelayClient.publishAccepted(this, payload.copy(eventTime = Prefs.nextEventTime(this)))) {
                fingerprints[sbn.key] = payload.generation
                while (fingerprints.size > 200) fingerprints.remove(fingerprints.keys.first())
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        work.event(sbn.key, false) {
            if (listenerReady && getActiveNotifications(arrayOf(sbn.key)).orEmpty().isNotEmpty()) return@event
            resetCaptureScope()
            fingerprints.remove(sbn.key)
            if (!listenerReady || !shouldForward(sbn)) return@event
            RelayClient.publish(this, MirrorPayload(sbn.packageName, "", "", "", sbn.postTime, sbn.key,
                event = MirrorPayload.EVENT_REMOVE, eventTime = Prefs.nextEventTime(this)))
        }
    }

    private fun resetCaptureScope() {
        val current = Prefs.mode(this) + ":" + Prefs.pairCode(this) + ":" + Prefs.receiverTransport(this) +
            ":" + Prefs.relayUrl(this) + ":" + Prefs.forwardAllApps(this) + ":" + Prefs.selectedApps(this).sorted()
        if (captureScope != current) { fingerprints.clear(); captureScope = current }
    }

    private fun shouldForward(sbn: StatusBarNotification): Boolean =
        Prefs.mode(this) == Prefs.MODE_SENDER && CryptoBox.isValidPairCode(Prefs.pairCode(this)) &&
            sbn.packageName != packageName &&
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
            (Prefs.forwardAllApps(this) || sbn.packageName in Prefs.selectedApps(this))

    private fun payloadFor(sbn: StatusBarNotification): MirrorPayload {
        val snapshot = ReplyCore.snapshot(sbn)
        val app = appLabels[sbn.packageName] ?: runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName).also {
            appLabels[sbn.packageName] = it
            while (appLabels.size > 128) appLabels.remove(appLabels.keys.first())
        }
        return MirrorPayload(sbn.packageName, app.take(128), snapshot.title.take(256), snapshot.text.take(1800),
            sbn.postTime, sbn.key, snapshot.reply != null, snapshot.read != null, eventTime = 0,
            generation = snapshot.generation, replyActionId = snapshot.replyId, readActionId = snapshot.readId,
            sourceUserId = snapshot.userId)
    }

    private fun publishSnapshot() {
        if (!listenerReady || Prefs.mode(this) != Prefs.MODE_SENDER ||
            !CryptoBox.isValidPairCode(Prefs.pairCode(this))) return
        resetCaptureScope()
        val notifications = runCatching { activeNotifications.filter(::shouldForward) }.getOrElse { return }
        if (notifications.size > 200) {
            Diagnostics.error(this, "Sync supports up to 200 active notifications")
            notifications.take(200).forEach {
                RelayClient.publish(this, payloadFor(it).copy(eventTime = Prefs.nextEventTime(this)))
            }
            return
        }
        val snapshotId = UUID.randomUUID().toString()
        val snapshotTime = Prefs.nextEventTime(this)
        val start = MirrorPayload(packageName, "", "", "", 0, "",
            event = MirrorPayload.EVENT_SNAPSHOT_START, eventTime = snapshotTime,
            snapshotId = snapshotId, snapshotTime = snapshotTime, snapshotCount = notifications.size)
        RelayClient.publish(this, start)
        notifications.forEach {
            val payload = payloadFor(it).copy(eventTime = Prefs.nextEventTime(this), snapshotId = snapshotId,
                snapshotTime = snapshotTime, snapshotCount = notifications.size)
            if (RelayClient.publishAccepted(this, payload)) fingerprints[it.key] = payload.generation
        }
        RelayClient.publish(this, start.copy(event = MirrorPayload.EVENT_SNAPSHOT_END,
            eventTime = Prefs.nextEventTime(this), eventId = UUID.randomUUID().toString()))
    }

    private fun acknowledge(claim: CommandInbox.Claimed, status: DispatchStatus): Boolean {
        if (claim.scope != CryptoBox.commandTopic(Prefs.pairCode(this))) return true
        if (claim.command.type == CommandPayload.TYPE_SYNC) return true
        val command = claim.command
        return RelayClient.publishAccepted(this, MirrorPayload(command.packageName, "", "", "",
            command.sourcePostTime, command.notificationKey, event = MirrorPayload.EVENT_ACTION_RESULT,
            eventTime = Prefs.nextEventTime(this), eventId = "result:${command.id}",
            generation = command.generation, sourceUserId = command.sourceUserId,
            commandId = command.id, actionStatus = status.name))
    }

    private fun drainCommands() {
        if (!listenerReady || Prefs.mode(this) != Prefs.MODE_SENDER) return
        // A stored claim with no result means the process ended around dispatch. Do not replay it.
        CommandInbox.inFlight(this)?.let { claim ->
            val status = DispatchStatus.parse(claim.result) ?: DispatchStatus.UNKNOWN
            if (!acknowledge(claim, status) || !CommandInbox.finish(this)) return
        }
        while (listenerReady && Prefs.mode(this) == Prefs.MODE_SENDER) {
            val claim = CommandInbox.claim(this) ?: break
            val command = claim.command
            val status = if (!command.isFresh()) DispatchStatus.EXPIRED
                else if (command.type == CommandPayload.TYPE_SYNC) {
                    publishSnapshot(); DispatchStatus.DISPATCHED
                } else executeCommand(claim)
            if (!CommandInbox.result(this, status)) {
                Diagnostics.error(this, "Action result unknown; check the original app")
                return
            }
            if (!acknowledge(claim, status) || !CommandInbox.finish(this)) return
        }
    }

    private fun executeCommand(claim: CommandInbox.Claimed): DispatchStatus {
        val command = claim.command
        if (!listenerReady || claim.scope != CryptoBox.commandTopic(Prefs.pairCode(this))) return DispatchStatus.DENIED
        return try {
            val current = getActiveNotifications(arrayOf(command.notificationKey)).orEmpty().firstOrNull {
                it.key == command.notificationKey && it.packageName == command.packageName
            } ?: return DispatchStatus.STALE
            if (!shouldForward(current)) return DispatchStatus.DENIED
            if (command.type == CommandPayload.TYPE_DISMISS) {
                val snapshot = ReplyCore.snapshot(current)
                if (command.generation.isBlank() || command.generation != snapshot.generation ||
                    command.sourceUserId != snapshot.userId || command.sourcePostTime != current.postTime)
                    DispatchStatus.STALE
                else { cancelNotification(current.key); DispatchStatus.DISPATCHED }
            } else ReplyCore.send(this, current, command)
        } catch (_: SecurityException) { DispatchStatus.DENIED }
        catch (_: Exception) { DispatchStatus.UNKNOWN }
    }

    companion object {
        @Volatile private var activeInstance: MirrorNotificationListener? = null
        private var lastRebind = -30_000L

        fun refresh(context: Context) {
            activeInstance?.takeIf { it.listenerReady }?.let { instance ->
                instance.work.commands { instance.drainCommands() }
            } ?: requestRebind(context)
        }

        fun requestSnapshot(context: Context) {
            activeInstance?.takeIf { it.listenerReady }?.let { instance ->
                instance.work.snapshot { instance.publishSnapshot() }
            } ?: requestRebind(context)
        }

        fun dispatchCommand(context: Context, command: CommandPayload, relayId: String = "",
            expectedPairCode: String = Prefs.pairCode(context)): Boolean {
            if (Prefs.mode(context) != Prefs.MODE_SENDER || expectedPairCode != Prefs.pairCode(context) ||
                !command.isFresh() || command.type !in setOf(CommandPayload.TYPE_SYNC, CommandPayload.TYPE_REPLY,
                    CommandPayload.TYPE_MARK_READ, CommandPayload.TYPE_DISMISS)) return false
            if (!CommandInbox.add(context.applicationContext, command, relayId)) return false
            refresh(context)
            return true
        }

        fun rejectCommand(context: Context, command: CommandPayload, expectedPairCode: String): Boolean {
            if (command.type == CommandPayload.TYPE_SYNC || command.id.isBlank() ||
                Prefs.mode(context) != Prefs.MODE_SENDER || expectedPairCode != Prefs.pairCode(context)) return true
            val status = if (!command.isFresh()) DispatchStatus.EXPIRED
                else if (command.type !in setOf(CommandPayload.TYPE_REPLY, CommandPayload.TYPE_MARK_READ,
                    CommandPayload.TYPE_DISMISS)) DispatchStatus.UNSUPPORTED
                else if (CommandInbox.rejectionRemembered(context, command)) DispatchStatus.BUSY else DispatchStatus.UNKNOWN
            return RelayClient.publishAccepted(context, MirrorPayload(command.packageName, "", "", "", command.sourcePostTime,
                command.notificationKey, event = MirrorPayload.EVENT_ACTION_RESULT, eventTime = Prefs.nextEventTime(context),
                eventId = "result:${command.id}", generation = command.generation, sourceUserId = command.sourceUserId,
                commandId = command.id, actionStatus = status.name))
        }

        @Synchronized
        private fun requestRebind(context: Context) {
            if (Prefs.mode(context) != Prefs.MODE_SENDER) return
            val component = ComponentName(context, MirrorNotificationListener::class.java)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (!manager.isNotificationListenerAccessGranted(component)) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastRebind < 30_000) return
            lastRebind = now
            runCatching { NotificationListenerService.requestRebind(component) }
        }
    }
}
