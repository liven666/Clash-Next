package com.follow.clash

internal object ProfileCompatibility {
    const val DEFAULT_SUBSCRIPTION_USER_AGENT =
        "FlClash/v0.8.94 clash-verge Platform/android"

    private const val PREVIOUS_SUBSCRIPTION_USER_AGENT =
        "ClashMetaForAndroid/2.11.17.Meta clash.meta Platform/android"

    // Different subscription panels recognize different established client
    // tokens. Try the exact upstream FlClash identity first, then conservative
    // generic fallbacks. A response containing an upgrade-warning profile is
    // rejected instead of being saved.
    val subscriptionUserAgents = listOf(
        DEFAULT_SUBSCRIPTION_USER_AGENT,
        "clash-verge",
        "clash.meta",
        PREVIOUS_SUBSCRIPTION_USER_AGENT,
    )

    private val globalUserAgentPattern = Regex(
        pattern = """(?mi)^global-ua\s*:\s*(?:"([^"]*)"|'([^']*)'|([^#\r\n]*))\s*(?:#.*)?$""",
        option = RegexOption.IGNORE_CASE,
    )

    private val geoAutoUpdatePattern = Regex(
        pattern = """(?mi)^geo-auto-update\s*:[^\r\n]*$""",
    )

    private val logLevelPattern = Regex(
        pattern = """(?mi)^log-level\s*:[^\r\n]*$""",
    )

    private val modeLinePattern = Regex(
        pattern = """(?mi)^mode\s*:[^\r\n]*$""",
    )

    private val modeValuePattern = Regex(
        pattern = """(?mi)^mode\s*:\s*[\"']?(rule|global|direct)[\"']?\s*(?:#.*)?$""",
    )

    private val managedUserAgents = (subscriptionUserAgents +
        PREVIOUS_SUBSCRIPTION_USER_AGENT).toSet()

    private val unsupportedMarkers = listOf(
        "客户端已过时",
        "客户端版本过旧",
        "客户端过旧",
        "请更换软件",
        "请升级客户端",
        "请更新客户端",
        "client is outdated",
        "client outdated",
        "obsolete client",
    )

    fun normalize(
        bytes: ByteArray,
        compatibleUserAgent: String = DEFAULT_SUBSCRIPTION_USER_AGENT,
    ): ByteArray {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val existing = globalUserAgentPattern.find(text)
        val withUserAgent = if (existing != null) {
            val currentValue = existing.groupValues
                .drop(1)
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
                .trim()
            if (currentValue !in managedUserAgents &&
                !currentValue.startsWith("ClashNext/", ignoreCase = true)
            ) {
                text
            } else {
                text.replaceRange(
                    existing.range,
                    "global-ua: \"$compatibleUserAgent\"",
                )
            }
        } else {
            buildString(text.length + 96) {
                append("global-ua: \"")
                append(compatibleUserAgent)
                append("\"\n")
                append(text)
            }
        }
        return enableDiagnosticLogging(
            disableGeoAutoUpdate(withUserAgent.toByteArray(Charsets.UTF_8)),
        )
    }

    fun disableGeoAutoUpdate(bytes: ByteArray): ByteArray {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val bundled = if (geoAutoUpdatePattern.containsMatchIn(text)) {
            text.replace(geoAutoUpdatePattern, "geo-auto-update: false")
        } else {
            "geo-auto-update: false\n$text"
        }
        return bundled.toByteArray(Charsets.UTF_8)
    }

    fun enableDiagnosticLogging(bytes: ByteArray): ByteArray {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val logging = if (logLevelPattern.containsMatchIn(text)) {
            text.replace(logLevelPattern, "log-level: info")
        } else {
            "log-level: info\n$text"
        }
        return logging.toByteArray(Charsets.UTF_8)
    }

    fun modeFrom(bytes: ByteArray): String? {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        return modeValuePattern.find(text)?.groupValues?.getOrNull(1)?.lowercase()
    }

    fun applyMode(bytes: ByteArray, mode: String): ByteArray {
        val normalizedMode = mode.lowercase()
        require(normalizedMode in setOf("rule", "global", "direct")) {
            "Unsupported proxy mode: $mode"
        }
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val updated = if (modeLinePattern.containsMatchIn(text)) {
            text.replace(modeLinePattern, "mode: $normalizedMode")
        } else {
            "mode: $normalizedMode\n$text"
        }
        return updated.toByteArray(Charsets.UTF_8)
    }

    fun looksLikeUnsupportedClient(bytes: ByteArray): Boolean {
        val text = bytes.toString(Charsets.UTF_8).lowercase()
        return unsupportedMarkers.any(text::contains)
    }
}
