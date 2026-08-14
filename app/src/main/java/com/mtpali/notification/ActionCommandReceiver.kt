package com.mtpali.notification

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ActionCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || Prefs.mode(context) != Prefs.MODE_RECEIVER) return
        if (!CryptoBox.isValidPairCode(Prefs.pairCode(context))) return

        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val notificationKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY).orEmpty()
        if (notificationKey.isBlank()) return

        val command = when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_REPLY_TEXT)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                    .take(MAX_REPLY_LENGTH)
                if (text.isBlank()) return

                CommandPayload(
                    type = CommandPayload.TYPE_REPLY,
                    packageName = packageName,
                    notificationKey = notificationKey,
                    text = text
                )
            }

            ACTION_MARK_READ -> {
                val localId = intent.getIntExtra(EXTRA_LOCAL_ID, Int.MIN_VALUE)
                if (localId != Int.MIN_VALUE) {
                    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .cancel(localId)
                }

                CommandPayload(
                    type = CommandPayload.TYPE_MARK_READ,
                    packageName = packageName,
                    notificationKey = notificationKey
                )
            }

            else -> return
        }

        val pending = goAsync()
        RelayClient.publishCommand(context.applicationContext, command) {
            pending.finish()
        }
    }

    companion object {
        private const val MAX_REPLY_LENGTH = 1000

        const val ACTION_REPLY = "com.mtpali.notification.REPLY"
        const val ACTION_MARK_READ = "com.mtpali.notification.MARK_READ"
        const val KEY_REPLY_TEXT = "reply_text"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_NOTIFICATION_KEY = "notification_key"
        const val EXTRA_LOCAL_ID = "local_id"
    }
}
