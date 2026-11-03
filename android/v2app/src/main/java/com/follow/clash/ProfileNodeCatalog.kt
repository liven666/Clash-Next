package com.follow.clash

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class ProxyGroupCatalog(
    val name: String,
    val nodes: List<String>,
    val selected: String,
    val testUrl: String = "",
    val selectable: Boolean = true,
    val hasRemoteProvider: Boolean = false,
    val remoteProviderPending: Boolean = false,
)

internal data class ProxyCatalog(
    val groups: List<ProxyGroupCatalog>,
    val isLive: Boolean,
) {
    val groupCount: Int
        get() = groups.count { it.selectable }

    val nodeCount: Int
        get() {
            val groupNames = groups.mapTo(mutableSetOf()) { it.name }
            return groups
                .flatMap { it.nodes }
                .filterNot {
                    it in ProfileNodeCatalog.BUILT_IN_NODES || it in groupNames
                }
                .toSet()
                .size
        }

    val pendingProviderGroups: List<String>
        get() = groups
            .filter { it.remoteProviderPending }
            .map { it.name }
}

internal data class RawProxyGroup(
    val name: String,
    val nodes: List<String>,
    val testUrl: String,
    val includesAllProxies: Boolean,
    val hasRemoteProvider: Boolean,
)

internal object ProfileNodeCatalog {
    val BUILT_IN_NODES = setOf("DIRECT", "REJECT", "REJECT-DROP", "PASS", "COMPATIBLE")

    private const val CACHE_VERSION = 1
    private val ignoredFingerprintLine = Regex(
        pattern = """(?i)^(?:mode|log-level|geo-auto-update)\s*:""",
    )

    fun fromRuntime(
        data: JSONObject,
        selectedMap: Map<String, String>,
    ): ProxyCatalog {
        val allGroups = data.optJSONArray("all") ?: JSONArray()
        val proxies = data.optJSONObject("proxies") ?: JSONObject()
        val groups = buildList {
            for (index in 0 until allGroups.length()) {
                val name = allGroups.optString(index).trim()
                val value = proxies.optJSONObject(name) ?: continue
                val nodes = value.optJSONArray("all").toStringList()
                if (name.isBlank() || nodes.isEmpty()) continue
                val coreSelected = value.optString("now")
                add(
                    ProxyGroupCatalog(
                        name = name,
                        nodes = nodes,
                        selected = selectedMap[name]
                            ?.takeIf(nodes::contains)
                            ?: coreSelected.takeIf(nodes::contains)
                            ?: nodes.first(),
                        testUrl = value.optString("testUrl"),
                    ),
                )
            }
        }
        return ProxyCatalog(groups = groups, isLive = true)
    }

    fun fromRawConfig(
        data: JSONObject,
        selectedMap: Map<String, String>,
        cached: ProxyCatalog?,
    ): ProxyCatalog {
        val inlineNodes = data.optJSONArray("proxies")
            .objectNames()
        val groups = data.optJSONArray("proxy-groups")
            .objects()
            .mapNotNull { value ->
                val name = value.optString("name").trim()
                if (name.isBlank()) return@mapNotNull null
                RawProxyGroup(
                    name = name,
                    nodes = value.optJSONArray("proxies").toStringList(),
                    testUrl = value.optString("url"),
                    includesAllProxies = value.optBoolean("include-all") ||
                        value.optBoolean("include-all-proxies"),
                    hasRemoteProvider = (value.optJSONArray("use")?.length() ?: 0) > 0 ||
                        value.optBoolean("include-all") ||
                        value.optBoolean("include-all-providers"),
                )
            }
        return buildOffline(
            inlineNodes = inlineNodes,
            rawGroups = groups,
            selectedMap = selectedMap,
            cached = cached,
        )
    }

