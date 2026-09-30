package com.mtpali.notification

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ActionCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || Prefs.mode(context) != Prefs.MODE_RECEIVER || !Prefs.receiverEnabled(context)) return
        if (!CryptoBox.isValidPairCode(Prefs.pairCode(context))) return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val key = intent.getStringExtra(EXTRA_NOTIFICATION_KEY).orEmpty()
        if (key.isBlank()) return
        val type = when (intent.action) {
            ACTION_REPLY -> CommandPayload.TYPE_REPLY
            ACTION_MARK_READ -> CommandPayload.TYPE_MARK_READ
            ACTION_DISMISS -> CommandPayload.TYPE_DISMISS
            else -> return
        }
        val text = if (type == CommandPayload.TYPE_REPLY) RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY_TEXT)?.toString()?.trim().orEmpty().take(1000) else ""
        if (type == CommandPayload.TYPE_REPLY && text.isBlank()) return
        val command = CommandPayload(type, packageName, key, text,
            sourcePostTime = intent.getLongExtra(EXTRA_SOURCE_POST_TIME, 0))
        // Queue is encrypted and committed before returning; no unbounded goAsync/network work.
        RelayClient.publishCommand(context, command) { accepted ->
            if (accepted && type == CommandPayload.TYPE_MARK_READ) {
                intent.getStringExtra(EXTRA_LOCAL_TAG)?.let { MirrorNotifier.cancel(context, it) }
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "com.mtpali.notification.REPLY"
        const val ACTION_MARK_READ = "com.mtpali.notification.MARK_READ"
        const val ACTION_DISMISS = "com.mtpali.notification.DISMISS"
        const val KEY_REPLY_TEXT = "reply_text"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_NOTIFICATION_KEY = "notification_key"
        const val EXTRA_LOCAL_TAG = "local_tag"
        const val EXTRA_SOURCE_POST_TIME = "source_post_time"
    }
}
