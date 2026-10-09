package com.mtpali.notification

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RelayDiagnosticsTest {
    private val base = "https://relay.example.workers.dev"
    private val token = "private-test-key-with-32-characters"
    private val project = "notification-2515e"

    private class Connection(url: URL, private val status: Int = 200, private val body: String = "{\"ok\":true}",
        private val connectError: Exception? = null, private val statusError: Exception? = null,
        private val bodyError: Exception? = null, private val retryAfter: String? = null) : HttpURLConnection(url) {
        val headers = linkedMapOf<String, String>()
        val sent = ByteArrayOutputStream()
        var disconnected = false
        val fixedBodyBytes: Int get() = fixedContentLength
        override fun connect() { connectError?.let { throw it }; connected = true }
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun setRequestProperty(key: String, value: String) { headers[key] = value; super.setRequestProperty(key, value) }
        override fun getOutputStream() = sent
        override fun getResponseCode(): Int { statusError?.let { throw it }; return status }
        override fun getHeaderField(name: String): String? = if (name == "Retry-After") retryAfter else null
        private fun stream(): InputStream { bodyError?.let { throw it }; return ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) }
        override fun getInputStream(): InputStream = stream()
        override fun getErrorStream(): InputStream = stream()
    }

    private fun check(second: Connection, first: Connection = Connection(URL("$base/health"))): RelayDiagnostics.Result {
        val connections = ArrayDeque(listOf(first, second))
        return RelayDiagnostics.runCheck(base, token, project) { connections.removeFirst() }
    }

    @Test fun actualHttpBuilderSendsGetThenAuthorizedPostWithProjectAndNoRedirects() {
        val opened = mutableListOf<Connection>()
        val result = RelayDiagnostics.runCheck("$base/v1/check/", "  $token  ", project) { url ->
            Connection(url).also { opened.add(it) }
        }
        assertEquals(2, opened.size)
        val health = opened[0]
        val checked = opened[1]
        assertEquals("$base/health", health.url.toString())
        assertEquals("GET", health.requestMethod)
        assertNull(health.headers["Authorization"])
        assertFalse(health.doOutput)
        assertEquals("$base/v1/check", checked.url.toString())
        assertEquals("POST", checked.requestMethod)
        assertEquals("Bearer $token", checked.headers["Authorization"])
        assertEquals("application/json; charset=utf-8", checked.headers["Content-Type"])
        assertEquals(project, JSONObject(checked.sent.toString("UTF-8")).getString("projectId"))
        assertEquals(checked.sent.size(), checked.fixedBodyBytes)
        assertEquals(40_000, checked.readTimeout)
        assertEquals(15_000, health.readTimeout)
        assertTrue(opened.all { !it.instanceFollowRedirects && !it.useCaches && it.disconnected })
        assertTrue(result.summary.startsWith("Connection verified"))
        assertFalse(result.report.contains(token))
        assertTrue(result.report.contains("Authorization=Bearer [redacted]"))
    }

    @Test fun wireRequestCanBeReadByAnHttpServerWithCorrectBodyLengthAndAuthorization() {
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val requests = mutableListOf<Triple<String, String?, String>>()
        val serverError = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                repeat(2) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                        val method = reader.readLine().substringBefore(' ')
                        val headers = linkedMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: throw IOException("Incomplete test request")
                            if (line.isEmpty()) break
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        // The test JSON/project are ASCII, so character and byte counts match.
                        val chars = CharArray(headers["content-length"]?.toInt() ?: 0)
                        var read = 0
                        while (read < chars.size) {
                            val count = reader.read(chars, read, chars.size - read)
                            if (count < 0) throw IOException("Incomplete test body")
                            read += count
                        }
                        requests.add(Triple(method, headers["authorization"], String(chars)))
                        val body = "{\"ok\":true}"
                        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                    }
                }
            } catch (error: Throwable) { serverError.set(error) }
        }.apply { isDaemon = true; start() }
        try {
            // Only the test factory replaces HTTPS with a loopback HTTP server; production uses platform TLS.
            val result = RelayDiagnostics.runCheck(base, token, project) { url ->
                URL("http://127.0.0.1:${server.localPort}${url.path}").openConnection() as HttpURLConnection
            }
            worker.join(5000)
            assertNull(serverError.get())
            assertTrue(result.summary.startsWith("Connection verified"))
            assertEquals(Triple("GET", null, ""), requests[0])
            assertEquals("POST", requests[1].first)
            assertEquals("Bearer $token", requests[1].second)
            assertEquals(project, JSONObject(requests[1].third).getString("projectId"))
        } finally { server.close(); worker.join(5000) }
    }

    @Test fun invalidSettingsAreRejectedBeforeOpeningAConnection() {
        var opened = 0
        val factory: (URL) -> HttpURLConnection = { opened++; Connection(it) }
        assertTrue(RelayDiagnostics.runCheck("http://relay.example", token, project, factory).summary.startsWith("Invalid Relay URL"))
        assertTrue(RelayDiagnostics.runCheck(base, "x".repeat(23), project, factory).summary.contains("at least 24"))
        assertTrue(RelayDiagnostics.runCheck(base, token + "\r\nInjected: yes", project, factory).summary.contains("unsupported characters"))
        assertTrue(RelayDiagnostics.runCheck(base, token + "ی", project, factory).summary.contains("unsupported characters"))
        assertTrue(RelayDiagnostics.runCheck(base, token, "", factory).summary.contains("project ID"))
        assertEquals(0, opened)
    }

    @Test fun dnsFailureNamesTheGetConnectionStageAndNeverExposesExceptionText() {
        val result = check(Connection(URL("$base/v1/check")), Connection(URL("$base/health"),
            connectError = UnknownHostException("private $token")))
        assertTrue(result.summary.startsWith("DNS lookup failed"))
        assertTrue(result.report.contains("GET /health / connect HTTPS"))
        assertTrue(result.report.contains("UnknownHostException"))
        assertFalse(result.report.contains("POST /v1/check"))
        assertFalse(result.report.contains(token))
    }

    @Test fun tlsFailureIsDistinguishedFromAnUnreachableWorkerWithoutWeakeningTrust() {
        val result = check(Connection(URL("$base/v1/check"), connectError =
            IOException("Authorization: Bearer $token", SSLHandshakeException("private $token"))))
        assertTrue(result.summary.startsWith("HTTPS/TLS verification failed"))
        assertTrue(result.report.contains("GET /health: HTTP 200"))
        assertTrue(result.report.contains("POST /v1/check / connect HTTPS"))
        assertTrue(result.report.contains("SSLHandshakeException"))
        assertFalse(result.report.contains(token))
    }

    @Test fun checkTimeoutPreservesSuccessfulHealthAndThePostResponseStage() {
        val result = check(Connection(URL("$base/v1/check"), statusError = SocketTimeoutException("private $token")))
        assertEquals("Worker response timed out", result.summary)
        assertTrue(result.report.contains("GET /health: HTTP 200"))
        assertTrue(result.report.contains("POST /v1/check / wait for response"))
        assertFalse(result.report.contains(token))
    }

    @Test fun keyRejectionIsAnHttpFailureRatherThanANetworkFailure() {
        val result = check(Connection(URL("$base/v1/check"), 401, "{\"error\":\"unauthorized\"}"))
        assertEquals("Worker private key rejected", result.summary)
        assertTrue(result.report.contains("POST /v1/check: HTTP 401"))
    }

    @Test fun htmlErrorIsNotReportedAsAnInvalidPrivateKey() {
        val result = check(Connection(URL("$base/v1/check"), 403, "<html>Access challenge</html>"))
        assertTrue(result.summary.contains("non-JSON response"))
        assertFalse(result.summary.contains("key rejected"))
    }

    @Test fun htmlHealthResponseStopsBeforeTheAuthenticatedRequest() {
        var opened = 0
        val result = RelayDiagnostics.runCheck(base, token, project) { url ->
            opened++; Connection(url, body = "<html>not this Worker</html>")
        }
        assertEquals(1, opened)
        assertTrue(result.summary.startsWith("Unexpected health response"))
    }

    @Test fun redirectAndMissingEndpointExplainHowToCorrectTheUrlOrDeployment() {
        assertTrue(check(Connection(URL("$base/v1/check"), 302, "")).summary.contains("redirected HTTP 302"))
        assertTrue(check(Connection(URL("$base/v1/check"), 404, "{\"error\":\"not_found\"}")).summary.contains("endpoint is missing"))
    }

    @Test fun responseReadFailureRetainsTheReceivedHttpStatus() {
        val result = check(Connection(URL("$base/v1/check"), bodyError = IOException("private $token")))
        assertTrue(result.report.contains("POST /v1/check / read response; HTTP 200"))
        assertFalse(result.report.contains(token))
    }

    @Test fun oversizedResponseIsBoundedAndDiagnosed() {
        val result = check(Connection(URL("$base/v1/check"), body = "x".repeat(8193)))
        assertEquals("Worker response exceeds 8192 bytes", result.summary)
        assertTrue(result.report.contains("read response; HTTP 200"))
    }

    @Test fun sharedDeliveryBuilderPreservesRetryDelayAndCompatibilityHeaders() {
        val connection = Connection(URL("https://ntfy.sh/test"), 429, "limit", retryAfter = "12")
        val response = RelayHttp.request(connection.url.toString(), "POST", "/[ntfy topic]", body = "encrypted",
            contentType = "text/plain; charset=utf-8", connectTimeoutMs = 4000, readTimeoutMs = 4000,
            headers = mapOf("Firebase" to "no"), open = { connection })
        assertEquals(429, response.status)
        assertEquals(12_000L, response.retryAfterMs)
        assertEquals("no", connection.headers["Firebase"])
        assertNull(connection.headers["Authorization"])
        assertEquals(4000, connection.readTimeout)
    }
}
