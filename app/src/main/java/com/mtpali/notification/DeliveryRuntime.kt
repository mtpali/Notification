package com.mtpali.notification

import android.content.Context
import android.content.Intent

object DeliveryRuntime {
    fun sync(context: Context): Boolean {
        val app = context.applicationContext
        FcmTransport.sync(app)
        RelayJobService.schedule(app)
        val compatibility = Prefs.receiverTransport(app) == Prefs.RECEIVER_STABLE &&
            CryptoBox.isValidPairCode(Prefs.pairCode(app)) &&
            (Prefs.mode(app) == Prefs.MODE_SENDER || Prefs.receiverEnabled(app))
        if (!compatibility) {
            app.stopService(Intent(app, ReceiverService::class.java))
            return true
        }
        return try {
            app.startForegroundService(Intent(app, ReceiverService::class.java))
            true
        } catch (_: RuntimeException) {
            Diagnostics.error(app, "Open the app to start Compatibility")
            Diagnostics.connection(app, "Stopped")
            false
        }
    }
}
