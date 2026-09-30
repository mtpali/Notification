package com.mtpali.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!CryptoBox.isValidPairCode(Prefs.pairCode(context))) return

        DeliveryRuntime.sync(context)
        RelayClient.flush(context)
    }
}
