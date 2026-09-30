package com.mtpali.notification

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReliabilityTest {
    private fun reply(index: Int, semantic: Int = 0, key: String = "unusual_input") =
        ActionDescriptor(index, semantic = semantic, inputKeys = listOf(key), freeFormKeys = listOf(key))

    @Test fun semanticReplyWinsOverUnlabelledInputWithoutGuessingItsResultKey() {
        val semantic = reply(1, 1, "telegram_custom_key")
        assertEquals(semantic, ActionPolicy.reply(listOf(reply(0), semantic)))
    }

    @Test fun ambiguousRepliesAreUnsupported() {
        assertNull(ActionPolicy.reply(listOf(reply(0), reply(1))))
        assertNull(ActionPolicy.reply(listOf(reply(0, 1), reply(1, 1))))
    }

    @Test fun immutableMissingAndMultipleInputActionsAreUnsupported() {
        assertNull(ActionPolicy.reply(listOf(reply(0).copy(immutable = true))))
        assertNull(ActionPolicy.reply(listOf(reply(0).copy(hasIntent = false))))
        assertNull(ActionPolicy.reply(listOf(reply(0).copy(inputKeys = listOf("a", "b")))))
        assertNull(ActionPolicy.reply(listOf(reply(0).copy(freeFormKeys = emptyList()))))
    }

    @Test fun wearableFallbackIsUsedOnlyWhenNativeReplyIsUnavailable() {
        val wearable = reply(0, 1).copy(origin = "wearable")
        assertEquals(wearable, ActionPolicy.reply(listOf(wearable)))
        assertEquals(reply(0), ActionPolicy.reply(listOf(reply(0), wearable)))
    }

    @Test fun markReadRequiresUniqueSemanticMetadata() {
        assertNull(ActionPolicy.markRead(listOf(ActionDescriptor(0))))
        val read = ActionDescriptor(0, semantic = 2, immutable = true)
        assertEquals(read, ActionPolicy.markRead(listOf(read)))
        assertNull(ActionPolicy.markRead(listOf(read, read.copy(index = 1))))
    }

    @Test fun referenceSeparatesFieldsAndChangesWhenActionMetadataChanges() {
        assertNotEquals(NotificationReference.hash("a", "bc"), NotificationReference.hash("ab", "c"))
        assertEquals(32, NotificationReference.hash("پیام 🌍").length)
        assertNotEquals(NotificationReference.actionId("g", "reply", reply(0)),
            NotificationReference.actionId("g", "reply", reply(0, key = "new_key")))
        assertNotEquals(reply(0).signature(), reply(0).copy(authenticationRequired = true).signature())
    }

    @Test fun snapshotReferencesRoundTripWithoutLosingProfileOrAction() {
        val mirror = MirrorPayload("pkg", "App", "Title", "Text", 10, "key", true, true,
            generation = "generation", replyActionId = "reply-id", readActionId = "read-id", sourceUserId = 12)
        assertEquals(mirror, MirrorPayload.fromJson(mirror.toJson()))
        val command = CommandPayload("reply", "pkg", "key", "سلام", sourcePostTime = 10,
            generation = mirror.generation, actionId = mirror.replyActionId, sourceUserId = mirror.sourceUserId)
        assertEquals(command, CommandPayload.fromJson(command.toTransportJson()))
    }

    @Test fun oldNotificationGenerationCannotActAfterUpdateOrRemoval() {
        val ledger = SyncLedger()
        val mirror = MirrorPayload("pkg", "App", "Title", "Text", 10, "key", generation = "a", eventTime = 10)
        val tag = SyncLedger.tag(mirror)
        ledger.accept(mirror, "")
        assertTrue(ledger.isCurrent(tag, "a"))
        ledger.accept(mirror.copy(generation = "b", eventTime = 11, eventId = "update"), "")
        val restored = SyncLedger(ledger.toJson())
        assertFalse(restored.isCurrent(tag, "a"))
        assertTrue(restored.isCurrent(tag, "b"))
        restored.accept(mirror.copy(event = MirrorPayload.EVENT_REMOVE, eventTime = 12, eventId = "remove"), "")
        assertFalse(restored.isCurrent(tag, "b"))
    }

    @Test fun actionResultsAreMatchedDeduplicatedAndRetainedAcrossRestart() {
        val command = CommandPayload("reply", "pkg", "key", generation = "g")
        val ledger = ActionLedger()
        ledger.queued(command, "tag")
        val restored = ActionLedger(ledger.toJson())
        assertNull(restored.complete("wrong-id", "tag", "g", "DISPATCHED"))
        assertNull(restored.complete(command.id, "wrong-tag", "g", "DISPATCHED"))
        assertNull(restored.complete(command.id, "tag", "old-generation", "DISPATCHED"))
        assertNotNull(restored.complete(command.id, "tag", "g", "UNKNOWN"))
        restored.submitted(command.id)
        assertEquals("UNKNOWN", restored.latest()?.state)
        assertNull(restored.complete(command.id, "tag", "g", "DISPATCHED"))
    }

    @Test fun delayedOlderResultDoesNotReplaceTheLatestUserAction() {
        val ledger = ActionLedger()
        val old = CommandPayload("reply", "pkg", "key", generation = "a")
        val new = old.copy(id = "new", generation = "b")
        ledger.queued(old, "tag"); ledger.queued(new, "tag")
        ledger.complete(old.id, "tag", "a", "DISPATCHED")
        assertEquals(new.id, ledger.latest()?.id)
        assertEquals("QUEUED", ledger.latest()?.state)
    }

    @Test fun coalescingPreservesRemovalBarriersAndHasABoundedCapacity() {
        val queue = CoalescingBuffer<String>(3)
        assertTrue(queue.offer("a", "old", true))
        assertTrue(queue.offer("a", "updated", true))
        assertEquals(1, queue.size)
        assertTrue(queue.offer("a", "removed", false))
        assertTrue(queue.offer("a", "reposted", true))
        assertFalse(queue.offer("b", "overflow", true))
        assertEquals("updated", queue.poll())
        assertEquals("removed", queue.poll())
        assertEquals("reposted", queue.poll())
        assertNull(queue.poll())
    }

    @Test fun workerReconcilesOverflowAndKeepsDurableCommandControl() {
        val blocked = CountDownLatch(1)
        val started = CountDownLatch(1)
        val gap = CountDownLatch(1)
        val command = CountDownLatch(1)
        val errors = mutableListOf<String>()
        val worker = ListenerWorkQueue({ gap.countDown() }, { errors.add("error") })
        try {
            worker.event("first", false) { started.countDown(); blocked.await(5, TimeUnit.SECONDS) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(300) { index -> worker.event(index.toString(), false) {} }
            worker.commands { command.countDown() }
            blocked.countDown()
            assertTrue(command.await(5, TimeUnit.SECONDS))
            assertTrue(gap.await(5, TimeUnit.SECONDS))
            assertTrue(errors.isEmpty())
        } finally { blocked.countDown(); worker.close() }
    }
}
