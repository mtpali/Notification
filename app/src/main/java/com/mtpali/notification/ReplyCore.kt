package com.mtpali.notification

import android.app.KeyguardManager
import android.app.ActivityOptions
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
        // Wearable copies of the same PendingIntent/metadata are redundant. Distinct intents,
        // even with equal labels, must remain separate. Native order always comes first.
        val all = ActionPolicy.complete(actions) { original, action -> original.actionIntent == action.actionIntent }
        val mirroredActions = all.map { (descriptor, _) -> ActionPolicy.mirror(generation, descriptor) }
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
                    Build.VERSION.SDK_INT >= 31 && action.isAuthenticationRequired,
                    action.title?.toString().orEmpty(), inputs.map { input -> InputDescriptor(input.resultKey,
                        input.label?.toString().orEmpty(), input.allowFreeFormInput,
                        input.choices.orEmpty().map { it.toString() }, input.allowedDataTypes.sorted(),
                        if (Build.VERSION.SDK_INT >= 29) input.editChoicesBeforeSending else 0) },
                    action.allowGeneratedReplies, Build.VERSION.SDK_INT >= 29 && action.isContextual) to action)
            }
        }
        add("native", notification.actions.orEmpty().toList())
        add("wearable", runCatching { Notification.WearableExtender(notification).actions.toList() }.getOrDefault(emptyList()))
        // UserHandle.hashCode is public and identifies the handle; getIdentifier is hidden on SDK 34.
        val userId = sbn.user.hashCode()
        val generation = NotificationReference.hash(sbn.packageName, sbn.key, sbn.postTime.toString(),
            userId.toString(), title, text, notification.`when`.toString(), notification.shortcutId.orEmpty(),
            notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty(),
            actions.joinToString("\u0000") { (descriptor, action) ->
                // Bind a replacement PendingIntent even if its label/content stayed identical.
                NotificationReference.hash(descriptor.signature(), action.actionIntent?.hashCode()?.toString().orEmpty()) })
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
            CommandPayload.TYPE_ACTION -> snapshot.all.firstOrNull { (candidate, _) ->
                command.actionId == NotificationReference.actionId(snapshot.generation, command.type, candidate) }?.first
            CommandPayload.TYPE_REPLY -> snapshot.reply
            CommandPayload.TYPE_MARK_READ -> snapshot.read
            else -> null
        } ?: return DispatchStatus.UNSUPPORTED
        if (command.actionId != NotificationReference.actionId(snapshot.generation, command.type, descriptor))
            return DispatchStatus.STALE
        val action = snapshot.actions.first { it.first == descriptor }.second
        val pending = action.actionIntent ?: return DispatchStatus.UNSUPPORTED
        if (command.type == CommandPayload.TYPE_ACTION && ActionPolicy.unsupportedReason(descriptor).isNotBlank())
            return DispatchStatus.UNSUPPORTED
        if (Build.VERSION.SDK_INT >= 31 && (action.isAuthenticationRequired || pending.isActivity) &&
            context.getSystemService(KeyguardManager::class.java).isDeviceLocked)
            return DispatchStatus.NEEDS_UNLOCK
        val fillIn = Intent()
        if (command.type == CommandPayload.TYPE_ACTION || command.type == CommandPayload.TYPE_REPLY) {
            val values = if (command.type == CommandPayload.TYPE_REPLY)
                mapOf(descriptor.inputKeys.single() to command.text) else command.inputResults
            if (!ActionPolicy.validResults(descriptor.inputs, values)) return DispatchStatus.INVALID_TEXT
            if (values.isNotEmpty()) {
                val inputs = action.remoteInputs ?: return DispatchStatus.UNSUPPORTED
                val results = Bundle().apply { values.forEach { (key, value) -> putCharSequence(key, value) } }
                RemoteInput.addResultsToIntent(inputs, fillIn, results)
                RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
            }
        }
        return try {
            val options = if (Build.VERSION.SDK_INT >= 34) ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                .toBundle() else null
            pending.send(context, 0, fillIn, null, null, null, options)
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
