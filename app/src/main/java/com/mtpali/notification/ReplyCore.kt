package com.mtpali.notification

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification

object ReplyCore {
    data class Snapshot(
        val generation: String, val title: String, val text: String, val userId: Int,
        val actions: List<Pair<ActionDescriptor, Notification.Action>>
    ) {
        val reply = ActionPolicy.reply(actions.map { it.first })
        val read = ActionPolicy.markRead(actions.map { it.first })
        val replyId = NotificationReference.actionId(generation, CommandPayload.TYPE_REPLY, reply)
        val readId = NotificationReference.actionId(generation, CommandPayload.TYPE_MARK_READ, read)
    }

    fun snapshot(sbn: StatusBarNotification): Snapshot {
        val notification = sbn.notification
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            .ifBlank { notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty() }
        val actions = mutableListOf<Pair<ActionDescriptor, Notification.Action>>()
        fun add(origin: String, values: List<Notification.Action>) {
            values.forEachIndexed { index, action ->
                val inputs = action.remoteInputs.orEmpty()
                actions.add(ActionDescriptor(index, origin, action.semanticAction,
                    inputs.map { it.resultKey }, inputs.filter { it.allowFreeFormInput }.map { it.resultKey },
                    action.actionIntent != null,
                    Build.VERSION.SDK_INT >= 31 && action.actionIntent?.isImmutable == true,
                    Build.VERSION.SDK_INT >= 31 && action.isAuthenticationRequired) to action)
            }
        }
        add("native", notification.actions.orEmpty().toList())
        add("wearable", runCatching { Notification.WearableExtender(notification).actions.toList() }.getOrDefault(emptyList()))
        val userId = sbn.user.identifier
        val generation = NotificationReference.hash(sbn.packageName, sbn.key, sbn.postTime.toString(),
            userId.toString(), title, text, notification.`when`.toString(), notification.shortcutId.orEmpty(),
            notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty(),
            actions.joinToString("\u0000") { it.first.signature() })
        return Snapshot(generation, title, text, userId, actions)
    }

    /** The caller must resolve a fresh, active notification from a connected listener. */
    fun send(context: Context, current: StatusBarNotification, command: CommandPayload): DispatchStatus {
        if (!command.isFresh()) return DispatchStatus.EXPIRED
        if (command.type == CommandPayload.TYPE_REPLY &&
            (command.text.isBlank() || command.text.length > 1000)) return DispatchStatus.INVALID_TEXT
        if (current.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return DispatchStatus.UNSUPPORTED
        val snapshot = snapshot(current)
        if (command.generation.isBlank() || command.generation != snapshot.generation ||
            command.sourceUserId != snapshot.userId || command.sourcePostTime != current.postTime ||
            command.packageName != current.packageName || command.notificationKey != current.key)
            return DispatchStatus.STALE
        val descriptor = when (command.type) {
            CommandPayload.TYPE_REPLY -> snapshot.reply
            CommandPayload.TYPE_MARK_READ -> snapshot.read
            else -> null
        } ?: return DispatchStatus.UNSUPPORTED
        if (command.actionId != NotificationReference.actionId(snapshot.generation, command.type, descriptor))
            return DispatchStatus.STALE
        val action = snapshot.actions.first { it.first == descriptor }.second
        val pending = action.actionIntent ?: return DispatchStatus.UNSUPPORTED
        if (Build.VERSION.SDK_INT >= 31 && action.isAuthenticationRequired &&
            context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            return DispatchStatus.NEEDS_UNLOCK
        val fillIn = Intent()
        if (command.type == CommandPayload.TYPE_REPLY) {
            val inputs = action.remoteInputs ?: return DispatchStatus.UNSUPPORTED
            val results = Bundle().apply { putCharSequence(descriptor.inputKeys.single(), command.text) }
            RemoteInput.addResultsToIntent(inputs, fillIn, results)
            RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        return try {
            pending.send(context, 0, fillIn)
            DispatchStatus.DISPATCHED
        } catch (_: PendingIntent.CanceledException) {
            DispatchStatus.CANCELLED
        } catch (_: SecurityException) {
            DispatchStatus.DENIED
        } catch (_: Exception) {
            DispatchStatus.UNKNOWN
        }
    }
}
