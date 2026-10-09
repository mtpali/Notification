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
    val authenticationRequired: Boolean = false,
    val title: String = "",
    val inputs: List<InputDescriptor> = emptyList(),
    val allowGeneratedReplies: Boolean = false,
    val contextual: Boolean = false
) {
    fun signature(): String = NotificationReference.hash(*(listOf(origin, index.toString(), semantic.toString(),
        inputKeys.size.toString()) + inputKeys + listOf(freeFormKeys.size.toString()) + freeFormKeys +
        listOf(hasIntent.toString(), immutable.toString(), authenticationRequired.toString(), title,
            allowGeneratedReplies.toString(), contextual.toString()) + inputs.map { it.signature() }).toTypedArray())
}

data class InputDescriptor(val key: String, val label: String = "", val freeForm: Boolean = true,
    val choices: List<String> = emptyList(), val dataTypes: List<String> = emptyList(), val editChoices: Int = 0) {
    fun signature(): String = NotificationReference.hash(key, label, freeForm.toString(),
        NotificationReference.hash(*choices.toTypedArray()), NotificationReference.hash(*dataTypes.toTypedArray()), editChoices.toString())
    fun toJson(): JSONObject = JSONObject().put("k", key).put("l", label).put("f", freeForm)
        .put("c", JSONArray(choices)).put("d", JSONArray(dataTypes)).put("e", editChoices)
    companion object {
        fun fromJson(j: JSONObject) = InputDescriptor(j.getString("k"), j.optString("l"), j.optBoolean("f", true),
            strings(j.optJSONArray("c")), strings(j.optJSONArray("d")), j.optInt("e"))
        private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    }
}

/** The original label/order and each input are retained; IDs bind to a single source action. */
data class MirroredAction(val id: String, val title: String, val semantic: Int = 0,
    val inputs: List<InputDescriptor> = emptyList(), val authenticationRequired: Boolean = false,
    val allowGeneratedReplies: Boolean = false, val unsupportedReason: String = "", val origin: String = "native") {
    val inlineInput: Boolean get() = inputs.size == 1 && unsupportedReason.isBlank() &&
        (inputs.single().freeForm || inputs.single().choices.isNotEmpty())
    fun toJson(): JSONObject = JSONObject().put("id", id).put("t", title).put("s", semantic)
        .put("i", JSONArray(inputs.map { it.toJson() })).put("a", authenticationRequired)
        .put("g", allowGeneratedReplies).put("u", unsupportedReason).put("o", origin)
    companion object {
        fun fromJson(j: JSONObject): MirroredAction {
            val inputs = j.optJSONArray("i") ?: JSONArray()
            return MirroredAction(j.getString("id"), j.getString("t"), j.optInt("s"),
                (0 until inputs.length()).map { InputDescriptor.fromJson(inputs.getJSONObject(it)) },
                j.optBoolean("a"), j.optBoolean("g"), j.optString("u"), j.optString("o", "native"))
        }
    }
}

object ActionPolicy {
    private const val REPLY = 1
    private const val MARK_READ = 2

    fun <T> complete(actions: List<Pair<ActionDescriptor, T>>, sameIntent: (T, T) -> Boolean): List<Pair<ActionDescriptor, T>> =
        actions.filter { (descriptor, action) -> descriptor.origin == "native" ||
            actions.none { (native, original) -> native.origin == "native" && sameIntent(original, action) &&
                native.copy(index = descriptor.index, origin = descriptor.origin) == descriptor } }

    fun mirror(generation: String, descriptor: ActionDescriptor): MirroredAction = MirroredAction(
        NotificationReference.actionId(generation, CommandPayload.TYPE_ACTION, descriptor),
        descriptor.title, descriptor.semantic, descriptor.inputs, descriptor.authenticationRequired,
        descriptor.allowGeneratedReplies, unsupportedReason(descriptor), descriptor.origin)

    fun unsupportedReason(action: ActionDescriptor): String = when {
        !action.hasIntent -> "Original action has no intent"
        action.inputs.map { it.key }.let { keys -> keys.any { it.isBlank() } || keys.distinct().size != keys.size } ->
            "Original action has invalid input keys"
        action.inputs.isNotEmpty() && action.immutable -> "Original input intent is immutable"
        action.inputs.any { !it.freeForm && it.choices.isEmpty() } -> "Attachment input is not supported"
        else -> ""
    }

    fun validResults(inputs: List<InputDescriptor>, results: Map<String, String>): Boolean =
        results.keys == inputs.map { it.key }.toSet() && inputs.all { input ->
            val value = results[input.key].orEmpty()
            value.isNotBlank() && value.length <= 1000 && (input.freeForm || value in input.choices)
        }

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
