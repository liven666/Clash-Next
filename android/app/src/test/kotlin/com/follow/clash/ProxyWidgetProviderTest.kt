package com.follow.clash

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyWidgetProviderTest {
    @Test
    fun selectsOneByTwoLayout() {
        assertEquals(
            R.layout.widget_1x2,
            ProxyWidgetProvider.selectLayout(width = 110, height = 48),
        )
    }

    @Test
    fun selectsOneByFourLayout() {
        assertEquals(
            R.layout.widget_1x4,
            ProxyWidgetProvider.selectLayout(width = 250, height = 48),
        )
    }

    @Test
    fun selectsTwoByTwoLayout() {
        assertEquals(
            R.layout.widget_2x2,
            ProxyWidgetProvider.selectLayout(width = 110, height = 110),
        )
    }

    @Test
    fun selectsTwoByFourLayout() {
        assertEquals(
            R.layout.widget_2x4,
            ProxyWidgetProvider.selectLayout(width = 250, height = 110),
        )
    }
}
