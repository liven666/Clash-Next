package com.follow.clash

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetInfoTest {
    @Test
    fun keepsFlagAlreadyPresentInNodeName() {
        assertEquals("🇯🇵", WidgetInfo.flagFromNodeName("🇯🇵 东京 01"))
    }

    @Test
    fun infersFlagFromChineseNodeName() {
        assertEquals("🇭🇰", WidgetInfo.flagFromNodeName("香港 IPLC 02"))
    }

    @Test
    fun infersFlagFromDelimitedCountryCode() {
        assertEquals("🇸🇬", WidgetInfo.flagFromNodeName("Premium-SG-01"))
    }

    @Test
    fun fallsBackToGlobeForUnknownNode() {
        assertEquals("🌐", WidgetInfo.flagFromNodeName("自动选择"))
    }

    @Test
    fun convertsExitCountryCodeToFlag() {
        assertEquals("🇺🇸", WidgetInfo.flagFromCountryCode("us"))
    }

    @Test
    fun disconnectedWidgetKeepsSavedSelection() {
        assertEquals(
            "香港 IPLC 02",
            WidgetInfo.displayNode(
                running = false,
                activeNode = "等待节点信息",
                selectedNode = "香港 IPLC 02",
            ),
        )
    }

    @Test
    fun connectedWidgetPrefersActualNode() {
        assertEquals(
            "日本 01",
            WidgetInfo.displayNode(
                running = true,
                activeNode = "日本 01",
                selectedNode = "自动选择",
            ),
        )
    }
}
