package com.mtpali.notification

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FcmMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Prefs.setFcmToken(this, token)
        FcmTransport.sync(this)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val pairCode = Prefs.pairCode(this)
        if (!CryptoBox.isValidPairCode(pairCode)) return

        when (Prefs.mode(this)) {
            Prefs.MODE_RECEIVER -> handleReceiverMessage(pairCode, remoteMessage)
            Prefs.MODE_SENDER -> handleSenderMessage(pairCode, remoteMessage)
        }
    }

    private fun handleReceiverMessage(pairCode: String, remoteMessage: RemoteMessage) {
        if (Prefs.receiverTransport(this) != Prefs.RECEIVER_PUSH ||
            !Prefs.receiverEnabled(this)
        ) return

        val data = remoteMessage.data
        if (data["kind"] == KIND_MIRROR) {
            handleEncryptedMirror(pairCode, remoteMessage)
            return
        }

        // Firebase Console test messages are notification messages. Android displays
        // them automatically in background; while foregrounded, mirror them locally.
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

    private fun handleSenderMessage(pairCode: String, remoteMessage: RemoteMessage) {
        if (remoteMessage.data["kind"] != KIND_COMMAND) return

        val encrypted = remoteMessage.data["payload"].orEmpty()
        if (encrypted.isBlank()) return

        val id = remoteMessage.data["id"].orEmpty()
            .ifBlank { remoteMessage.messageId.orEmpty() }
        if (id.isNotBlank() && id == Prefs.lastCommandId(this)) return

        try {
            val command = CommandPayload.fromJson(CryptoBox.decrypt(pairCode, encrypted))
            val now = System.currentTimeMillis()
            if (command.createdAt <= 0L ||
                command.createdAt > now + 60_000L ||
                now - command.createdAt > COMMAND_MAX_AGE_MS
            ) {
                if (id.isNotBlank()) Prefs.setLastCommandId(this, id)
                return
            }

            if (id.isNotBlank()) Prefs.setLastCommandId(this, id)
            MirrorNotificationListener.dispatchCommand(applicationContext, command)
        } catch (_: Exception) {
        }
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
        private const val KIND_COMMAND = "command"
        private const val COMMAND_MAX_AGE_MS = 10 * 60_000L
    }
}
