package com.mtpali.notification

import java.net.HttpURLConnection
import java.net.URL
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import org.json.JSONObject

object RelayDiagnostics {
    private val executor = Executors.newSingleThreadExecutor()
    fun check(baseUrl: String, token: String, projectId: String, completed: (String) -> Unit) {
        executor.execute {
            val result = try {
                val base = RelayProtocol.baseUrl(baseUrl)
                val health = request("$base/health")
                if (health.first != 200) RelayProtocol.error(health.first, health.second.optString("error"))
                else {
                    val checked = request("$base/v1/check", token, projectId)
                    when {
                        checked.first == 404 -> "Worker reachable, but diagnostic endpoint is missing. Deploy Worker 1.3.0."
                        checked.first in 200..299 && checked.second.optBoolean("ok") ->
                            "Worker, private key and Firebase sending permission verified. Use Send Test to check the second phone."
                        else -> RelayProtocol.error(checked.first, checked.second.optString("error"))
                    }
                }
            } catch (_: Exception) { "Worker unreachable; check Internet, HTTPS URL and Worker deployment" }
            completed(result)
        }
    }

    private fun request(address: String, token: String = "", projectId: String = ""): Pair<Int, JSONObject> {
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 20_000
            if (token.isNotBlank()) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(JSONObject().put("projectId", projectId).toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val body = readBody(if (status in 200..299) connection.inputStream else connection.errorStream)
            return status to runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        } finally { connection.disconnect() }
    }

    // InputStream.readNBytes is not available on the Android 9 minimum SDK.
    fun readBody(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (output.size() < 8192) {
                val count = it.read(buffer, 0, minOf(buffer.size, 8192 - output.size()))
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
