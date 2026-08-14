package com.mtpali.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent

object MirrorNotifier {
    private const val CHANNEL_MIRRORED = "mirrored_notifications"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MIRRORED, "Mirrored", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun show(context: Context, payload: MirrorPayload, relayId: String) {
        ensureChannel(context)

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

        val builder = Notification.Builder(context, CHANNEL_MIRRORED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(body)
            .setSubText(sourceApp)
            .setStyle(Notification.BigTextStyle().setBigContentTitle(displayTitle).bigText(body))
            .setAutoCancel(true)
            .setWhen(payload.postTime.takeIf { it > 0 } ?: System.currentTimeMillis())

        if (payload.canReply && payload.notificationKey.isNotBlank()) {
            builder.addAction(replyAction(context, payload, localId))
        }
        if (payload.canMarkRead && payload.notificationKey.isNotBlank()) {
            builder.addAction(markReadAction(context, payload, localId))
        }

        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(localId, builder.build())
    }

    private fun replyAction(context: Context, payload: MirrorPayload, localId: Int): Notification.Action {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (payload.notificationKey + ":reply").hashCode(),
            commandIntent(context, ActionCommandReceiver.ACTION_REPLY, payload, localId),
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

    private fun markReadAction(context: Context, payload: MirrorPayload, localId: Int): Notification.Action {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (payload.notificationKey + ":read").hashCode(),
            commandIntent(context, ActionCommandReceiver.ACTION_MARK_READ, payload, localId),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(R.drawable.ic_notification, "Mark as read", pendingIntent)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()
    }

    private fun commandIntent(
        context: Context,
        action: String,
        payload: MirrorPayload,
        localId: Int
    ) = Intent(context, ActionCommandReceiver::class.java).apply {
        this.action = action
        putExtra(ActionCommandReceiver.EXTRA_PACKAGE, payload.packageName)
        putExtra(ActionCommandReceiver.EXTRA_NOTIFICATION_KEY, payload.notificationKey)
        putExtra(ActionCommandReceiver.EXTRA_LOCAL_ID, localId)
    }
}
