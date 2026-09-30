package com.mtpali.notification

import org.json.JSONObject

/** Leave room for AES-GCM, Base64 and the FCM topic data envelope (2 KiB). */
object PayloadBudget {
    const val MAX_PLAINTEXT_BYTES = 1280
    const val MAX_ENCRYPTED_BYTES = 1800

    fun fit(raw: String): String {
        val json = JSONObject(raw)
        for (field in listOf("text", "title", "app")) {
            if (size(json.toString()) <= MAX_PLAINTEXT_BYTES) break
            val value = json.optString(field)
            val count = value.codePointCount(0, value.length)
            var low = 0
            var high = count
            while (low < high) {
                val middle = (low + high + 1) / 2
                json.put(field, value.substring(0, value.offsetByCodePoints(0, middle)))
                if (size(json.toString()) <= MAX_PLAINTEXT_BYTES) low = middle
                else high = middle - 1
            }
            json.put(field, value.substring(0, value.offsetByCodePoints(0, low)))
        }
        return json.toString().also {
            require(size(it) <= MAX_PLAINTEXT_BYTES) { "Notification metadata exceeds payload limit" }
        }
    }

    private fun size(value: String) = value.toByteArray(Charsets.UTF_8).size
}
