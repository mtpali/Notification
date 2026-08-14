package com.mtpali.notification

import android.content.Context

object Prefs {
    private const val FILE = "notification_settings"
    private const val KEY_MODE = "mode"
    private const val KEY_PAIR_CODE = "pair_code"
    private const val KEY_FORWARD_ALL = "forward_all"
    private const val KEY_SELECTED_APPS = "selected_apps"
    private const val KEY_LAST_MESSAGE_ID = "last_message_id"

    const val MODE_SENDER = "sender"
    const val MODE_RECEIVER = "receiver"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mode(context: Context): String =
        prefs(context).getString(KEY_MODE, MODE_SENDER) ?: MODE_SENDER

    fun setMode(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MODE, value).apply()
    }

    fun pairCode(context: Context): String =
        prefs(context).getString(KEY_PAIR_CODE, "") ?: ""

    fun setPairCode(context: Context, value: String) {
        val normalized = value.trim()
        val old = pairCode(context)
        prefs(context).edit().apply {
            putString(KEY_PAIR_CODE, normalized)
            if (old != normalized) remove(KEY_LAST_MESSAGE_ID)
        }.apply()
    }

    fun forwardAllApps(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FORWARD_ALL, true)

    fun setForwardAllApps(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_FORWARD_ALL, value).apply()
    }

    fun selectedApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SELECTED_APPS, emptySet())?.toSet() ?: emptySet()

    fun setSelectedApps(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SELECTED_APPS, packages).apply()
    }

    fun lastMessageId(context: Context): String =
        prefs(context).getString(KEY_LAST_MESSAGE_ID, "") ?: ""

    fun setLastMessageId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_LAST_MESSAGE_ID, id).apply()
    }
}
