package com.mtpali.notification

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.firebase.messaging.FirebaseMessaging

object FcmTransport {
    private val handler = Handler(Looper.getMainLooper())
    private var inFlight = false
    private val callbacks = mutableListOf<(Boolean) -> Unit>()

    /** Serialize subscriptions and recompute the desired topic after every asynchronous step. */
    fun sync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        handler.post {
            if (onComplete != null) callbacks.add(onComplete)
            if (!inFlight) {
                inFlight = true
                reconcile(app)
            }
        }
    }

    private fun reconcile(context: Context) {
        val desired = desiredTopic(context)
        val current = Prefs.fcmSubscribedTopic(context)
        if (current == desired) { finish(true); return }
        val messaging = runCatching { FirebaseMessaging.getInstance() }.getOrElse { finish(false); return }
        if (current.isNotBlank()) {
            messaging.unsubscribeFromTopic(current).addOnCompleteListener { task ->
                if (!task.isSuccessful) { finish(false); return@addOnCompleteListener }
                Prefs.setFcmSubscribedTopic(context, "")
                reconcile(context)
            }
        } else {
            messaging.subscribeToTopic(desired).addOnCompleteListener { task ->
                if (!task.isSuccessful) { finish(false); return@addOnCompleteListener }
                Prefs.setFcmSubscribedTopic(context, desired)
                if (desired == desiredTopic(context) && Prefs.mode(context) == Prefs.MODE_RECEIVER) RelayClient.requestSync(context)
                reconcile(context)
            }
        }
    }

    private fun finish(ok: Boolean) {
        inFlight = false
        val pending = callbacks.toList()
        callbacks.clear()
        pending.forEach { it(ok) }
    }

    fun refreshToken(context: Context, onComplete: ((String) -> Unit)? = null) {
        val app = context.applicationContext
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = if (task.isSuccessful) task.result.orEmpty() else ""
                if (token.isNotBlank()) Prefs.setFcmToken(app, token)
                onComplete?.invoke(token)
            }
        } catch (_: Exception) { onComplete?.invoke("") }
    }

    private fun desiredTopic(context: Context): String {
        val pairCode = Prefs.pairCode(context)
        if (!CryptoBox.isValidPairCode(pairCode) || Prefs.receiverTransport(context) == Prefs.RECEIVER_STABLE) return ""
        return when (Prefs.mode(context)) {
            Prefs.MODE_SENDER -> CryptoBox.commandTopic(pairCode)
            Prefs.MODE_RECEIVER -> if (Prefs.receiverEnabled(context)) CryptoBox.topic(pairCode) else ""
            else -> ""
        }
    }
}
