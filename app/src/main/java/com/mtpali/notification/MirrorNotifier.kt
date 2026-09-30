package com.mtpali.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object MirrorNotifier {
    private const val CHANNEL_MIRRORED = "mirrored_notifications"
    private const val MIRROR_ID = 2001

    fun ensureChannel(context: Context) {
        manager(context).createNotificationChannel(
            NotificationChannel(CHANNEL_MIRRORED, "Mirrored", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun canNotify(context: Context): Boolean = manager(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun show(context: Context, payload: MirrorPayload, relayId: String): Boolean {
        if (!canNotify(context)) {
            Diagnostics.error(context, "Allow notifications on this phone")
            return false
        }
        return try {
            ensureChannel(context)
            val sourceApp = payload.appName.ifBlank {
                payload.packageName.substringAfterLast('.').ifBlank { "Notification" }
            }
            val title = payload.title.trim()
            val displayTitle = if (title.isBlank() || title.equals(sourceApp, true)) sourceApp else "$sourceApp • $title"
            val body = payload.text.ifBlank { sourceApp }
            val tag = SyncLedger.tag(payload, relayId)
            val builder = Notification.Builder(context, CHANNEL_MIRRORED)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(displayTitle).setContentText(body).setSubText(sourceApp)
                .setStyle(Notification.BigTextStyle().setBigContentTitle(displayTitle).bigText(body))
                .setOnlyAlertOnce(true).setAutoCancel(false)
                .setWhen(payload.postTime.takeIf { it > 0 } ?: System.currentTimeMillis())
            if (payload.notificationKey.isNotBlank()) {
                builder.setDeleteIntent(pending(context, ActionCommandReceiver.ACTION_DISMISS, payload, tag, false))
                if (payload.canReply) {
                    builder.addAction(Notification.Action.Builder(R.drawable.ic_notification, "Reply",
                        pending(context, ActionCommandReceiver.ACTION_REPLY, payload, tag, true))
                        .addRemoteInput(RemoteInput.Builder(ActionCommandReceiver.KEY_REPLY_TEXT).setLabel("Reply").build())
                        .setAllowGeneratedReplies(true).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build())
                }
                if (payload.canMarkRead) {
                    builder.addAction(Notification.Action.Builder(R.drawable.ic_notification, "Mark as read",
                        pending(context, ActionCommandReceiver.ACTION_MARK_READ, payload, tag, false))
                        .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build())
                }
            }
            manager(context).notify(tag, MIRROR_ID, builder.build())
            true
        } catch (_: RuntimeException) {
            Diagnostics.error(context, "Could not display notification")
            false
        }
    }

    fun cancel(context: Context, tag: String) { manager(context).cancel(tag, MIRROR_ID) }

    fun clear(context: Context) {
        manager(context).activeNotifications.filter { it.notification.channelId == CHANNEL_MIRRORED }
            .forEach { manager(context).cancel(it.tag, it.id) }
    }

    fun clearLegacy(context: Context) {
        manager(context).activeNotifications.filter { it.tag == null && it.notification.channelId == CHANNEL_MIRRORED }
            .forEach { manager(context).cancel(it.id) }
    }

    private fun manager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun pending(context: Context, action: String, payload: MirrorPayload, tag: String, mutable: Boolean): PendingIntent {
        val intent = Intent(context, ActionCommandReceiver::class.java).apply {
            this.action = action
            // PendingIntent identity includes the full source key, avoiding hash collisions.
            data = android.net.Uri.Builder().scheme("notification").authority("action")
                .appendPath(action).appendPath(tag).build()
            putExtra(ActionCommandReceiver.EXTRA_PACKAGE, payload.packageName)
            putExtra(ActionCommandReceiver.EXTRA_NOTIFICATION_KEY, payload.notificationKey)
            putExtra(ActionCommandReceiver.EXTRA_LOCAL_TAG, tag)
            putExtra(ActionCommandReceiver.EXTRA_SOURCE_POST_TIME, payload.postTime)
        }
        val mutability = if (mutable && Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE
            else if (mutable) 0 else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
    }
}
