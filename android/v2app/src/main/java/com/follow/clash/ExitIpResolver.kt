package com.follow.clash

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal object ExitIpResolver {
    data class Result(val ip: String, val countryCode: String)

    private val endpoints = listOf(
        "https://api.ip.sb/geoip",
        "https://ipwho.is/",
    )

    fun resolve(): Result? {
        for (endpoint in endpoints) {
            val result = runCatching { resolve(endpoint) }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    private fun resolve(endpoint: String): Result? {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "ClashNext/0.1.3 Android")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return null
            val text = connection.inputStream.bufferedReader().use { reader ->
                reader.readText().take(64 * 1024)
            }
            val data = JSONObject(text)
            if (data.has("success") && !data.optBoolean("success", true)) return null
            val ip = data.optString("ip").trim()
            val country = data.optString("country_code").trim()
            if (ip.isBlank() || ip.length > 64) null else Result(ip, country)
        } finally {
            connection.disconnect()
        }
    }
}
