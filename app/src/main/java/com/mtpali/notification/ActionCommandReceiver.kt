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
            ACTION_FORWARD -> CommandPayload.TYPE_ACTION
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
            sourceUserId = intent.getIntExtra(EXTRA_SOURCE_USER, -1),
            inputResults = if (type == CommandPayload.TYPE_ACTION) intent.getStringArrayListExtra(EXTRA_INPUT_KEYS).orEmpty()
                .associateWith { RemoteInput.getResultsFromIntent(intent)?.getCharSequence(it)?.toString().orEmpty() } else emptyMap())
        val pending = goAsync()
        queue(context, command, tag) { pending.finish() }
    }

    companion object {
        private val io = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            ArrayBlockingQueue(32), { runnable -> Thread(runnable, "notification-action") })
            .apply { allowCoreThreadTimeOut(true) }
        fun queue(context: Context, command: CommandPayload, tag: String, onComplete: (Boolean) -> Unit = {}) {
            val app = context.applicationContext
            val pair = Prefs.pairCode(app)
            try {
                io.execute {
                    var accepted = false
                    try {
                        if (Prefs.mode(app) != Prefs.MODE_RECEIVER || !Prefs.receiverEnabled(app) ||
                            Prefs.pairCode(app) != pair) return@execute
                        if (!SyncRepository.isCurrent(app, tag, command.generation)) {
                            Diagnostics.error(app, "Original notification changed; wait for its update")
                            return@execute
                        }
                        var title = ""
                        if (command.type == CommandPayload.TYPE_ACTION) {
                            val original = MirrorNotifier.currentPayload(app, tag)?.actions?.firstOrNull { it.id == command.actionId }
                            if (original == null || original.unsupportedReason.isNotBlank()) {
                                Diagnostics.error(app, original?.unsupportedReason ?: "Original action changed")
                                return@execute
                            }
                            if (!ActionPolicy.validResults(original.inputs, command.inputResults)) {
                                Diagnostics.error(app, "Complete each input with valid text or a supplied choice")
                                return@execute
                            }
                            title = original.title
                        }
                        if (runCatching { command.toTransportJson() }.isFailure) {
                            Diagnostics.error(app, "Action input is too large; shorten it")
                            MirrorNotifier.actionStatus(app, tag, "Action input is too large")
                            return@execute
                        }
                        accepted = ActionResults.enqueue(app, command, tag, title)
                    } catch (_: Exception) {
                        Diagnostics.error(app, "Could not queue action")
                    } finally { onComplete(accepted) }
                }
            } catch (_: RejectedExecutionException) {
                Diagnostics.error(app, "Too many pending actions; try again shortly")
                onComplete(false)
            }
        }
        const val ACTION_FORWARD = "com.mtpali.notification.FORWARD_ACTION"
        const val EXTRA_INPUT_KEYS = "input_keys"
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
