package com.mtpali.notification

import android.content.Context

object SyncRepository {
    @Synchronized
    fun receive(context: Context, payload: MirrorPayload, relayId: String): Boolean {
        return try {
            val prefs = context.getSharedPreferences("mirror_sync", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("migrated", false)) {
                MirrorNotifier.clearLegacy(context)
                prefs.edit().putBoolean("migrated", true).commit()
            }
            val ledger = SyncLedger(prefs.getString("ledger", "").orEmpty())
            val result = ledger.accept(payload, relayId)
            if (result.show && !MirrorNotifier.show(context, payload, relayId)) return false
            result.cancel.forEach { MirrorNotifier.cancel(context, it) }
            if (!prefs.edit().putString("ledger", ledger.toJson()).commit()) return false
            Diagnostics.received(context)
            true
        } catch (_: Exception) {
            Diagnostics.error(context, "Could not read mirrored notification")
            false
        }
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences("mirror_sync", Context.MODE_PRIVATE).edit().clear().commit()
        MirrorNotifier.clear(context)
    }
}
