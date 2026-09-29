package com.mtpali.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Prefs.mode(context) != Prefs.MODE_RECEIVER) return
        if (Prefs.pairCode(context).isBlank()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(Intent(context, ReceiverService::class.java))
            } else {
                context.startService(Intent(context, ReceiverService::class.java))
            }
        } catch (_: Exception) {
        }
    }
}
