package com.mtpali.notification

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Split oversized action metadata, never trim/drop individual actions to meet FCM's budget. */
object MirrorWire {
    const val MAX_ASSEMBLED_BYTES = 64 * 1024
    const val CHUNK_BYTES = 720
    const val MAX_PARTS = 92
    fun encode(payload: MirrorPayload): List<String> {
        val single = runCatching { payload.toTransportJson() }.getOrNull()
        if (single != null) return listOf(single)
        val raw = PayloadBudget.fit(payload.toJson(), MAX_ASSEMBLED_BYTES)
        val bytes = raw.toByteArray(Charsets.UTF_8)
        val group = NotificationReference.hash(raw)
        val chunks = bytes.asList().chunked(CHUNK_BYTES)
        require(chunks.size in 2..MAX_PARTS)
        return chunks.mapIndexed { index, chunk ->
            JSONObject().put("fragment", 1).put("group", group).put("part", index).put("total", chunks.size)
                .put("data", Base64.getUrlEncoder().withoutPadding().encodeToString(chunk.toByteArray())).toString()
                .also { require(it.toByteArray(Charsets.UTF_8).size <= PayloadBudget.MAX_PLAINTEXT_BYTES) }
        }
    }
}

/** Restart-safe state contains only encrypted fragments, bounded in count, groups, bytes and age. */
class MirrorAssembly(private val pairCode: String, saved: String = "", now: Long = System.currentTimeMillis()) {
    data class Result(val accepted: Boolean, val raw: String? = null, val group: String = "")
    private data class Part(val encrypted: String, val time: Long, val group: String, val index: Int,
        val total: Int, val bytes: ByteArray)
    private val parts = mutableListOf<Part>()
    init {
        val list = runCatching { JSONArray(saved) }.getOrDefault(JSONArray())
        for (index in 0 until minOf(list.length(), MAX_BUFFER_PARTS)) runCatching {
            val row = list.getJSONObject(index)
            val encrypted = row.getString("encrypted")
            parse(encrypted, row.getLong("time"), now)?.let { part ->
                if (parts.none { it.group == part.group && it.index == part.index } &&
                    (part.group in parts.map { it.group } || parts.map { it.group }.distinct().size < MAX_GROUPS))
                    parts.add(part)
            }
        }
    }

    fun accept(encrypted: String, now: Long = System.currentTimeMillis()): Result {
        parts.removeAll { now - it.time > MAX_AGE_MS }
        val part = runCatching { parse(encrypted, now, now) }.getOrNull() ?: return Result(false)
        val group = parts.filter { it.group == part.group }
        if (group.any { it.total != part.total }) return Result(false)
        val old = group.firstOrNull { it.index == part.index }
        if (old != null && !old.bytes.contentEquals(part.bytes)) return Result(false)
        if (old == null) {
            if (parts.size >= MAX_BUFFER_PARTS ||
                (group.isEmpty() && parts.map { it.group }.distinct().size >= MAX_GROUPS)) return Result(false)
            parts.add(part)
        }
        val complete = parts.filter { it.group == part.group }.sortedBy { it.index }
        if (complete.size < part.total) return Result(true, group = part.group)
        val size = complete.sumOf { it.bytes.size }
        if (size > MirrorWire.MAX_ASSEMBLED_BYTES) return Result(false)
        val output = ByteArrayOutputStream(size)
        complete.forEach { output.write(it.bytes) }
        val raw = output.toByteArray().toString(Charsets.UTF_8)
        if (NotificationReference.hash(raw) != part.group) return Result(false)
        return Result(true, raw, part.group)
    }

    fun finished(group: String) { parts.removeAll { it.group == group } }
    fun toJson(): String = JSONArray(parts.map { JSONObject().put("encrypted", it.encrypted).put("time", it.time) }).toString()

    private fun parse(encrypted: String, time: Long, now: Long): Part? {
        if (encrypted.length > PayloadBudget.MAX_ENCRYPTED_BYTES || time > now + 60_000 || now - time > MAX_AGE_MS) return null
        val j = JSONObject(CryptoBox.decrypt(pairCode, encrypted))
        if (j.optInt("fragment") != 1) return null
        val group = j.getString("group")
        val total = j.getInt("total")
        val index = j.getInt("part")
        if (!group.matches(Regex("[a-f0-9]{32}")) || total !in 2..MirrorWire.MAX_PARTS || index !in 0 until total) return null
        val encoded = j.getString("data")
        if (encoded.length > 960) return null
        val bytes = Base64.getUrlDecoder().decode(encoded)
        if (bytes.isEmpty() || bytes.size > MirrorWire.CHUNK_BYTES) return null
        return Part(encrypted, time, group, index, total, bytes)
    }
    companion object {
        const val MAX_BUFFER_PARTS = 192
        const val MAX_GROUPS = 8
        const val MAX_AGE_MS = 10 * 60_000L
    }
}
