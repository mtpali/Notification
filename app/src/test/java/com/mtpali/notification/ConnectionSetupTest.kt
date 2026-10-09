package com.mtpali.notification

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectionSetupTest {
    private val url = "https://relay.example.workers.dev"
    private val key = "private-test-key-with-32-characters"
    private fun raw(json: JSONObject) = "NotificationSetup1:" + Base64.getEncoder()
        .encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    private fun validJson() = JSONObject().put("version", 1).put("pairCode", "123456")
        .put("relayUrl", url).put("privateKey", key).put("transport", Prefs.RECEIVER_PUSH)
    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun setupTransfersAllConnectionFieldsAndNormalizesCopiedEndpointAndWhitespace() {
        val setup = ConnectionSetup.create(" 123456 ", "$url/v1/check/", " $key ", Prefs.RECEIVER_PUSH)
        val restored = ConnectionSetup.decode("\n${setup.encode()}\n")
        assertEquals("123456", restored.pairCode)
        assertEquals(url, restored.relayUrl)
        assertEquals(key, restored.privateKey)
        assertEquals(Prefs.RECEIVER_PUSH, restored.transport)
        assertFalse(setup.toString().contains(key))
        val json = JSONObject(String(Base64.getDecoder().decode(setup.encode().substringAfter(':')), Charsets.UTF_8))
        assertFalse(json.has("mode"))
        assertFalse(json.has("receiverEnabled"))
    }

    @Test fun compatibilityNeedsNoWorkerKeyAndDoesNotCopyAnUnusedSecret() {
        val setup = ConnectionSetup.decode(ConnectionSetup.create("123456", url, key, Prefs.RECEIVER_STABLE).encode())
        assertEquals(Prefs.RECEIVER_STABLE, setup.transport)
        assertEquals("", setup.privateKey)
    }

    @Test fun existingLongPairKeysRemainCompatible() {
        val pair = "a".repeat(32)
        assertEquals(pair, ConnectionSetup.decode(ConnectionSetup.create(pair, url, key, Prefs.RECEIVER_PUSH).encode()).pairCode)
    }

    @Test fun malformedUnknownOrIncompleteClipboardDataCannotBecomeASetup() {
        rejects { ConnectionSetup.decode("ordinary clipboard text") }
        rejects { ConnectionSetup.decode("NotificationSetup1:%%%") }
        rejects { ConnectionSetup.decode(raw(validJson().put("version", 2))) }
        rejects { ConnectionSetup.decode(raw(validJson().apply { remove("privateKey") })) }
        rejects { ConnectionSetup.decode(raw(validJson().put("pairCode", 123456))) }
        rejects { ConnectionSetup.decode(raw(validJson().put("transport", "unknown"))) }
        rejects { ConnectionSetup.decode("NotificationSetup1:" + "x".repeat(8192)) }
    }

    @Test fun invalidPairUrlOrHeaderKeyIsRejectedBeforeImport() {
        rejects { ConnectionSetup.decode(raw(validJson().put("pairCode", "123"))) }
        rejects { ConnectionSetup.decode(raw(validJson().put("relayUrl", "http://relay.example"))) }
        rejects { ConnectionSetup.decode(raw(validJson().put("relayUrl", "https://user:pass@relay.example"))) }
        rejects { ConnectionSetup.decode(raw(validJson().put("privateKey", "x".repeat(23)))) }
        rejects { ConnectionSetup.decode(raw(validJson().put("privateKey", key + "\r\nInjected: yes"))) }
        rejects { ConnectionSetup.decode(raw(validJson().put("privateKey", key + "ی"))) }
    }
}
