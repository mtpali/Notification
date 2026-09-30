package com.mtpali.notification

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FcmMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        Prefs.setFcmToken(this, token)
        FcmTransport.sync(this)
    }

    override fun onDeletedMessages() {
        if (Prefs.mode(this) == Prefs.MODE_RECEIVER) RelayClient.requestSync(this)
        else MirrorNotificationListener.requestSnapshot(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val pairCode = Prefs.pairCode(this)
        if (!CryptoBox.isValidPairCode(pairCode) || Prefs.receiverTransport(this) != Prefs.RECEIVER_PUSH) return
        val id = message.data["id"].orEmpty().ifBlank { message.messageId.orEmpty() }
        val encrypted = message.data["payload"].orEmpty()
        try {
            when {
                Prefs.mode(this) == Prefs.MODE_RECEIVER && Prefs.receiverEnabled(this) && message.data["kind"] == "mirror" -> {
                    SyncRepository.receive(this, MirrorPayload.fromJson(CryptoBox.decrypt(pairCode, encrypted)), id)
                }
                Prefs.mode(this) == Prefs.MODE_SENDER && message.data["kind"] == "command" -> {
                    MirrorNotificationListener.dispatchCommand(this, CommandPayload.fromJson(CryptoBox.decrypt(pairCode, encrypted)), id, pairCode)
                }
                Prefs.mode(this) == Prefs.MODE_RECEIVER && Prefs.receiverEnabled(this) -> {
                    val test = message.notification ?: return
                    val now = System.currentTimeMillis()
                    SyncRepository.receive(this, MirrorPayload(packageName, "Firebase", test.title.orEmpty(),
                        test.body.orEmpty(), now, id.ifBlank { "fcm-test-$now" }), id)
                }
            }
        } catch (_: Exception) { Diagnostics.error(this, "Could not decrypt received message") }
    }
}
