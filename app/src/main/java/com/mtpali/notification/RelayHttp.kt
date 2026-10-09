package com.mtpali.notification

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** Shared by delivery and diagnostics. Never report credentials, bodies or exception messages. */
object RelayHttp {
    const val CONNECT_TIMEOUT_MS = 10_000
    const val HEALTH_TIMEOUT_MS = 15_000
    // OAuth and FCM each allow 8 seconds. A 401 can repeat both: allow that full path.
    const val PUSH_TIMEOUT_MS = 40_000
    const val MAX_RESPONSE_BYTES = 8192

    enum class Phase(val label: String) {
        PREPARE("prepare request"), CONNECT("connect HTTPS"), WRITE("write request"),
        STATUS("wait for response"), BODY("read response")
    }

    data class Response(val status: Int, val body: String, val retryAfterMs: Long = 0)
    class ResponseTooLargeException : IOException("Relay response exceeded its byte limit")

    class Failure(val method: String, val endpoint: String, val phase: Phase,
        val status: Int?, cause: Exception) : IOException("Relay request failed", cause) {
        private fun causes(): List<Throwable> {
            val causes = mutableListOf<Throwable>()
            var current = cause
            repeat(8) { current?.let { causes.add(it); current = it.cause } }
            return causes
        }

        fun summary(): String {
            val causes = causes()
            return when {
                causes.any { it is UnknownHostException } -> "DNS lookup failed; check phone DNS, VPN or network"
                causes.any { it is SSLException || it is CertificateException } ->
                    "HTTPS/TLS verification failed; check device date/time, system updates or VPN/proxy certificates"
                causes.any { it is SocketTimeoutException } -> when (phase) {
                    Phase.CONNECT -> "Connection timed out"
                    Phase.WRITE -> "Sending request timed out"
                    Phase.BODY -> "Reading Worker response timed out"
                    else -> "Worker response timed out"
                }
                causes.any { it is ConnectException || it is NoRouteToHostException } -> "Network connection failed"
                causes.any { it is SecurityException } -> "Android denied network access"
                causes.any { it is ResponseTooLargeException } -> "Worker response exceeds 8192 bytes"
                causes.any { it is IllegalArgumentException } -> "Invalid request settings or private-key characters"
                else -> "Network I/O failed"
            }
        }

        fun report(): String {
            val causes = causes()
            val types = causes.map { it.javaClass.simpleName }.distinct().joinToString(" > ")
            return "$method $endpoint / ${phase.label}${status?.let { "; HTTP $it" }.orEmpty()}: ${summary()} [$types]"
        }
    }

    fun request(address: String, method: String, endpoint: String, body: String? = null,
        token: String? = null, contentType: String = "application/json; charset=utf-8",
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS, readTimeoutMs: Int = PUSH_TIMEOUT_MS,
        headers: Map<String, String> = emptyMap(),
        open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }): Response {
        var connection: HttpURLConnection? = null
        var phase = Phase.PREPARE
        var status: Int? = null
        try {
            require(method == "GET" || method == "POST")
            require((method == "POST") == (body != null))
            if (token != null) require(RelayProtocol.privateKeyError(token).isEmpty())
            connection = open(URL(address))
            val bytes = body?.toByteArray(Charsets.UTF_8)
            connection.apply {
                requestMethod = method
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                setRequestProperty("User-Agent", "Notification-Android/1.3.1")
                setRequestProperty("Accept", "application/json")
                if (bytes != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType)
                    setFixedLengthStreamingMode(bytes.size)
                }
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
                if (token != null) setRequestProperty("Authorization", "Bearer ${token.trim()}")
            }
            phase = Phase.CONNECT
            connection.connect()
            if (bytes != null) {
                phase = Phase.WRITE
                connection.outputStream.use { it.write(bytes) }
            }
            phase = Phase.STATUS
            status = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull()
                ?.coerceIn(0, 3600)?.times(1000) ?: 0
            phase = Phase.BODY
            val response = readBody(if (status in 200..299) connection.inputStream else connection.errorStream)
            return Response(status, response, retryAfter)
        } catch (error: Exception) {
            throw Failure(method, endpoint, phase, status, error)
        } finally {
            connection?.disconnect()
        }
    }

    fun readBody(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_RESPONSE_BYTES) throw ResponseTooLargeException()
                if (count > 0) output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
