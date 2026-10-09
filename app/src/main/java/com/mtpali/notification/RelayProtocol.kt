package com.mtpali.notification

import java.net.URI

object RelayProtocol {
    /** Accept the base URL or a copied known endpoint; never follow credential-bearing redirects. */
    fun baseUrl(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null) { "Use an HTTPS Worker URL without query or login details" }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val basePath = when {
            path.endsWith("/v1/send") -> path.removeSuffix("/v1/send")
            path.endsWith("/v1/check") -> path.removeSuffix("/v1/check")
            path.endsWith("/health") -> path.removeSuffix("/health")
            else -> path
        }
        return "https://${uri.rawAuthority}$basePath"
    }

    fun messageId(id: String): String = if (id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) id
        else NotificationReference.hash(id)

    fun privateKeyError(value: String): String = when {
        value.trim().length < 24 -> "Worker private key must contain at least 24 characters"
        value.trim().any { it.code !in 32..126 } -> "Worker private key contains unsupported characters; use ASCII letters, digits or symbols"
        else -> ""
    }

    fun error(status: Int, code: String): String = when (code) {
        "unauthorized" -> "Worker private key rejected"
        "relay_not_configured" -> "Worker Firebase secret is missing or invalid"
        "firebase_project_mismatch" -> "Worker uses a different Firebase project"
        "oauth_rejected", "oauth_failed" -> "Worker Google service account was rejected"
        "fcm_permission_denied" -> "Enable FCM API and service-account sending permission"
        "fcm_invalid_payload" -> "Firebase rejected the message format"
        else -> when (status) {
            401, 403 -> "Worker private key rejected"
            404 -> "Worker endpoint missing; check URL or deploy the current Worker"
            429 -> "Worker rate limited; send will retry"
            in 500..599 -> "Worker or Firebase unavailable (HTTP $status)"
            else -> "Worker rejected request (HTTP $status)"
        }
    }
}
