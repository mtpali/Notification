package com.mtpali.notification

import java.security.MessageDigest
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject

/** Metadata only. PendingIntent objects never leave the source phone. */
data class ActionDescriptor(
    val index: Int,
    val origin: String = "native",
    val semantic: Int = 0,
    val inputKeys: List<String> = emptyList(),
    val freeFormKeys: List<String> = emptyList(),
    val hasIntent: Boolean = true,
    val immutable: Boolean = false,
    val authenticationRequired: Boolean = false
) {
    fun signature(): String = NotificationReference.hash(*(listOf(origin, index.toString(), semantic.toString(),
        inputKeys.size.toString()) + inputKeys + listOf(freeFormKeys.size.toString()) + freeFormKeys +
        listOf(hasIntent.toString(), immutable.toString(), authenticationRequired.toString())).toTypedArray())
}

object ActionPolicy {
    private const val REPLY = 1
    private const val MARK_READ = 2

    fun reply(actions: List<ActionDescriptor>): ActionDescriptor? {
        val eligible = preferred(actions.filter {
            it.hasIntent && !it.immutable && it.inputKeys.size == 1 &&
                it.freeFormKeys == it.inputKeys && it.inputKeys.single().isNotBlank() &&
                it.semantic in listOf(0, REPLY)
        })
        val semantic = eligible.filter { it.semantic == REPLY }
        return when {
            semantic.size == 1 -> semantic.single()
            semantic.isEmpty() && eligible.size == 1 -> eligible.single()
            else -> null
        }
    }

    fun markRead(actions: List<ActionDescriptor>): ActionDescriptor? =
        preferred(actions.filter { it.hasIntent && it.semantic == MARK_READ && it.inputKeys.isEmpty() }).singleOrNull()

    private fun preferred(actions: List<ActionDescriptor>): List<ActionDescriptor> =
        actions.filter { it.origin == "native" }.ifEmpty { actions }
}

object NotificationReference {
    fun hash(vararg fields: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        val hex = "0123456789abcdef"
        return buildString(32) {
            digest.digest().take(16).forEach { byte ->
                val value = byte.toInt() and 255
                append(hex[value ushr 4]); append(hex[value and 15])
            }
        }
    }

    fun actionId(generation: String, type: String, action: ActionDescriptor?): String =
        if (action == null) "" else hash(generation, type, action.signature())
}

enum class DispatchStatus {
    DISPATCHED, STALE, EXPIRED, UNSUPPORTED, NEEDS_UNLOCK, CANCELLED, INVALID_TEXT, DENIED, BUSY, UNKNOWN;

    companion object {
        fun parse(value: String): DispatchStatus? = entries.firstOrNull { it.name == value }
    }
}

/** Never evict an unexpired command ID to make room: that would allow a replay to execute twice. */
class CommandDeduplicator(raw: String = "", now: Long = System.currentTimeMillis(), private val capacity: Int = 256) {
    enum class Admission { NEW, DUPLICATE, FULL }
    private val ids = linkedMapOf<String, Long>()
    init {
        val list = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        for (i in 0 until list.length()) {
            val row = list.optJSONObject(i)
            val id = row?.optString("id") ?: list.optString(i)
            val expiry = row?.optLong("expires") ?: (now + CommandPayload.MAX_AGE_MS)
            if (id.isNotBlank() && expiry >= now) ids[id] = expiry
        }
    }

    fun admit(id: String, expiresAt: Long, now: Long): Admission {
        ids.entries.removeAll { it.value < now }
        if (id in ids) return Admission.DUPLICATE
        if (ids.size >= capacity) return Admission.FULL
        ids[id] = expiresAt
        return Admission.NEW
    }

    fun toJson(): String = JSONArray(ids.map { (id, expiry) ->
        JSONObject().put("id", id).put("expires", expiry)
    }).toString()
    fun contains(id: String): Boolean = id in ids
}
