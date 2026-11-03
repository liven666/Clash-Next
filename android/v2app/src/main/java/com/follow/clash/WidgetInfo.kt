package com.follow.clash

import java.util.Locale

internal object WidgetInfo {
    private val countryKeywords = listOf(
        "香港" to "HK", "hong kong" to "HK", "hk" to "HK",
        "台湾" to "TW", "臺灣" to "TW", "taiwan" to "TW", "tw" to "TW",
        "新加坡" to "SG", "singapore" to "SG", "sg" to "SG",
        "日本" to "JP", "东京" to "JP", "大阪" to "JP", "japan" to "JP", "jp" to "JP",
        "美国" to "US", "洛杉矶" to "US", "西雅图" to "US", "美西" to "US",
        "united states" to "US", "usa" to "US", "us" to "US",
        "英国" to "GB", "伦敦" to "GB", "united kingdom" to "GB", "uk" to "GB",
        "韩国" to "KR", "首尔" to "KR", "korea" to "KR", "kr" to "KR",
        "德国" to "DE", "法兰克福" to "DE", "germany" to "DE", "de" to "DE",
        "法国" to "FR", "巴黎" to "FR", "france" to "FR", "fr" to "FR",
        "加拿大" to "CA", "canada" to "CA", "ca" to "CA",
        "澳大利亚" to "AU", "澳洲" to "AU", "australia" to "AU", "au" to "AU",
        "荷兰" to "NL", "netherlands" to "NL", "nl" to "NL",
        "俄罗斯" to "RU", "russia" to "RU", "ru" to "RU",
        "印度" to "IN", "india" to "IN",
        "土耳其" to "TR", "turkey" to "TR",
    )

    fun flagFromNodeName(name: String): String {
        findFlagEmoji(name)?.let { return it }
        val normalized = name.lowercase(Locale.ROOT)
        val country = countryKeywords.firstOrNull { (keyword, _) ->
            when {
                keyword.length <= 2 && keyword.all(Char::isLetter) ->
                    Regex("(?:^|[^a-z])${Regex.escape(keyword)}(?:[^a-z]|$)")
                        .containsMatchIn(normalized)
                else -> normalized.contains(keyword)
            }
        }?.second
        return flagFromCountryCode(country.orEmpty())
    }

    fun flagFromCountryCode(countryCode: String): String {
        val code = countryCode.trim().uppercase(Locale.ROOT)
        if (code.length != 2 || code.any { it !in 'A'..'Z' }) return "🌐"
        val first = Character.toChars(0x1F1E6 + (code[0] - 'A'))
        val second = Character.toChars(0x1F1E6 + (code[1] - 'A'))
        return String(first) + String(second)
    }

    fun speedText(up: Long, down: Long): String =
        "↑${formatBytes(up)}/s  ↓${formatBytes(down)}/s"

    fun displayNode(running: Boolean, activeNode: String, selectedNode: String): String {
        val active = activeNode.takeUnless { it == "等待节点信息" }.orEmpty()
        return (if (running) {
            active.ifBlank { selectedNode }
        } else {
            selectedNode.ifBlank { active }
        }).ifBlank { "等待节点信息" }
    }

    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1fM", value / 1048576.0)
        value >= 1024L -> String.format(Locale.ROOT, "%.0fK", value / 1024.0)
        else -> "${value.coerceAtLeast(0)}B"
    }

    private fun findFlagEmoji(text: String): String? {
        var index = 0
        while (index < text.length) {
            val first = Character.codePointAt(text, index)
            val nextIndex = index + Character.charCount(first)
            if (first in 0x1F1E6..0x1F1FF && nextIndex < text.length) {
                val second = Character.codePointAt(text, nextIndex)
                if (second in 0x1F1E6..0x1F1FF) {
                    return String(Character.toChars(first)) + String(Character.toChars(second))
                }
            }
            index = nextIndex
        }
        return null
    }
}
