package com.mtpali.notification

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.random.Random

object RelayClient {
    private val executor = Executors.newSingleThreadExecutor()

    /** Completion means durably queued; actual send results are visible in Diagnostics. */
    fun publish(context: Context, payload: MirrorPayload, onComplete: ((Boolean) -> Unit)? = null) {
        val accepted = publishAccepted(context, payload)
        onComplete?.invoke(accepted)
    }

    fun publishAccepted(context: Context, payload: MirrorPayload): Boolean =
        enqueue(context, "mirror", payload.toTransportJsonSafe(), payload.eventId,
            if (payload.snapshotId.isBlank() && payload.notificationKey.isNotBlank())
                "${payload.packageName}:${payload.notificationKey}" else "", 60 * 60_000L,
            if (payload.event == MirrorPayload.EVENT_UPSERT) "high" else "normal")

    fun publishCommand(context: Context, payload: CommandPayload, onComplete: ((Boolean) -> Unit)? = null) {
        val raw = runCatching { payload.toTransportJson() }.getOrNull()
        val accepted = enqueue(context, "command", raw, payload.id, "", CommandPayload.MAX_AGE_MS)
        onComplete?.invoke(accepted)
    }

    fun requestSync(context: Context) {
        if (Prefs.mode(context) == Prefs.MODE_RECEIVER && Prefs.receiverEnabled(context)) {
            publishCommand(context, CommandPayload(CommandPayload.TYPE_SYNC, "", ""))
        }
    }

    private fun MirrorPayload.toTransportJsonSafe(): String? = runCatching { toTransportJson() }.getOrNull()

    private fun enqueue(context: Context, kind: String, plaintext: String?, id: String,
        coalesceKey: String, lifetime: Long, priority: String = "normal"): Boolean {
        val app = context.applicationContext
        val pairCode = Prefs.pairCode(app)
        if (plaintext == null || !CryptoBox.isValidPairCode(pairCode)) return false
        val transport = Prefs.receiverTransport(app)
        if (transport == Prefs.RECEIVER_PUSH && !Prefs.relayConfigured(app)) {
            Diagnostics.error(app, "Set Relay URL and private key")
            return false
        }
        val encrypted = runCatching { CryptoBox.encrypt(pairCode, plaintext) }.getOrNull() ?: return false
        if (encrypted.length > PayloadBudget.MAX_ENCRYPTED_BYTES) return false
        val message = OutboxMessage(id.ifBlank { UUID.randomUUID().toString() },
            if (kind == "command") CryptoBox.commandTopic(pairCode) else CryptoBox.topic(pairCode),
            kind, encrypted, transport, Prefs.mode(app), Prefs.relayUrl(app).trimEnd('/'),
            System.currentTimeMillis() + lifetime, coalesceKey, priority = priority)
        if (!OutboxStore.add(app, message)) {
            Diagnostics.error(app, "Send queue is full")
            return false
        }
        RelayJobService.schedule(app)
        flush(app)
        return true
    }

    fun flush(context: Context, shouldContinue: () -> Boolean = { true }, onComplete: (() -> Unit)? = null) {
        val app = context.applicationContext
        executor.execute {
            try {
                val deadline = System.currentTimeMillis() + 25_000
                var sent = 0
                while (shouldContinue() && sent < 16 && System.currentTimeMillis() < deadline) {
                    val message = OutboxStore.first(app) ?: break
                    if (message.attemptAfter > System.currentTimeMillis()) break
                    val result = send(app, message)
                    if (result.sent || !result.retry) {
                        OutboxStore.remove(app, message.id)
                        if (result.sent) Diagnostics.sent(app) else Diagnostics.error(app, result.error)
                        sent++
                    } else {
                        Diagnostics.error(app, result.error)
                        val backoff = (60_000L * (1L shl message.attempts.coerceIn(0, 6))).coerceAtMost(3_600_000)
                        OutboxStore.retry(app, message, maxOf(backoff, result.retryAfter) + Random.nextLong(5_000))
                        break
                    }
                }
            } finally {
                if (onComplete != null) onComplete.invoke() else RelayJobService.schedule(app)
            }
        }
    }

    private data class SendResult(val sent: Boolean, val retry: Boolean = false,
        val error: String = "", val retryAfter: Long = 0)

    private fun send(context: Context, message: OutboxMessage): SendResult {
        var connection: HttpURLConnection? = null
        return try {
            val compatibility = message.transport == Prefs.RECEIVER_STABLE
            val address = if (compatibility) "https://ntfy.sh/${message.topic}" else "${message.relayUrl}/v1/send"
            val body = if (compatibility) message.payload else JSONObject()
                .put("topic", message.topic).put("kind", message.kind).put("payload", message.payload)
                .put("id", message.id).put("priority", message.priority).toString()
            connection = URL(address).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = 4_000
                readTimeout = 4_000
                doOutput = true
                setRequestProperty("User-Agent", "Notification-Android/1.1")
                setRequestProperty("Content-Type", if (compatibility) "text/plain; charset=utf-8" else "application/json; charset=utf-8")
                if (compatibility) setRequestProperty("Firebase", "no")
                else setRequestProperty("Authorization", "Bearer ${Prefs.relayToken(context)}")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(0, 3600)?.times(1000) ?: 0
            when {
                status in 200..299 -> SendResult(true)
                status == 408 || status == 429 || status >= 500 -> SendResult(false, true, "Send pending (HTTP $status)", retryAfter)
                status == 401 || status == 403 -> SendResult(false, false, "Relay private key rejected")
                else -> SendResult(false, false, "Send rejected (HTTP $status)")
            }
        } catch (_: Exception) {
            SendResult(false, true, "Offline or relay unreachable")
        } finally {
            connection?.disconnect()
        }
    }
}
