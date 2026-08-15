package com.mtpali.notification

import android.content.Context

object Prefs {
    private const val FILE = "notification_settings"
    private const val KEY_MODE = "mode"
    private const val KEY_PAIR_CODE = "pair_code"
    private const val KEY_FORWARD_ALL = "forward_all"
    private const val KEY_SELECTED_APPS = "selected_apps"
    private const val KEY_LAST_MESSAGE_ID = "last_message_id"
    private const val KEY_LAST_COMMAND_ID = "last_command_id"
    private const val KEY_PENDING_COMMAND = "pending_command"
    private const val KEY_RECEIVER_TRANSPORT = "receiver_transport"
    private const val KEY_RECEIVER_ENABLED = "receiver_enabled"
    private const val KEY_FCM_TOKEN = "fcm_token"
    private const val KEY_FCM_SUBSCRIBED_TOPIC = "fcm_subscribed_topic"
    private const val KEY_RELAY_URL = "relay_url"
    private const val KEY_RELAY_TOKEN = "relay_token"

    const val MODE_SENDER = "sender"
    const val MODE_RECEIVER = "receiver"

    const val RECEIVER_STABLE = "stable"
    const val RECEIVER_PUSH = "push"
    private const val LEGACY_HIDDEN = "hidden"

    const val DEFAULT_RELAY_URL =
        "https://notification.mhdvi45.workers.dev"

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
            if (old != normalized) {
                remove(KEY_LAST_MESSAGE_ID)
                remove(KEY_LAST_COMMAND_ID)
                remove(KEY_PENDING_COMMAND)
            }
        }.apply()
    }

    fun receiverTransport(context: Context): String {
        val stored = prefs(context).getString(KEY_RECEIVER_TRANSPORT, RECEIVER_PUSH)
        return when (stored) {
            RECEIVER_STABLE -> RECEIVER_STABLE
            LEGACY_HIDDEN -> RECEIVER_PUSH
            else -> RECEIVER_PUSH
        }
    }

    fun setReceiverTransport(context: Context, value: String) {
        val normalized = if (value == RECEIVER_STABLE) RECEIVER_STABLE else RECEIVER_PUSH
        prefs(context).edit().putString(KEY_RECEIVER_TRANSPORT, normalized).apply()
    }

    fun receiverEnabled(context: Context): Boolean {
        val values = prefs(context)
        return if (values.contains(KEY_RECEIVER_ENABLED)) {
            values.getBoolean(KEY_RECEIVER_ENABLED, false)
        } else {
            mode(context) == MODE_RECEIVER
        }
    }

    fun setReceiverEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_RECEIVER_ENABLED, value).apply()
    }

    fun relayUrl(context: Context): String =
        prefs(context).getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL

    fun setRelayUrl(context: Context, value: String) {
        val normalized = value.trim().ifBlank { DEFAULT_RELAY_URL }
        prefs(context).edit().putString(KEY_RELAY_URL, normalized).apply()
    }

    fun relayToken(context: Context): String =
        prefs(context).getString(KEY_RELAY_TOKEN, "") ?: ""

    fun setRelayToken(context: Context, value: String) {
        prefs(context).edit().putString(KEY_RELAY_TOKEN, value.trim()).apply()
    }

    fun relayConfigured(context: Context): Boolean =
        relayUrl(context).startsWith("https://") && relayToken(context).length >= 24

    fun fcmToken(context: Context): String =
        prefs(context).getString(KEY_FCM_TOKEN, "") ?: ""

    fun setFcmToken(context: Context, value: String) {
        prefs(context).edit().putString(KEY_FCM_TOKEN, value).apply()
    }

    fun fcmSubscribedTopic(context: Context): String =
        prefs(context).getString(KEY_FCM_SUBSCRIBED_TOPIC, "") ?: ""

    fun setFcmSubscribedTopic(context: Context, value: String) {
        prefs(context).edit().putString(KEY_FCM_SUBSCRIBED_TOPIC, value).apply()
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

    fun lastCommandId(context: Context): String =
        prefs(context).getString(KEY_LAST_COMMAND_ID, "") ?: ""

    fun setLastCommandId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_LAST_COMMAND_ID, id).apply()
    }

    fun pendingCommand(context: Context): String =
        prefs(context).getString(KEY_PENDING_COMMAND, "") ?: ""

    fun setPendingCommand(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PENDING_COMMAND, value).apply()
    }

    fun clearPendingCommand(context: Context) {
        prefs(context).edit().remove(KEY_PENDING_COMMAND).apply()
    }
}
