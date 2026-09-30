package com.mtpali.notification

import java.security.MessageDigest
import java.nio.ByteBuffer

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
    fun signature(): String = NotificationReference.hash(origin, index.toString(), semantic.toString(),
        inputKeys.joinToString("\u0000"), freeFormKeys.joinToString("\u0000"), hasIntent.toString(),
        immutable.toString(), authenticationRequired.toString())
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
        preferred(actions.filter { it.hasIntent && it.semantic == MARK_READ }).singleOrNull()

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
    DISPATCHED, STALE, EXPIRED, UNSUPPORTED, NEEDS_UNLOCK, CANCELLED, INVALID_TEXT, DENIED, UNKNOWN;

    companion object {
        fun parse(value: String): DispatchStatus? = entries.firstOrNull { it.name == value }
    }
}
