package com.mtpali.notification

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class CompleteActionsTest {
    private val input = InputDescriptor("samsung_reply_key", "Write a reply", choices = listOf("بله", "خیر"))
    private val samsung = listOf(
        ActionDescriptor(0, title = "Call"),
        ActionDescriptor(1, title = "Mark as read"), // OEMs need not publish a semantic action.
        ActionDescriptor(2, semantic = 1, inputKeys = listOf(input.key), freeFormKeys = listOf(input.key),
            title = "Reply", inputs = listOf(input)))
    private fun payload(actions: List<ActionDescriptor> = samsung) = MirrorPayload("com.samsung.android.messaging",
        "Messages", "Contact", "سلام 🌍", 100, "original-key", generation = "generation", sourceUserId = 12,
        actions = actions.map { ActionPolicy.mirror("generation", it) })

    @Test fun samsungThreeButtonsKeepOriginalOrderAndIndependentIdsWithoutReadSemantics() {
        val captured = ActionPolicy.complete(samsung.mapIndexed { i, a -> a to "intent-$i" }) { a, b -> a == b }
        val mirror = payload(captured.map { it.first })
        assertEquals(listOf("Call", "Mark as read", "Reply"), mirror.actions.map { it.title })
        assertEquals(3, mirror.actions.map { it.id }.distinct().size)
        assertTrue(mirror.actions.all { it.unsupportedReason.isEmpty() })
        assertEquals(mirror, MirrorPayload.fromJson(mirror.toJson()))
        assertNull(ActionPolicy.markRead(samsung)) // Generic forwarding still retains this button.
    }

    @Test fun twoEqualLabelsAndMultipleRepliesRemainDistinct() {
        val actions = listOf(samsung[2], samsung[2].copy(index = 3))
        assertNull(ActionPolicy.reply(actions))
        val mirror = payload(actions)
        assertEquals(2, mirror.actions.size)
        assertNotEquals(mirror.actions[0].id, mirror.actions[1].id)
    }

    @Test fun wearableDuplicatesAreRemovedOnlyWhenIntentAndMetadataBothMatch() {
        val native = samsung[2]
        val wearable = native.copy(index = 0, origin = "wearable")
        assertEquals(1, ActionPolicy.complete(listOf(native to "same", wearable to "same")) { a, b -> a == b }.size)
        assertEquals(2, ActionPolicy.complete(listOf(native to "first", wearable to "second")) { a, b -> a == b }.size)
        assertEquals(2, ActionPolicy.complete(listOf(native to "same", wearable.copy(title = "Different") to "same")) { a, b -> a == b }.size)
        assertEquals("wearable", ActionPolicy.complete(listOf(wearable to "only")) { a, b -> a == b }.single().first.origin)
    }

    @Test fun generationChangesWhenLabelsChoicesAndInputRulesChange() {
        val reply = samsung[2]
        assertNotEquals(reply.signature(), reply.copy(title = "Send").signature())
        assertNotEquals(reply.signature(), reply.copy(inputs = listOf(input.copy(choices = listOf("new")))).signature())
        assertNotEquals(reply.signature(), reply.copy(inputs = listOf(input.copy(freeForm = false))).signature())
        assertNotEquals(ActionPolicy.mirror("old", reply).id, ActionPolicy.mirror("new", reply).id)
    }

    @Test fun multipleInputsAndChoiceOnlyResultsAreValidatedIndividually() {
        val choice = InputDescriptor("confirm", freeForm = false, choices = listOf("Yes", "No"))
        val inputs = listOf(input, choice)
        val descriptor = samsung[2].copy(inputs = inputs)
        assertEquals("", ActionPolicy.unsupportedReason(descriptor))
        assertFalse(ActionPolicy.mirror("g", descriptor).inlineInput)
        assertTrue(ActionPolicy.validResults(inputs, mapOf(input.key to "سلام", "confirm" to "Yes")))
        assertFalse(ActionPolicy.validResults(inputs, mapOf(input.key to "سلام", "confirm" to "Maybe")))
        assertFalse(ActionPolicy.validResults(inputs, mapOf(input.key to "سلام")))
        assertFalse(ActionPolicy.validResults(inputs, mapOf(input.key to "سلام", "confirm" to "Yes", "wrong" to "x")))
        assertTrue(ActionPolicy.validResults(emptyList(), emptyMap()))
    }

    @Test fun unsupportedActionsKeepTheirButtonAndExplainWhy() {
        val actions = listOf(samsung[0].copy(hasIntent = false), samsung[2].copy(immutable = true),
            samsung[2].copy(index = 3, inputs = listOf(InputDescriptor("photo", freeForm = false, dataTypes = listOf("image/png")))))
        val mirror = payload(actions)
        assertEquals(3, mirror.actions.size)
        assertTrue(mirror.actions.all { it.unsupportedReason.isNotBlank() })
        assertEquals("", ActionPolicy.unsupportedReason(samsung[0].copy(immutable = true)))
        assertTrue(ActionPolicy.unsupportedReason(samsung[2].copy(inputs = listOf(input, input))).isNotBlank())
    }

    @Test fun originalResultKeysAndTextSurviveCommandEncryptionRoundTrip() {
        val command = CommandPayload(CommandPayload.TYPE_ACTION, "pkg", "key", generation = "g", actionId = "action-2",
            inputResults = mapOf(input.key to "  سلام 🌍  "))
        val raw = CryptoBox.decrypt("123456", CryptoBox.encrypt("123456", command.toTransportJson()))
        assertEquals(command, CommandPayload.fromJson(raw))
        assertTrue(runCatching { command.copy(inputResults = mapOf(input.key to "🌍".repeat(1000))).toTransportJson() }.isFailure)
    }

    private fun oversized() = payload((0 until 8).map { i -> samsung[2].copy(index = i,
        title = "دکمه $i ".repeat(20), inputs = listOf(input.copy(key = "key-$i", choices = listOf("پیشنهاد 🌍".repeat(30))))) })

    @Test fun oversizedActionsAreEncryptedAsBoundedPartsWithoutDroppingMetadata() {
        val original = oversized()
        val parts = MirrorWire.encode(original)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= PayloadBudget.MAX_PLAINTEXT_BYTES })
        val encrypted = parts.map { CryptoBox.encrypt("123456", it) }
        assertTrue(encrypted.all { it.length <= PayloadBudget.MAX_ENCRYPTED_BYTES })
        val assembly = MirrorAssembly("123456")
        var raw: String? = null
        encrypted.reversed().forEach { val result = assembly.accept(it); assertTrue(result.accepted); raw = result.raw ?: raw }
        assertEquals(original, MirrorPayload.fromJson(raw!!))
    }

    @Test fun incompletePartsRemainEncryptedAcrossRestartAndNeverDisplayPartialActions() {
        val encrypted = MirrorWire.encode(oversized()).map { CryptoBox.encrypt("123456", it) }
        val first = MirrorAssembly("123456", now = 100)
        assertNull(first.accept(encrypted.first(), 100).raw)
        assertTrue(first.accept(encrypted.first(), 100).accepted)
        assertFalse(first.toJson().contains("samsung_reply_key"))
        assertFalse(first.toJson().contains("دکمه"))
        val restored = MirrorAssembly("123456", first.toJson(), 101)
        var raw: String? = null
        encrypted.drop(1).forEach { raw = restored.accept(it, 101).raw ?: raw }
        assertEquals(oversized().actions, MirrorPayload.fromJson(raw!!).actions)
    }

    @Test fun fragmentsCannotMixGenerationsOrReplaceAnAlreadyReceivedPart() {
        val parts = MirrorWire.encode(oversized())
        val assembly = MirrorAssembly("123456")
        val first = CryptoBox.encrypt("123456", parts.first())
        assertTrue(assembly.accept(first).accepted)
        val modified = JSONObject(parts.first()).put("data", "eA").toString()
        assertFalse(assembly.accept(CryptoBox.encrypt("123456", modified)).accepted)
        val second = MirrorWire.encode(oversized().copy(generation = "changed", eventId = "new-id"))
        assertNull(assembly.accept(CryptoBox.encrypt("123456", second.last())).raw)
        assertFalse(assembly.accept(CryptoBox.encrypt("654321", parts[1])).accepted)
    }

    @Test fun fragmentAgeAndAssemblyLimitsAreEnforced() {
        val parts = MirrorWire.encode(oversized())
        val old = MirrorAssembly("123456", now = 100)
        old.accept(CryptoBox.encrypt("123456", parts.first()), 100)
        val expired = MirrorAssembly("123456", old.toJson(), MirrorAssembly.MAX_AGE_MS + 101)
        assertEquals("[]", expired.toJson())
        assertFalse(expired.accept(CryptoBox.encrypt("123456", JSONObject(parts.first())
            .put("total", MirrorWire.MAX_PARTS + 1).toString())).accepted)
        assertFalse(expired.accept(CryptoBox.encrypt("123456", JSONObject(parts.first()).put("part", -1).toString())).accepted)
    }

    @Test fun workerEndpointsAreNormalizedAndAcknowledgementIdsMeetWorkerRules() {
        for (ending in listOf("", "/", "/health", "/v1/send/", "/v1/check"))
            assertEquals("https://notification.example", RelayProtocol.baseUrl(" https://notification.example$ending "))
        assertEquals("https://example.com/relay", RelayProtocol.baseUrl("https://example.com/relay/v1/send"))
        for (url in listOf("http://example.com", "https://key@example.com", "https://example.com?secret=x", "https://example.com#x"))
            assertTrue(runCatching { RelayProtocol.baseUrl(url) }.isFailure)
        val id = RelayProtocol.messageId("result:command-id")
        assertTrue(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        assertEquals(id, RelayProtocol.messageId("result:command-id"))
        assertEquals("command-id", RelayProtocol.messageId("command-id"))
        assertTrue(RelayProtocol.error(503, "fcm_permission_denied").contains("permission"))
    }
}