    fun buildOffline(
        inlineNodes: List<String>,
        rawGroups: List<RawProxyGroup>,
        selectedMap: Map<String, String>,
        cached: ProxyCatalog?,
    ): ProxyCatalog {
        val cachedGroups = cached?.groups.orEmpty().associateBy { it.name }
        val configuredGroupNames = rawGroups.mapTo(mutableSetOf()) { it.name }
        val result = mutableListOf<ProxyGroupCatalog>()
        val groupedInlineNodes = linkedSetOf<String>()

        if ("GLOBAL" !in configuredGroupNames) {
            val cachedGlobal = cachedGroups["GLOBAL"]
            val globalNodes = linkedSetOf<String>().apply {
                add("DIRECT")
                add("REJECT")
                addAll(inlineNodes)
                addAll(rawGroups.map { it.name })
            }.filter { it.isNotBlank() }
            result += ProxyGroupCatalog(
                name = "GLOBAL",
                nodes = globalNodes,
                selected = selectedMap["GLOBAL"]
                    ?.takeIf(globalNodes::contains)
                    ?: cachedGlobal?.selected?.takeIf(globalNodes::contains)
                    ?: globalNodes.first(),
            )
            groupedInlineNodes += inlineNodes
        }

        for (definition in rawGroups) {
            val cachedGroup = cachedGroups[definition.name]
            val staticNodes = linkedSetOf<String>().apply {
                addAll(definition.nodes)
                if (definition.includesAllProxies) addAll(inlineNodes)
            }.filter { it.isNotBlank() }
            val cachedProviderNodes = if (definition.hasRemoteProvider) {
                cachedGroup?.nodes.orEmpty().filterNot(staticNodes::contains)
            } else {
                emptyList()
            }
            val nodes = linkedSetOf<String>().apply {
                addAll(staticNodes)
                addAll(cachedProviderNodes)
            }.toList()
            groupedInlineNodes += nodes.filter(inlineNodes::contains)
            val selected = selectedMap[definition.name]
                ?.takeIf(nodes::contains)
                ?: cachedGroup?.selected?.takeIf(nodes::contains)
                ?: nodes.firstOrNull().orEmpty()
            result += ProxyGroupCatalog(
                name = definition.name,
                nodes = nodes,
                selected = selected,
                testUrl = definition.testUrl.ifBlank { cachedGroup?.testUrl.orEmpty() },
                hasRemoteProvider = definition.hasRemoteProvider,
                remoteProviderPending = definition.hasRemoteProvider &&
                    cachedProviderNodes.isEmpty(),
            )
        }

        val ungroupedNodes = inlineNodes.filterNot(groupedInlineNodes::contains)
        if (ungroupedNodes.isNotEmpty() || (result.isEmpty() && inlineNodes.isNotEmpty())) {
            val visibleNodes = if (result.isEmpty()) inlineNodes else ungroupedNodes
            result += ProxyGroupCatalog(
                name = if (result.isEmpty()) "全部节点" else "其他节点",
                nodes = visibleNodes,
                selected = "",
                selectable = false,
            )
        }

        for ((name, cachedGroup) in cachedGroups) {
            if (name !in configuredGroupNames && cachedGroup.hasRemoteProvider) {
                result += cachedGroup.copy(
                    selected = selectedMap[name]
                        ?.takeIf(cachedGroup.nodes::contains)
                        ?: cachedGroup.selected,
                )
            }
        }
        return ProxyCatalog(groups = result, isLive = false)
    }

    fun save(cacheFile: File, configBytes: ByteArray, catalog: ProxyCatalog) {
        val root = JSONObject()
            .put("version", CACHE_VERSION)
            .put("fingerprint", fingerprint(configBytes))
            .put("groups", JSONArray().apply {
                for (group in catalog.groups) {
                    put(JSONObject()
                        .put("name", group.name)
                        .put("nodes", JSONArray(group.nodes))
                        .put("selected", group.selected)
                        .put("testUrl", group.testUrl)
                        .put("selectable", group.selectable)
                        .put("hasRemoteProvider", group.hasRemoteProvider)
                        .put("remoteProviderPending", group.remoteProviderPending))
                }
            })
        val serialized = root.toString()
        if (cacheFile.isFile && cacheFile.readText() == serialized) return
        val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        temp.writeText(serialized)
        if (!temp.renameTo(cacheFile)) {
            cacheFile.writeText(serialized)
            temp.delete()
        }
    }

    fun load(cacheFile: File, configBytes: ByteArray): ProxyCatalog? = runCatching {
        if (!cacheFile.isFile) return@runCatching null
        val root = JSONObject(cacheFile.readText())
        if (root.optInt("version") != CACHE_VERSION ||
            root.optString("fingerprint") != fingerprint(configBytes)
        ) {
            return@runCatching null
        }
        val groups = root.optJSONArray("groups").objects().mapNotNull { value ->
            val name = value.optString("name").trim()
            val nodes = value.optJSONArray("nodes").toStringList()
            if (name.isBlank() || nodes.isEmpty()) return@mapNotNull null
            ProxyGroupCatalog(
                name = name,
                nodes = nodes,
                selected = value.optString("selected").takeIf(nodes::contains)
                    ?: nodes.first(),
                testUrl = value.optString("testUrl"),
                selectable = value.optBoolean("selectable", true),
                hasRemoteProvider = value.optBoolean("hasRemoteProvider"),
                remoteProviderPending = value.optBoolean("remoteProviderPending"),
            )
        }
        ProxyCatalog(groups = groups, isLive = false)
    }.getOrNull()

    fun preferMoreCompleteDuringWarmup(
        fresh: ProxyCatalog,
        cached: ProxyCatalog?,
    ): ProxyCatalog {
        if (cached == null) return fresh
        val cachedGroups = cached.groups.associateBy { it.name }
        val freshNames = fresh.groups.mapTo(mutableSetOf()) { it.name }
        val groups = fresh.groups.map { freshGroup ->
            val cachedGroup = cachedGroups[freshGroup.name]
            if (cachedGroup == null || cachedGroup.nodes.size <= freshGroup.nodes.size) {
                freshGroup
            } else {
                freshGroup.copy(
                    nodes = cachedGroup.nodes,
                    selected = freshGroup.selected.takeIf(cachedGroup.nodes::contains)
                        ?: cachedGroup.selected.takeIf(cachedGroup.nodes::contains)
                        ?: cachedGroup.nodes.first(),
                    testUrl = freshGroup.testUrl.ifBlank { cachedGroup.testUrl },
                )
            }
        }.toMutableList()
        groups += cached.groups.filterNot { it.name in freshNames }
        return fresh.copy(groups = groups)
    }

    fun fingerprint(bytes: ByteArray): String {
        val stableText = bytes.toString(Charsets.UTF_8)
            .lineSequence()
            .filterNot { ignoredFingerprintLine.containsMatchIn(it.trimStart()) }
            .joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(stableText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun JSONArray?.objectNames(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.optString("name")?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let(::add)
            }
        }
    }
}
