package com.mtpali.notification

import android.content.Context
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import com.google.firebase.FirebaseApp

object RelayClient {
    private val executor = Executors.newSingleThreadExecutor()
    private val flushing = AtomicBoolean(false)

    /** Completion means durably queued; actual send results are visible in Diagnostics. */
    fun publish(context: Context, payload: MirrorPayload, onComplete: ((Boolean) -> Unit)? = null) {
        val accepted = publishAccepted(context, payload)
        onComplete?.invoke(accepted)
    }

    fun publishAccepted(context: Context, payload: MirrorPayload): Boolean =
        enqueue(context, "mirror", runCatching { MirrorWire.encode(payload) }.getOrNull(), payload.eventId,
            if (payload.event in setOf(MirrorPayload.EVENT_UPSERT, MirrorPayload.EVENT_REMOVE) &&
                payload.snapshotId.isBlank() && payload.notificationKey.isNotBlank())
                "${payload.packageName}:${payload.notificationKey}" else "", 60 * 60_000L,
            if (payload.event == MirrorPayload.EVENT_UPSERT) "high" else "normal")

    fun publishCommand(context: Context, payload: CommandPayload, onComplete: ((Boolean) -> Unit)? = null) {
        val accepted = publishCommandAccepted(context, payload)
        onComplete?.invoke(accepted)
    }

    fun publishCommandAccepted(context: Context, payload: CommandPayload): Boolean {
        val raw = runCatching { payload.toTransportJson() }.getOrNull()
        return enqueue(context, "command", raw?.let { listOf(it) }, payload.id, "", CommandPayload.MAX_AGE_MS)
    }

    fun requestSync(context: Context) {
        if (Prefs.mode(context) == Prefs.MODE_RECEIVER && Prefs.receiverEnabled(context)) {
            publishCommand(context, CommandPayload(CommandPayload.TYPE_SYNC, "", ""))
        }
    }

    private fun enqueue(context: Context, kind: String, plaintext: List<String>?, id: String,
        coalesceKey: String, lifetime: Long, priority: String = "normal"): Boolean {
        val app = context.applicationContext
        val pairCode = Prefs.pairCode(app)
        if (plaintext == null || plaintext.isEmpty() || !CryptoBox.isValidPairCode(pairCode)) {
            Diagnostics.error(app, "Notification or action exceeds the transport limit")
            return false
        }
        val transport = Prefs.receiverTransport(app)
        if (transport == Prefs.RECEIVER_PUSH && !Prefs.relayConfigured(app)) {
            Diagnostics.error(app, "Set Relay URL and private key")
            return false
        }
        val wireId = RelayProtocol.messageId(id.ifBlank { UUID.randomUUID().toString() })
        val encrypted = runCatching { plaintext.map { CryptoBox.encrypt(pairCode, it) } }.getOrNull() ?: return false
        if (encrypted.any { it.length > PayloadBudget.MAX_ENCRYPTED_BYTES }) return false
        val messages = encrypted.mapIndexed { index, body -> OutboxMessage(
            if (encrypted.size == 1) wireId else "${NotificationReference.hash(wireId)}_$index",
            if (kind == "command") CryptoBox.commandTopic(pairCode) else CryptoBox.topic(pairCode),
            kind, body, transport, Prefs.mode(app), Prefs.relayUrl(app).trimEnd('/'),
            System.currentTimeMillis() + lifetime, coalesceKey, priority = priority) }
        if (!OutboxStore.addAll(app, messages)) {
            Diagnostics.error(app, "Send queue is full")
            return false
        }
        RelayJobService.schedule(app)
        flush(app)
        return true
    }

    fun flush(context: Context, shouldContinue: () -> Boolean = { true }, onComplete: (() -> Unit)? = null) {
        val app = context.applicationContext
        if (!flushing.compareAndSet(false, true)) {
            onComplete?.invoke()
            return
        }
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
                        if (message.kind == "command") {
                            if (result.sent) ActionResults.submitted(app, message.id)
                            else ActionResults.failed(app, message.id)
                        }
                        sent++
                    } else {
                        Diagnostics.error(app, result.error)
                        val backoff = (60_000L * (1L shl message.attempts.coerceIn(0, 6))).coerceAtMost(3_600_000)
                        OutboxStore.retry(app, message, maxOf(backoff, result.retryAfter) + Random.nextLong(5_000))
                        break
                    }
                }
            } finally {
                flushing.set(false)
                if (onComplete != null) onComplete.invoke() else RelayJobService.schedule(app)
                if (Prefs.mode(app) == Prefs.MODE_SENDER) MirrorNotificationListener.refresh(app)
            }
        }
    }

    private data class SendResult(val sent: Boolean, val retry: Boolean = false,
        val error: String = "", val retryAfter: Long = 0)

    private fun send(context: Context, message: OutboxMessage): SendResult {
        return try {
            val compatibility = message.transport == Prefs.RECEIVER_STABLE
            val address = if (compatibility) "https://ntfy.sh/${message.topic}" else "${message.relayUrl}/v1/send"
            val body = if (compatibility) message.payload else JSONObject()
                .put("topic", message.topic).put("kind", message.kind).put("payload", message.payload)
                .put("id", message.id).put("priority", message.priority)
                .put("projectId", runCatching { FirebaseApp.getInstance().options.projectId.orEmpty() }.getOrDefault(""))
                .toString()
            val response = RelayHttp.request(address, "POST", if (compatibility) "/[ntfy topic]" else "/v1/send",
                body = body, token = if (compatibility) null else Prefs.relayToken(context),
                contentType = if (compatibility) "text/plain; charset=utf-8" else "application/json; charset=utf-8",
                connectTimeoutMs = if (compatibility) 4_000 else RelayHttp.CONNECT_TIMEOUT_MS,
                readTimeoutMs = if (compatibility) 4_000 else RelayHttp.PUSH_TIMEOUT_MS,
                headers = if (compatibility) mapOf("Firebase" to "no") else emptyMap())
            val status = response.status
            val code = if (!compatibility && status !in 200..299) runCatching {
                JSONObject(response.body).optString("error")
            }.getOrNull().orEmpty() else ""
            when {
                status in 200..299 -> SendResult(true)
                status == 408 || status == 429 || status >= 500 -> SendResult(false, true,
                    if (compatibility) "Send pending (HTTP $status)" else RelayProtocol.error(status, code), response.retryAfterMs)
                else -> SendResult(false, false, if (compatibility) "Send rejected (HTTP $status)" else RelayProtocol.error(status, code))
            }
        } catch (error: RelayHttp.Failure) {
            SendResult(false, true, error.report())
        } catch (error: Exception) {
            SendResult(false, true, "Send could not complete [${error.javaClass.simpleName}]")
        }
    }
}
