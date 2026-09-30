package com.mtpali.notification

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ActionRecord(val id: String, val type: String, val tag: String, val generation: String,
    val state: String = "QUEUED", val createdAt: Long = System.currentTimeMillis())

/** No reply text is stored here. Late results cannot complete another action or change its target. */
class ActionLedger(raw: String = "") {
    private val records = linkedMapOf<String, ActionRecord>()
    init {
        val list = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        for (index in 0 until list.length()) runCatching {
            val r = list.getJSONObject(index)
            val row = ActionRecord(r.getString("id"), r.getString("type"), r.getString("tag"),
                r.getString("generation"), r.getString("state"), r.getLong("time"))
            records[row.id] = row
        }
        trim()
    }

    fun queued(command: CommandPayload, tag: String) {
        records[command.id] = ActionRecord(command.id, command.type, tag, command.generation, createdAt = command.createdAt)
        trim()
    }

    fun submitted(id: String) {
        records[id]?.takeIf { it.state == "QUEUED" }?.let { records[id] = it.copy(state = "SUBMITTED") }
    }

    fun complete(id: String, tag: String, generation: String, status: String): ActionRecord? {
        val old = records[id] ?: return null
        if (old.tag != tag || old.generation != generation || old.state !in listOf("QUEUED", "SUBMITTED")) return null
        val updated = old.copy(state = status)
        records[id] = updated
        return updated
    }

    fun failed(id: String): ActionRecord? {
        val row = records[id] ?: return null
        return complete(id, row.tag, row.generation, "FAILED")
    }

    fun latest(): ActionRecord? = records.values.lastOrNull()
    fun toJson(): String = JSONArray(records.values.map {
        JSONObject().put("id", it.id).put("type", it.type).put("tag", it.tag)
            .put("generation", it.generation).put("state", it.state).put("time", it.createdAt)
    }).toString()
    private fun trim() { while (records.size > 64) records.remove(records.keys.first()) }
}

object ActionResults {
    private fun prefs(context: Context) = context.getSharedPreferences("action_results", Context.MODE_PRIVATE)
    private fun read(context: Context) = ActionLedger(prefs(context).getString("ledger", "").orEmpty())
    private fun save(context: Context, ledger: ActionLedger): Boolean =
        prefs(context).edit().putString("ledger", ledger.toJson()).commit()

    @Synchronized
    fun enqueue(context: Context, command: CommandPayload, tag: String): Boolean {
        val ledger = read(context)
        ledger.queued(command, tag)
        if (!save(context, ledger)) return false
        val accepted = RelayClient.publishCommandAccepted(context, command)
        if (!accepted) { ledger.failed(command.id); save(context, ledger) }
        MirrorNotifier.actionStatus(context, tag, if (accepted) "Action queued" else "Action could not be queued")
        return accepted
    }

    @Synchronized
    fun submitted(context: Context, id: String) {
        val ledger = read(context)
        ledger.submitted(id)
        save(context, ledger)
    }

    @Synchronized
    fun failed(context: Context, id: String) {
        val ledger = read(context)
        val record = ledger.failed(id) ?: return
        save(context, ledger)
        if (SyncRepository.isCurrent(context, record.tag, record.generation))
            MirrorNotifier.actionStatus(context, record.tag, "Action could not be sent")
    }

    @Synchronized
    fun receive(context: Context, payload: MirrorPayload): Boolean {
        val status = DispatchStatus.parse(payload.actionStatus) ?: return false
        val ledger = read(context)
        val row = ledger.complete(payload.commandId, SyncLedger.tag(payload), payload.generation, status.name)
            ?: return true
        if (!save(context, ledger)) return false
        if (SyncRepository.isCurrent(context, row.tag, row.generation)) {
            if (row.type == CommandPayload.TYPE_MARK_READ && status == DispatchStatus.DISPATCHED)
                MirrorNotifier.cancel(context, row.tag)
            else MirrorNotifier.actionStatus(context, row.tag, label(status.name))
        }
        Diagnostics.received(context)
        return true
    }

    fun summary(context: Context): String {
        val row = read(context).latest() ?: return ""
        val action = when (row.type) { CommandPayload.TYPE_REPLY -> "Reply"; CommandPayload.TYPE_MARK_READ -> "Mark as read"; else -> "Dismiss" }
        val state = if (row.state in listOf("QUEUED", "SUBMITTED") &&
            System.currentTimeMillis() - row.createdAt > CommandPayload.MAX_AGE_MS + 5 * 60_000L)
            "Result unavailable; check Sender" else label(row.state)
        return "$action • $state"
    }

    fun label(state: String): String = when (state) {
        "QUEUED" -> "Queued"
        "SUBMITTED" -> "Relay accepted; awaiting Sender"
        "DISPATCHED" -> "Action dispatched on Sender"
        "STALE" -> "Original notification changed or disappeared"
        "EXPIRED" -> "Action expired"
        "NEEDS_UNLOCK" -> "Unlock Sender before trying again"
        "UNSUPPORTED" -> "Original action is unsupported or ambiguous"
        "CANCELLED" -> "Original action was cancelled"
        "INVALID_TEXT" -> "Reply text is invalid"
        "DENIED" -> "Original action was denied"
        "UNKNOWN" -> "Result unknown; check Sender before retrying"
        else -> "Action could not be sent"
    }

    @Synchronized
    fun clear(context: Context) { prefs(context).edit().clear().commit() }
}
