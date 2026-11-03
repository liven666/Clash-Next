package com.follow.clash

import java.net.URI

internal object DiagnosticSanitizer {
    private val urlPattern = Regex("""https?://[^\s\"'<>]+""", RegexOption.IGNORE_CASE)
    private val credentialPattern = Regex(
        """(?i)(token|password|passwd|secret|authorization|uuid)(\s*[:=]\s*)([^\s,;&]+)""",
    )

    fun redact(value: String): String {
        val withoutUrls = urlPattern.replace(value) { match -> redactUrl(match.value) }
        return credentialPattern.replace(withoutUrls) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
        }
    }

    fun sourceHost(value: String): String = runCatching {
        URI(value).host.orEmpty()
    }.getOrDefault("").ifBlank { "local" }

    private fun redactUrl(value: String): String = runCatching {
        val uri = URI(value)
        val host = uri.host ?: return@runCatching "https://<redacted>"
        buildString {
            append(uri.scheme.lowercase())
            append("://")
            append(host)
            if (uri.port > 0) append(":${uri.port}")
            if (!uri.path.isNullOrBlank() && uri.path != "/") append("/…")
        }
    }.getOrDefault("https://<redacted>")
}
