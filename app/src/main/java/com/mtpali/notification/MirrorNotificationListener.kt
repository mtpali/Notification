package com.mtpali.notification

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MirrorNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Prefs.setListenerStatus(this, "CONNECTED at ${timeNow()}")
    }

    override fun onListenerDisconnected() {
        Prefs.setListenerStatus(this, "disconnected at ${timeNow()}")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (Prefs.mode(this) != Prefs.MODE_SENDER) return
        if (!CryptoBox.isValidPairCode(Prefs.pairCode(this))) return
        if (sbn.packageName == packageName) return

        val forwardAll = Prefs.forwardAllApps(this)
        if (!forwardAll && sbn.packageName !in Prefs.selectedApps(this)) return

        val notification = sbn.notification ?: return
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val text = if (bigText.isNotBlank()) bigText else normalText

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        Prefs.setLastCapture(this, "$appName: ${title.take(50)} at ${timeNow()}")

        val payload = MirrorPayload(
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            postTime = sbn.postTime,
            notificationKey = sbn.key
        )

        RelayClient.publish(applicationContext, payload)
    }

    private fun timeNow(): String =
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
}
