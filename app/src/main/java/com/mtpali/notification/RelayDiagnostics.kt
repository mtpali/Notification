package com.mtpali.notification

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

object RelayDiagnostics {
    private val executor = Executors.newSingleThreadExecutor()
    data class Result(val summary: String, val report: String)

    fun check(baseUrl: String, token: String, projectId: String, completed: (Result) -> Unit) {
        executor.execute { completed(runCheck(baseUrl, token, projectId)) }
    }

    /** Tests exercise the actual GET/POST/header/body builder through this connection factory. */
    internal fun runCheck(baseUrl: String, token: String, projectId: String,
        open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }): Result {
        val trace = mutableListOf<String>()
        fun result(summary: String, detail: String = summary) = Result(summary, (trace + detail).joinToString("\n"))
        val base = runCatching { RelayProtocol.baseUrl(baseUrl) }.getOrNull()
            ?: return result("Invalid Relay URL; use the base HTTPS Worker URL")
        val keyError = RelayProtocol.privateKeyError(token)
        if (keyError.isNotEmpty()) return result(keyError)
        if (projectId.isBlank()) return result("Firebase project ID is missing in this APK")
        return try {
            val health = RelayHttp.request("$base/health", "GET", "/health",
                readTimeoutMs = RelayHttp.HEALTH_TIMEOUT_MS, open = open)
            trace.add("GET /health: HTTP ${health.status}")
            val healthJson = parse(health.body)
            if (health.status != 200) return result(responseError(health, healthJson))
            if (healthJson == null || !healthJson.optBoolean("ok"))
                return result("Unexpected health response; check the Worker URL", "Unexpected health response; expected Worker JSON with ok=true")
            trace.add("POST /v1/check: projectId=$projectId; Authorization=Bearer [redacted]; Content-Type=application/json")
            val checked = RelayHttp.request("$base/v1/check", "POST", "/v1/check",
                body = JSONObject().put("projectId", projectId).toString(), token = token.trim(), open = open)
            trace.add("POST /v1/check: HTTP ${checked.status}")
            val checkedJson = parse(checked.body)
            val summary = when {
                checked.status == 404 -> "Worker reachable, but diagnostic endpoint is missing. Deploy Worker 1.3.0."
                checked.status in 200..299 && checkedJson?.optBoolean("ok") == true ->
                    "Connection verified ✓ Use Send Test to check the second phone."
                checked.status in 200..299 -> "Unexpected diagnostic response; expected Worker JSON with ok=true"
                else -> responseError(checked, checkedJson)
            }
            result(summary)
        } catch (error: RelayHttp.Failure) {
            result(error.summary(), error.report())
        } catch (error: Exception) {
            // Class only: exception messages may contain a header value or other private data.
            result("Worker check could not complete; open Details", "Worker check could not complete [${error.javaClass.simpleName}]")
        }
    }

    private fun parse(body: String): JSONObject? = runCatching { JSONObject(body) }.getOrNull()

    private fun responseError(response: RelayHttp.Response, json: JSONObject?): String = when {
        response.status in 300..399 -> "Worker redirected HTTP ${response.status}; use the final base HTTPS URL"
        json == null -> "HTTP ${response.status} with a non-JSON response; check Relay URL or Cloudflare Access/challenge"
        else -> RelayProtocol.error(response.status, json.optString("error"))
    }
}
