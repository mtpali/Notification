package com.mtpali.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.mode(context) != Prefs.MODE_RECEIVER) return
        if (!Prefs.receiverEnabled(context)) return
        if (!CryptoBox.isValidPairCode(Prefs.pairCode(context))) return

        if (Prefs.receiverTransport(context) == Prefs.RECEIVER_STABLE) {
            context.startForegroundService(Intent(context, ReceiverService::class.java))
        } else {
            FcmTransport.sync(context)
        }
    }
}
