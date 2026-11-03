package com.follow.clash

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetLayoutTest {
    @Test
    fun selectsOneByTwoLayout() {
        assertEquals(R.layout.widget_1x2, ProxyWidgetProvider.selectLayout(110, 48))
    }

    @Test
    fun selectsOneByFourLayout() {
        assertEquals(R.layout.widget_1x4, ProxyWidgetProvider.selectLayout(250, 48))
    }

    @Test
    fun selectsTwoByTwoLayout() {
        assertEquals(R.layout.widget_2x2, ProxyWidgetProvider.selectLayout(110, 110))
    }

    @Test
    fun selectsTwoByFourLayout() {
        assertEquals(R.layout.widget_2x4, ProxyWidgetProvider.selectLayout(250, 110))
    }
}
