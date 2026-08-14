package com.mtpali.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.mode(context) != Prefs.MODE_RECEIVER) return
        if (Prefs.pairCode(context).isBlank()) return

        context.startForegroundService(Intent(context, ReceiverService::class.java))
    }
}
