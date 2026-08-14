package com.mtpali.notification

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FcmMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Prefs.setFcmToken(this, token)
        FcmTransport.sync(this)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        if (Prefs.mode(this) != Prefs.MODE_RECEIVER ||
            Prefs.receiverTransport(this) != Prefs.RECEIVER_PUSH ||
            !Prefs.receiverEnabled(this)
        ) return

        val pairCode = Prefs.pairCode(this)
        if (!CryptoBox.isValidPairCode(pairCode)) return

        val data = remoteMessage.data
        if (data["kind"] == KIND_MIRROR) {
            handleEncryptedMirror(pairCode, remoteMessage)
            return
        }

        // Firebase Console test messages are notification messages. Android displays
        // them automatically while the app is backgrounded; while foregrounded,
        // mirror them locally so the same test is visible in both states.
        val notification = remoteMessage.notification ?: return
        val id = remoteMessage.messageId.orEmpty().ifBlank {
            "fcm-test-${System.currentTimeMillis()}"
        }
        MirrorNotifier.show(
            applicationContext,
            MirrorPayload(
                packageName = packageName,
                appName = "Firebase",
                title = notification.title.orEmpty().ifBlank { "FCM test" },
                text = notification.body.orEmpty().ifBlank { "Push received" },
                postTime = System.currentTimeMillis(),
                notificationKey = id
            ),
            id
        )
    }

    private fun handleEncryptedMirror(pairCode: String, remoteMessage: RemoteMessage) {
        val encrypted = remoteMessage.data["payload"].orEmpty()
        if (encrypted.isBlank()) return

        val id = remoteMessage.data["id"].orEmpty()
            .ifBlank { remoteMessage.messageId.orEmpty() }
        if (id.isNotBlank() && id == Prefs.lastMessageId(this)) return

        try {
            val payload = MirrorPayload.fromJson(CryptoBox.decrypt(pairCode, encrypted))
            MirrorNotifier.show(applicationContext, payload, id)
            if (id.isNotBlank()) Prefs.setLastMessageId(this, id)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val KIND_MIRROR = "mirror"
    }
}
