package com.mtpali.notification

import android.content.Context
import org.json.JSONArray

object CommandInbox {
    data class Claimed(val command: CommandPayload, val scope: String, val result: String)
    private fun prefs(context: Context) = context.getSharedPreferences("command_inbox", Context.MODE_PRIVATE)

    @Synchronized
    fun add(context: Context, command: CommandPayload, relayId: String): Boolean {
        if (!command.isFresh()) return false
        val pair = Prefs.pairCode(context)
        if (!CryptoBox.isValidPairCode(pair)) return false
        val p = prefs(context)
        val id = command.id.ifBlank { relayId }
        if (id.isBlank()) return false
        val ids = read(p.getString("ids", "[]").orEmpty())
        if (id.isNotBlank() && (0 until ids.length()).any { ids.optString(it) == id }) return true
        val queue = read(p.getString("queue", "[]").orEmpty())
        if (queue.length() >= 32) {
            Diagnostics.error(context, "Too many pending actions")
            return false
        }
        queue.put(CryptoBox.encrypt(pair, command.copy(id = id).toJson()))
        if (id.isNotBlank()) ids.put(id)
        while (ids.length() > 256) ids.remove(0)
        return p.edit().putString("queue", queue.toString()).putString("ids", ids.toString()).commit()
    }

    @Synchronized
    fun claim(context: Context): Claimed? {
        val p = prefs(context)
        if (p.getString("active", "").orEmpty().isNotBlank()) return null
        val queue = read(p.getString("queue", "[]").orEmpty())
        while (queue.length() > 0) {
            val raw = queue.remove(0)?.toString().orEmpty()
            val pair = Prefs.pairCode(context)
            val command = runCatching { CommandPayload.fromJson(CryptoBox.decrypt(pair, raw)) }
                .getOrElse { runCatching { CommandPayload.fromJson(raw) }.getOrNull() }
            val edit = p.edit().putString("queue", queue.toString())
            val scope = CryptoBox.commandTopic(pair)
            if (command != null) edit.putString("active", CryptoBox.encrypt(pair, command.toJson()))
                .putString("active_scope", scope).remove("active_result")
            // Claim before dispatch. A crash leaves a result that must be reported as UNKNOWN.
            if (!edit.commit()) return null
            if (command != null) return Claimed(command, scope, "")
            Diagnostics.error(context, "Pending action could not be recovered")
        }
        return null
    }

    @Synchronized
    fun inFlight(context: Context): Claimed? {
        val p = prefs(context)
        val raw = p.getString("active", "").orEmpty()
        if (raw.isBlank()) return null
        val scope = p.getString("active_scope", "").orEmpty()
        val pair = Prefs.pairCode(context)
        return runCatching {
            require(scope == CryptoBox.commandTopic(pair))
            Claimed(CommandPayload.fromJson(CryptoBox.decrypt(pair, raw)), scope,
                p.getString("active_result", "").orEmpty())
        }.getOrElse {
            finish(context)
            Diagnostics.error(context, "Interrupted action result unavailable")
            null
        }
    }

    @Synchronized
    fun result(context: Context, status: DispatchStatus): Boolean =
        prefs(context).edit().putString("active_result", status.name).commit()

    @Synchronized
    fun finish(context: Context): Boolean = prefs(context).edit().remove("active")
        .remove("active_scope").remove("active_result").commit()

    @Synchronized
    fun clear(context: Context) { prefs(context).edit().clear().commit() }

    private fun read(raw: String) = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
}
