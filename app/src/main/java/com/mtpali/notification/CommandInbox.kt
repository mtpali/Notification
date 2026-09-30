package com.mtpali.notification

import android.content.Context
import org.json.JSONArray

object CommandInbox {
    private fun prefs(context: Context) = context.getSharedPreferences("command_inbox", Context.MODE_PRIVATE)

    @Synchronized
    fun add(context: Context, command: CommandPayload, relayId: String): Boolean {
        if (!command.isFresh()) return false
        val p = prefs(context)
        val id = command.id.ifBlank { relayId }
        val ids = read(p.getString("ids", "[]").orEmpty())
        if (id.isNotBlank() && (0 until ids.length()).any { ids.optString(it) == id }) return true
        val queue = read(p.getString("queue", "[]").orEmpty())
        if (queue.length() >= 32) {
            Diagnostics.error(context, "Too many pending actions")
            return false
        }
        queue.put(command.toJson())
        if (id.isNotBlank()) ids.put(id)
        while (ids.length() > 256) ids.remove(0)
        return p.edit().putString("queue", queue.toString()).putString("ids", ids.toString()).commit()
    }

    @Synchronized
    fun take(context: Context): CommandPayload? {
        val p = prefs(context)
        val queue = read(p.getString("queue", "[]").orEmpty())
        while (queue.length() > 0) {
            val raw = queue.remove(0)?.toString().orEmpty()
            // Persist consumption before dispatch; a restarted process must not send a reply twice.
            if (!p.edit().putString("queue", queue.toString()).commit()) return null
            val command = runCatching { CommandPayload.fromJson(raw) }.getOrNull()
            if (command != null && command.isFresh()) return command
        }
        return null
    }

    @Synchronized
    fun clear(context: Context) { prefs(context).edit().clear().commit() }

    private fun read(raw: String) = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
}
