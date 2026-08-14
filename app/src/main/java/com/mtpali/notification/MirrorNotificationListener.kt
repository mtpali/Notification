package com.mtpali.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MirrorNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (Prefs.mode(this) != Prefs.MODE_SENDER) return
        if (!CryptoBox.isValidPairCode(Prefs.pairCode(this))) return
        if (sbn.packageName == packageName) return

        if (!Prefs.forwardAllApps(this) && sbn.packageName !in Prefs.selectedApps(this)) return

        val extras = sbn.notification?.extras ?: return
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
                notificationKey = sbn.key
            )
        )
    }
}
