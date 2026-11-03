package com.follow.clash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileNodeCatalogTest {
    @Test
    fun buildsOfflineGroupsAndRestoresSavedSelection() {
        val catalog = ProfileNodeCatalog.buildOffline(
            inlineNodes = listOf("香港 01", "日本 02"),
            rawGroups = listOf(
                RawProxyGroup(
                    name = "节点选择",
                    nodes = listOf("香港 01", "日本 02", "DIRECT"),
                    testUrl = "https://example.com/ping",
                    includesAllProxies = false,
                    hasRemoteProvider = false,
                ),
            ),
            selectedMap = mapOf("节点选择" to "日本 02"),
            cached = null,
        )

        val group = catalog.groups.first { it.name == "节点选择" }
        assertFalse(catalog.isLive)
        assertEquals(2, catalog.groupCount)
        assertEquals(2, catalog.nodeCount)
        assertEquals("日本 02", group.selected)
        assertEquals(listOf("香港 01", "日本 02", "DIRECT"), group.nodes)
    }

    @Test
    fun mergesPreviouslyLoadedProviderNodesWhileDisconnected() {
        val cached = ProxyCatalog(
            groups = listOf(
                ProxyGroupCatalog(
                    name = "机场节点",
                    nodes = listOf("新加坡 01", "美国 02"),
                    selected = "美国 02",
                ),
            ),
            isLive = true,
        )

        val catalog = ProfileNodeCatalog.buildOffline(
            inlineNodes = emptyList(),
            rawGroups = listOf(
                RawProxyGroup(
                    name = "机场节点",
                    nodes = emptyList(),
                    testUrl = "",
                    includesAllProxies = false,
                    hasRemoteProvider = true,
                ),
            ),
            selectedMap = mapOf("机场节点" to "新加坡 01"),
            cached = cached,
        )

        val group = catalog.groups.first { it.name == "机场节点" }
        assertEquals(listOf("新加坡 01", "美国 02"), group.nodes)
        assertEquals("新加坡 01", group.selected)
        assertTrue(catalog.pendingProviderGroups.isEmpty())
    }

    @Test
    fun synthesizesSelectableGlobalForUngroupedSubscriptionNodes() {
        val catalog = ProfileNodeCatalog.buildOffline(
            inlineNodes = listOf("香港 01"),
            rawGroups = emptyList(),
            selectedMap = mapOf("GLOBAL" to "香港 01"),
            cached = null,
        )

        val global = catalog.groups.single()
        assertEquals("GLOBAL", global.name)
        assertTrue(global.selectable)
        assertEquals(listOf("DIRECT", "REJECT", "香港 01"), global.nodes)
        assertEquals("香港 01", global.selected)
    }

    @Test
    fun reportsProviderPendingEvenWhenStaticFallbackExists() {
        val catalog = ProfileNodeCatalog.buildOffline(
            inlineNodes = emptyList(),
            rawGroups = listOf(
                RawProxyGroup(
                    name = "机场节点",
                    nodes = listOf("DIRECT"),
                    testUrl = "",
                    includesAllProxies = false,
                    hasRemoteProvider = true,
                ),
            ),
            selectedMap = emptyMap(),
            cached = null,
        )

        assertEquals(listOf("机场节点"), catalog.pendingProviderGroups)
    }

    @Test
    fun warmupDoesNotReplaceCompleteCacheWithPartialSnapshot() {
        val cached = ProxyCatalog(
            groups = listOf(
                ProxyGroupCatalog(
                    name = "机场节点",
                    nodes = listOf("新加坡 01", "美国 02"),
                    selected = "美国 02",
                ),
            ),
            isLive = false,
        )
        val fresh = ProxyCatalog(
            groups = listOf(
                ProxyGroupCatalog(
                    name = "机场节点",
                    nodes = listOf("DIRECT"),
                    selected = "DIRECT",
                ),
            ),
            isLive = true,
        )

        val merged = ProfileNodeCatalog.preferMoreCompleteDuringWarmup(fresh, cached)

        assertTrue(merged.isLive)
        assertEquals(listOf("新加坡 01", "美国 02"), merged.groups.single().nodes)
        assertEquals("美国 02", merged.groups.single().selected)
    }

    @Test
    fun warmupAcceptsMoreCompleteFreshSnapshot() {
        val cached = ProxyCatalog(
            groups = listOf(
                ProxyGroupCatalog("机场节点", listOf("DIRECT"), "DIRECT"),
            ),
            isLive = false,
        )
        val fresh = ProxyCatalog(
            groups = listOf(
                ProxyGroupCatalog(
                    "机场节点",
                    listOf("DIRECT", "新加坡 01"),
                    "新加坡 01",
                ),
            ),
            isLive = true,
        )

        val merged = ProfileNodeCatalog.preferMoreCompleteDuringWarmup(fresh, cached)

        assertEquals(fresh, merged)
    }

    @Test
    fun fingerprintIgnoresRuntimeModeButTracksNodeChanges() {
        val rule = "mode: rule\nlog-level: info\nproxies:\n  - name: 香港 01\n"
        val global = "mode: global\nlog-level: warning\nproxies:\n  - name: 香港 01\n"
        val changed = "mode: global\nlog-level: warning\nproxies:\n  - name: 日本 02\n"

        assertEquals(
            ProfileNodeCatalog.fingerprint(rule.toByteArray()),
            ProfileNodeCatalog.fingerprint(global.toByteArray()),
        )
        assertNotEquals(
            ProfileNodeCatalog.fingerprint(rule.toByteArray()),
            ProfileNodeCatalog.fingerprint(changed.toByteArray()),
        )
    }
}
