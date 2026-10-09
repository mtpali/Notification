package com.mtpali.notification

import android.content.Context
import org.json.JSONObject

object MirrorDelivery {
    private fun prefs(context: Context) = context.getSharedPreferences("mirror_fragments", Context.MODE_PRIVATE)
    private fun current(context: Context, pairCode: String) = pairCode == Prefs.pairCode(context) &&
        Prefs.mode(context) == Prefs.MODE_RECEIVER && Prefs.receiverEnabled(context)

    fun receive(context: Context, pairCode: String, encrypted: String, relayId: String): Boolean {
        if (!current(context, pairCode)) return false
        require(encrypted.length <= PayloadBudget.MAX_ENCRYPTED_BYTES)
        val raw = CryptoBox.decrypt(pairCode, encrypted)
        if (JSONObject(raw).optInt("fragment") != 1)
            return SyncRepository.receive(context, MirrorPayload.fromJson(raw), relayId)
        val result = synchronized(this) {
            if (!current(context, pairCode)) return false
            val store = prefs(context)
            val assembly = MirrorAssembly(pairCode, store.getString("parts", "").orEmpty())
            val part = assembly.accept(encrypted)
            if (!part.accepted) {
                Diagnostics.error(context, "Notification parts incomplete; use Sync now")
                return false
            }
            // Commit before advancing the compatibility cursor, including the final part.
            if (!store.edit().putString("parts", assembly.toJson()).commit()) return false
            part
        }
        val completed = result.raw ?: return true
        if (!current(context, pairCode)) return false
        val payload = MirrorPayload.fromJson(completed)
        // Never hold the fragment monitor while taking sync/action-result monitors.
        if (!SyncRepository.receive(context, payload, payload.eventId)) return false
        return synchronized(this) {
            if (!current(context, pairCode)) return true
            val store = prefs(context)
            val latest = MirrorAssembly(pairCode, store.getString("parts", "").orEmpty())
            latest.finished(result.group)
            store.edit().putString("parts", latest.toJson()).commit()
        }
    }

    @Synchronized
    fun clear(context: Context) { prefs(context).edit().clear().commit() }
}
