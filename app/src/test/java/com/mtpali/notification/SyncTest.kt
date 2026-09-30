package com.mtpali.notification

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SyncTest {
    private fun mirror(key: String, time: Long, event: String = MirrorPayload.EVENT_UPSERT, id: String = "$key-$time") =
        MirrorPayload("com.example", "Example", "Title", "Text", 10, key,
            event = event, eventTime = time, eventId = id)

    @Test fun removedNotificationDoesNotReappearFromDelayedMessage() {
        val ledger = SyncLedger()
        assertTrue(ledger.accept(mirror("a", 10), "").show)
        assertEquals(listOf("mirror:com.example:a"), ledger.accept(mirror("a", 30, MirrorPayload.EVENT_REMOVE), "").cancel)
        assertFalse(ledger.accept(mirror("a", 20), "").show)
        assertTrue(ledger.accept(mirror("a", 40), "").show)
    }

    @Test fun retryIsDeduplicatedAfterOtherMessagesAndRestart() {
        val ledger = SyncLedger()
        ledger.accept(mirror("a", 10), "")
        ledger.accept(mirror("b", 20), "")
        val restored = SyncLedger(ledger.toJson())
        assertFalse(restored.accept(mirror("a", 10), "").show)
    }

    @Test fun snapshotCanArriveOutOfOrderWithoutDeletingLiveNotifications() {
        val ledger = SyncLedger()
        ledger.accept(mirror("a", 1), "")
        ledger.accept(mirror("b", 2), "")
        val packet = mirror("", 10).copy(snapshotId = "s", snapshotTime = 10, snapshotCount = 1)
        assertTrue(ledger.accept(packet.copy(event = MirrorPayload.EVENT_SNAPSHOT_END, eventTime = 20, eventId = "end"), "").cancel.isEmpty())
        assertTrue(ledger.accept(mirror("a", 11).copy(snapshotId = "s", snapshotTime = 10, snapshotCount = 1), "").cancel.isEmpty())
        val result = ledger.accept(packet.copy(event = MirrorPayload.EVENT_SNAPSHOT_START, eventId = "start"), "")
        assertEquals(listOf("mirror:com.example:b"), result.cancel)
        assertFalse(ledger.accept(mirror("b", 3), "").show)
    }

    @Test fun incompleteSnapshotNeverClearsAnUnseenNotification() {
        val ledger = SyncLedger()
        ledger.accept(mirror("old", 1), "")
        val packet = mirror("", 10).copy(snapshotId = "s", snapshotTime = 10, snapshotCount = 2)
        ledger.accept(packet.copy(event = MirrorPayload.EVENT_SNAPSHOT_START, eventId = "start"), "")
        ledger.accept(mirror("a", 11).copy(snapshotId = "s", snapshotTime = 10, snapshotCount = 2), "")
        assertTrue(ledger.accept(packet.copy(event = MirrorPayload.EVENT_SNAPSHOT_END, eventTime = 20, eventId = "end"), "").cancel.isEmpty())
    }

    @Test fun completedSnapshotPreservesUpdatesNewerThanSnapshotStart() {
        val ledger = SyncLedger()
        ledger.accept(mirror("old", 1), "")
        ledger.accept(mirror("new", 30), "")
        val start = mirror("", 10).copy(event = MirrorPayload.EVENT_SNAPSHOT_START, snapshotId = "s", snapshotTime = 10, snapshotCount = 0)
        ledger.accept(start, "")
        val restored = SyncLedger(ledger.toJson())
        assertEquals(listOf("mirror:com.example:old"), restored.accept(start.copy(event = MirrorPayload.EVENT_SNAPSHOT_END,
            eventTime = 40, eventId = "end"), "").cancel)
    }

    @Test fun persianAndEmojiTextFitsTopicBudgetWithoutBrokenSurrogates() {
        val payload = mirror("a", 10).copy(text = "سلام 🌍 ".repeat(1000))
        val json = payload.toTransportJson()
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= PayloadBudget.MAX_PLAINTEXT_BYTES)
        val text = JSONObject(json).getString("text")
        assertTrue(text.isNotEmpty())
        assertFalse(text.last().isHighSurrogate())
        assertTrue(CryptoBox.encrypt("123456", json).length <= PayloadBudget.MAX_ENCRYPTED_BYTES)
    }

    @Test fun replyFitsBudgetAndRemainsAnAction() {
        val command = CommandPayload(CommandPayload.TYPE_REPLY, "com.example", "key", "سلام".repeat(1000))
        val json = command.toTransportJson()
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= PayloadBudget.MAX_PLAINTEXT_BYTES)
        assertEquals(CommandPayload.TYPE_REPLY, CommandPayload.fromJson(json).type)
        assertTrue(CommandPayload.fromJson(json).text.isNotEmpty())
    }

    @Test fun commandRejectsExpiredOrFutureMessages() {
        val now = 1_000_000L
        assertTrue(CommandPayload("sync", "", "", createdAt = now).isFresh(now))
        assertFalse(CommandPayload("sync", "", "", createdAt = now - CommandPayload.MAX_AGE_MS - 1).isFresh(now))
        assertFalse(CommandPayload("sync", "", "", createdAt = now + 60_001).isFresh(now))
    }

    @Test fun generatedPairKeyIsStrongAndLegacyCodeRemainsSupported() {
        val key = CryptoBox.generatePairCode()
        assertEquals(32, key.length)
        assertTrue(CryptoBox.isValidPairCode(key))
        assertTrue(CryptoBox.isValidPairCode("123456"))
        assertFalse(CryptoBox.isValidPairCode("12345"))
        assertNotEquals(key, CryptoBox.generatePairCode())
    }

    @Test fun encryptionRoundTripsAndRejectsWrongKey() {
        val raw = "پیام خصوصی 🌍"
        val encrypted = CryptoBox.encrypt("123456", raw)
        assertEquals(raw, CryptoBox.decrypt("123456", encrypted))
        assertNotEquals(encrypted, CryptoBox.encrypt("123456", raw))
        assertTrue(runCatching { CryptoBox.decrypt("654321", encrypted) }.isFailure)
    }
}
