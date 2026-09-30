package com.mtpali.notification

import org.json.JSONArray
import org.json.JSONObject

/** Bounded state, independent of Android, so ordering and reconciliation are testable. */
class SyncLedger(raw: String = "") {
    data class Row(val version: Long, val visible: Boolean)
    data class Result(val show: Boolean = false, val cancel: List<String> = emptyList())
    private data class Snapshot(
        val id: String, val time: Long, val count: Int,
        var start: Boolean = false, var endTime: Long = 0,
        val seen: MutableSet<String> = linkedSetOf()
    )

    private val rows = linkedMapOf<String, Row>()
    private val messageIds = linkedSetOf<String>()
    private var snapshot: Snapshot? = null
    private var completedSnapshotTime = 0L

    init {
        if (raw.isNotBlank()) runCatching {
            val json = JSONObject(raw)
            val entries = json.optJSONObject("rows") ?: JSONObject()
            entries.keys().forEach { key ->
                val row = entries.getJSONObject(key)
                rows[key] = Row(row.getLong("time"), row.getBoolean("visible"))
            }
            val ids = json.optJSONArray("ids") ?: JSONArray()
            for (i in 0 until ids.length()) messageIds.add(ids.getString(i))
            completedSnapshotTime = json.optLong("completed")
            json.optJSONObject("snapshot")?.let { s ->
                val seen = linkedSetOf<String>()
                val keys = s.optJSONArray("seen") ?: JSONArray()
                for (i in 0 until keys.length()) seen.add(keys.getString(i))
                snapshot = Snapshot(s.getString("id"), s.getLong("time"), s.getInt("count"),
                    s.optBoolean("start"), s.optLong("end"), seen)
            }
        }
        trim()
    }

    fun accept(payload: MirrorPayload, relayId: String): Result {
        require(payload.event in setOf(MirrorPayload.EVENT_UPSERT, MirrorPayload.EVENT_REMOVE,
            MirrorPayload.EVENT_SNAPSHOT_START, MirrorPayload.EVENT_SNAPSHOT_END))
        require(payload.eventTime > 0)
        val key = tag(payload, relayId)
        val id = payload.eventId.ifBlank { relayId }
        if (id.isNotBlank() && id in messageIds) return Result()
        if (id.isNotBlank()) messageIds.add(id)

        if (payload.snapshotId.isNotBlank() && payload.snapshotTime > completedSnapshotTime &&
            payload.snapshotTime > 0 && payload.snapshotCount in 0..200
        ) {
            val old = snapshot
            if (old == null || payload.snapshotTime > old.time) {
                snapshot = Snapshot(payload.snapshotId, payload.snapshotTime, payload.snapshotCount)
            }
            snapshot?.takeIf { it.id == payload.snapshotId && it.count == payload.snapshotCount }?.let {
                when (payload.event) {
                    MirrorPayload.EVENT_SNAPSHOT_START -> it.start = true
                    MirrorPayload.EVENT_SNAPSHOT_END -> it.endTime = payload.eventTime
                    MirrorPayload.EVENT_UPSERT -> it.seen.add(key)
                }
            }
        }

        var show = false
        val cancel = mutableListOf<String>()
        if (payload.event == MirrorPayload.EVENT_UPSERT || payload.event == MirrorPayload.EVENT_REMOVE) {
            require(payload.notificationKey.isNotBlank() || relayId.isNotBlank())
            if (payload.eventTime > (rows[key]?.version ?: 0)) {
                show = payload.event == MirrorPayload.EVENT_UPSERT
                rows[key] = Row(payload.eventTime, show)
                if (!show) cancel.add(key)
            }
        }

        snapshot?.let { s ->
            if (s.start && s.endTime >= s.time && s.seen.size >= s.count) {
                rows.toMap().forEach { (tag, row) ->
                    if (row.visible && row.version <= s.time && tag !in s.seen) {
                        rows[tag] = Row(s.endTime, false)
                        cancel.add(tag)
                    }
                }
                completedSnapshotTime = s.time
                snapshot = null
            }
        }
        trim()
        return Result(show, cancel)
    }

    fun toJson(): String = JSONObject().apply {
        put("rows", JSONObject().apply {
            rows.forEach { (key, row) -> put(key, JSONObject().put("time", row.version).put("visible", row.visible)) }
        })
        put("ids", JSONArray(messageIds.toList()))
        put("completed", completedSnapshotTime)
        snapshot?.let { s ->
            put("snapshot", JSONObject().put("id", s.id).put("time", s.time).put("count", s.count)
                .put("start", s.start).put("end", s.endTime).put("seen", JSONArray(s.seen.toList())))
        }
    }.toString()

    private fun trim() {
        while (messageIds.size > 256) messageIds.remove(messageIds.first())
        while (rows.size > 512) {
            val oldest = rows.entries.filter { !it.value.visible }.minByOrNull { it.value.version }
                ?: rows.entries.minByOrNull { it.value.version } ?: break
            rows.remove(oldest.key)
        }
    }

    companion object {
        fun tag(payload: MirrorPayload, relayId: String = ""): String =
            "mirror:${payload.packageName}:${payload.notificationKey.ifBlank { relayId }}"
    }
}
