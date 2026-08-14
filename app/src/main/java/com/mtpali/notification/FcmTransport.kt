package com.mtpali.notification

import android.content.Context
import com.google.firebase.messaging.FirebaseMessaging

object FcmTransport {
    fun sync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val appContext = context.applicationContext
        val desiredTopic = desiredTopic(appContext)
        val currentTopic = Prefs.fcmSubscribedTopic(appContext)

        val messaging = try {
            FirebaseMessaging.getInstance()
        } catch (_: Exception) {
            onComplete?.invoke(false)
            return
        }

        refreshToken(appContext)

        if (currentTopic == desiredTopic) {
            onComplete?.invoke(true)
            return
        }

        fun subscribeDesired() {
            if (desiredTopic.isBlank()) {
                Prefs.setFcmSubscribedTopic(appContext, "")
                onComplete?.invoke(true)
                return
            }

            messaging.subscribeToTopic(desiredTopic)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Prefs.setFcmSubscribedTopic(appContext, desiredTopic)
                    }
                    onComplete?.invoke(task.isSuccessful)
                }
        }

        if (currentTopic.isBlank()) {
            subscribeDesired()
        } else {
            messaging.unsubscribeFromTopic(currentTopic)
                .addOnCompleteListener {
                    Prefs.setFcmSubscribedTopic(appContext, "")
                    subscribeDesired()
                }
        }
    }

    fun refreshToken(context: Context, onComplete: ((String) -> Unit)? = null) {
        val appContext = context.applicationContext
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                val token = if (task.isSuccessful) task.result.orEmpty() else ""
                if (token.isNotBlank()) Prefs.setFcmToken(appContext, token)
                onComplete?.invoke(token)
            }
        } catch (_: Exception) {
            onComplete?.invoke("")
        }
    }

    private fun desiredTopic(context: Context): String {
        if (Prefs.mode(context) != Prefs.MODE_RECEIVER) return ""
        if (Prefs.receiverTransport(context) != Prefs.RECEIVER_PUSH) return ""
        if (!Prefs.receiverEnabled(context)) return ""
        val pairCode = Prefs.pairCode(context)
        if (!CryptoBox.isValidPairCode(pairCode)) return ""
        return CryptoBox.topic(pairCode)
    }
}
