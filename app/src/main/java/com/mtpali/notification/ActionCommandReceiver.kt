package com.mtpali.notification

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException

class ActionCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || Prefs.mode(context) != Prefs.MODE_RECEIVER || !Prefs.receiverEnabled(context)) return
        if (!CryptoBox.isValidPairCode(Prefs.pairCode(context))) return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val key = intent.getStringExtra(EXTRA_NOTIFICATION_KEY).orEmpty()
        val generation = intent.getStringExtra(EXTRA_GENERATION).orEmpty()
        val tag = intent.getStringExtra(EXTRA_LOCAL_TAG).orEmpty()
        if (key.isBlank() || packageName.isBlank() || generation.isBlank() || tag.isBlank()) return
        val type = when (intent.action) {
            ACTION_REPLY -> CommandPayload.TYPE_REPLY
            ACTION_MARK_READ -> CommandPayload.TYPE_MARK_READ
            ACTION_DISMISS -> CommandPayload.TYPE_DISMISS
            else -> return
        }
        val text = if (type == CommandPayload.TYPE_REPLY) RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY_TEXT)?.toString().orEmpty() else ""
        if (type == CommandPayload.TYPE_REPLY && (text.isBlank() || text.length > 1000)) {
            Diagnostics.error(context, "Reply must contain 1–1000 characters")
            return
        }
        val command = CommandPayload(type, packageName, key, text,
            sourcePostTime = intent.getLongExtra(EXTRA_SOURCE_POST_TIME, 0), generation = generation,
            actionId = intent.getStringExtra(EXTRA_ACTION_ID).orEmpty(),
            sourceUserId = intent.getIntExtra(EXTRA_SOURCE_USER, -1))
        val pair = Prefs.pairCode(context)
        val app = context.applicationContext
        val pending = goAsync()
        try {
            io.execute {
                try {
                    if (Prefs.mode(app) != Prefs.MODE_RECEIVER || !Prefs.receiverEnabled(app) ||
                        Prefs.pairCode(app) != pair) return@execute
                    if (!SyncRepository.isCurrent(app, tag, generation)) {
                        Diagnostics.error(app, "Original notification changed; wait for its update")
                        return@execute
                    }
                    if (runCatching { command.toTransportJson() }.isFailure) {
                        Diagnostics.error(app, "Reply is too large for this notification; shorten it")
                        MirrorNotifier.actionStatus(app, tag, "Reply is too large")
                        return@execute
                    }
                    ActionResults.enqueue(app, command, tag)
                } catch (_: Exception) {
                    Diagnostics.error(app, "Could not queue action")
                } finally { pending.finish() }
            }
        } catch (_: RejectedExecutionException) {
            Diagnostics.error(app, "Too many pending actions; try again shortly")
            pending.finish()
        }
    }

    companion object {
        private val io = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            ArrayBlockingQueue(32), { runnable -> Thread(runnable, "notification-action") })
            .apply { allowCoreThreadTimeOut(true) }
        const val ACTION_REPLY = "com.mtpali.notification.REPLY"
        const val ACTION_MARK_READ = "com.mtpali.notification.MARK_READ"
        const val ACTION_DISMISS = "com.mtpali.notification.DISMISS"
        const val KEY_REPLY_TEXT = "reply_text"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_NOTIFICATION_KEY = "notification_key"
        const val EXTRA_LOCAL_TAG = "local_tag"
        const val EXTRA_SOURCE_POST_TIME = "source_post_time"
        const val EXTRA_GENERATION = "generation"
        const val EXTRA_ACTION_ID = "action_id"
        const val EXTRA_SOURCE_USER = "source_user"
    }
}
