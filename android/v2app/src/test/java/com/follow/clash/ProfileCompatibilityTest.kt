package com.follow.clash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileCompatibilityTest {
    @Test
    fun addsCompatibleGlobalUserAgent() {
        val source = "proxies: []\nrules:\n  - MATCH,DIRECT\n".toByteArray()

        val normalized = ProfileCompatibility.normalize(source).toString(Charsets.UTF_8)

        assertTrue(normalized.contains("global-ua: \"FlClash/v0.8.94"))
        assertTrue(normalized.contains("clash-verge Platform/android"))
        assertTrue(normalized.endsWith(source.toString(Charsets.UTF_8)))
    }

    @Test
    fun preservesProfileUserAgentWhenAlreadyConfigured() {
        val source = "global-ua: custom-client\nproxies: []\n"

        val normalized = ProfileCompatibility.normalize(source.toByteArray())
            .toString(Charsets.UTF_8)

        assertTrue(normalized.contains(source))
        assertTrue(normalized.contains("geo-auto-update: false\n"))
    }

    @Test
    fun stripsUtf8BomBeforeWritingYaml() {
        val source = "\uFEFFproxies: []\n"

        val normalized = ProfileCompatibility.normalize(source.toByteArray())
            .toString(Charsets.UTF_8)

        assertTrue(!normalized.contains('\uFEFF'))
    }

    @Test
    fun replacesPreviouslyManagedUserAgentWithAcceptedIdentity() {
        val source = "global-ua: \"ClashMetaForAndroid/2.11.17.Meta clash.meta Platform/android\"\nproxies: []\n"

        val normalized = ProfileCompatibility.normalize(
            source.toByteArray(),
            "clash-verge",
        ).toString(Charsets.UTF_8)

        assertTrue(normalized.contains("global-ua: \"clash-verge\""))
        assertTrue(!normalized.contains("ClashMetaForAndroid"))
    }

    @Test
    fun detectsChineseUpgradeWarningProfile() {
        val source = "proxies:\n  - name: Clash客户端已过时，请更换软件\n"

        assertTrue(ProfileCompatibility.looksLikeUnsupportedClient(source.toByteArray()))
    }

    @Test
    fun acceptsNormalProfile() {
        val source = "proxies:\n  - name: 香港 01\n    type: ss\n"

        assertTrue(!ProfileCompatibility.looksLikeUnsupportedClient(source.toByteArray()))
    }

    @Test
    fun disablesProfileGeoAutoUpdate() {
        val source = "geo-auto-update: true\nproxies: []\n"

        val normalized = ProfileCompatibility.disableGeoAutoUpdate(source.toByteArray())
            .toString(Charsets.UTF_8)

        assertTrue(normalized.startsWith("geo-auto-update: false\n"))
        assertTrue(!normalized.contains("geo-auto-update: true"))
    }

    @Test
    fun addsBundledGeoSettingWhenMissing() {
        val source = "proxies: []\n"

        val normalized = ProfileCompatibility.disableGeoAutoUpdate(source.toByteArray())
            .toString(Charsets.UTF_8)

        assertEquals("geo-auto-update: false\n$source", normalized)
    }

    @Test
    fun forcesInfoLevelForDiagnosticLogging() {
        val source = "log-level: silent\nproxies: []\n"

        val normalized = ProfileCompatibility.enableDiagnosticLogging(source.toByteArray())
            .toString(Charsets.UTF_8)

        assertTrue(normalized.startsWith("log-level: info\n"))
        assertTrue(!normalized.contains("log-level: silent"))
    }

    @Test
    fun readsQuotedProxyMode() {
        val source = "mode: 'GLOBAL' # selected by profile\nproxies: []\n"

        assertEquals("global", ProfileCompatibility.modeFrom(source.toByteArray()))
    }

    @Test
    fun replacesExistingProxyMode() {
        val source = "mode: rule # old value\nproxies: []\n"

        val updated = ProfileCompatibility.applyMode(source.toByteArray(), "direct")
            .toString(Charsets.UTF_8)

        assertTrue(updated.startsWith("mode: direct\n"))
        assertTrue(!updated.contains("mode: rule"))
    }

    @Test
    fun addsProxyModeWhenMissing() {
        val source = "proxies: []\n"

        val updated = ProfileCompatibility.applyMode(source.toByteArray(), "global")
            .toString(Charsets.UTF_8)

        assertEquals("mode: global\n$source", updated)
    }
}
