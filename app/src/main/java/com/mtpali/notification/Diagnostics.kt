package com.mtpali.notification

import android.content.Context
import java.text.DateFormat
import java.util.Date

object Diagnostics {
    private fun prefs(context: Context) = context.getSharedPreferences("delivery_status", Context.MODE_PRIVATE)

    fun sent(context: Context) {
        prefs(context).edit().putLong("sent", System.currentTimeMillis()).remove("error").apply()
    }

    fun received(context: Context) {
        prefs(context).edit().putLong("received", System.currentTimeMillis()).apply()
    }

    fun error(context: Context, message: String) {
        prefs(context).edit().putString("error", message.take(160)).apply()
    }

    fun connection(context: Context, message: String) {
        prefs(context).edit().putString("connection", message).apply()
    }

    fun summary(context: Context): String {
        val p = prefs(context)
        fun time(key: String): String = p.getLong(key, 0).let {
            if (it == 0L) "—" else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
        }
        val connection = if (Prefs.receiverTransport(context) == Prefs.RECEIVER_STABLE)
            p.getString("connection", "Disconnected").orEmpty() else "FCM"
        val error = p.getString("error", "").orEmpty()
        return "$connection • Pending ${OutboxStore.count(context)}\nSent ${time("sent")} • Received ${time("received")}" +
            if (error.isBlank()) "" else "\n$error"
    }
}
