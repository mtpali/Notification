package com.mtpali.notification

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class OutboxMessage(
    val id: String, val topic: String, val kind: String, val payload: String,
    val transport: String, val mode: String, val relayUrl: String,
    val expiresAt: Long, val coalesceKey: String = "",
    val attemptAfter: Long = 0, val attempts: Int = 0, val priority: String = "normal"
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("topic", topic).put("kind", kind)
        .put("payload", payload).put("transport", transport).put("mode", mode).put("url", relayUrl)
        .put("expires", expiresAt).put("key", coalesceKey).put("after", attemptAfter).put("attempts", attempts).put("priority", priority)

    companion object {
        fun fromJson(j: JSONObject) = OutboxMessage(j.getString("id"), j.getString("topic"),
            j.getString("kind"), j.getString("payload"), j.getString("transport"), j.getString("mode"),
            j.getString("url"), j.getLong("expires"), j.optString("key"), j.optLong("after"), j.optInt("attempts"), j.optString("priority", "normal"))
    }
}

/** Only ciphertext is persisted; the relay credential remains in the existing settings. */
object OutboxStore {
    private fun prefs(context: Context) = context.getSharedPreferences("encrypted_outbox", Context.MODE_PRIVATE)

    private fun read(context: Context): MutableList<OutboxMessage> {
        val array = runCatching { JSONArray(prefs(context).getString("messages", "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull {
            runCatching { OutboxMessage.fromJson(array.getJSONObject(it)) }.getOrNull()
        }.toMutableList()
    }

    private fun write(context: Context, messages: List<OutboxMessage>): Boolean =
        prefs(context).edit().putString("messages", JSONArray(messages.map { it.toJson() }).toString()).commit()

    @Synchronized
    fun add(context: Context, message: OutboxMessage): Boolean {
        val messages = read(context)
        val now = System.currentTimeMillis()
        messages.removeAll { it.expiresAt <= now ||
            (message.coalesceKey.isNotBlank() && it.coalesceKey == message.coalesceKey && it.topic == message.topic) }
        if (messages.size >= 256) return false
        messages.add(message)
        return write(context, messages)
    }

    @Synchronized
    fun first(context: Context): OutboxMessage? {
        val messages = read(context)
        val now = System.currentTimeMillis()
        val pairCode = Prefs.pairCode(context)
        val mirrorTopic = CryptoBox.topic(pairCode)
        val commandTopic = CryptoBox.commandTopic(pairCode)
        val removed = messages.removeAll { it.expiresAt <= now ||
            it.transport != Prefs.receiverTransport(context) || it.mode != Prefs.mode(context) ||
            it.relayUrl != Prefs.relayUrl(context).trimEnd('/') ||
            it.topic != (if (it.kind == "command") commandTopic else mirrorTopic) }
        if (removed) write(context, messages)
        return messages.firstOrNull()
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        val messages = read(context)
        messages.removeAll { it.id == id }
        write(context, messages)
    }

    @Synchronized
    fun retry(context: Context, message: OutboxMessage, delay: Long) {
        val messages = read(context)
        val index = messages.indexOfFirst { it.id == message.id }
        if (index >= 0) {
            messages[index] = message.copy(attemptAfter = System.currentTimeMillis() + delay, attempts = message.attempts + 1)
            write(context, messages)
        }
    }

    @Synchronized
    fun count(context: Context): Int = read(context).size

    @Synchronized
    fun clear(context: Context) { prefs(context).edit().clear().commit() }
}
