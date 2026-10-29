package com.cylonid.nativealpha.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 站点 theme-color 解析契约：把页面 meta 值转成系统栏 ARGB。
 *
 * 边界覆盖：3/6 位十六进制、大小写、rgb() 拾取、非法值回落 null
 * （null = 不干预系统栏，回落应用主题色）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebThemeColorBridgeTest {

    @Test
    fun sixDigitHexParsed() {
        assertEquals(0xFF161616.toInt(), WebThemeColorBridge.parseHexColor("#161616"))
    }

    @Test
    fun threeDigitHexExpanded() {
        // #abc → #aabbcc
        assertEquals(0xFFAABBCC.toInt(), WebThemeColorBridge.parseHexColor("#abc"))
    }

    @Test
    fun uppercaseHexAccepted() {
        assertEquals(0xFFFFFFFF.toInt(), WebThemeColorBridge.parseHexColor("#FFFFFF"))
    }

    @Test
    fun withoutHashAccepted() {
        // 部分站点省略 #，浏览器同样容错
        assertEquals(0xFF161616.toInt(), WebThemeColorBridge.parseHexColor("161616"))
    }

    @Test
    fun rgbFunctionRejectedAtKotlinLayer() {
        // rgb() 由 JS 侧 normalize() 归一化成 #rrggbb 后才上行；
        // Kotlin 侧只接十六进制，收到 rgb() 视为非法（防御，不静默猜测）
        assertNull(WebThemeColorBridge.parseHexColor("rgb(16, 32, 48)"))
    }

    @Test
    fun whiteIsParsed() {
        assertEquals(0xFFFFFFFF.toInt(), WebThemeColorBridge.parseHexColor("#fff"))
    }

    @Test
    fun invalidReturnsNull() {
        // null = 不干预系统栏（回落应用主题色），不是黑色
        assertNull(WebThemeColorBridge.parseHexColor(""))
        assertNull(WebThemeColorBridge.parseHexColor(null))
        assertNull(WebThemeColorBridge.parseHexColor("transparent"))
        assertNull(WebThemeColorBridge.parseHexColor("#12345"))
        assertNull(WebThemeColorBridge.parseHexColor("#gggggg"))
    }

    @Test
    fun payloadWithoutColorReturnsNull() {
        assertNull(WebThemeColorBridge.parseThemeColor("""{"color":""}"""))
        assertNull(WebThemeColorBridge.parseThemeColor("""{}"""))
        assertNull(WebThemeColorBridge.parseThemeColor("not json"))
        assertNull(WebThemeColorBridge.parseThemeColor(null))
    }

    @Test
    fun payloadWithColorParsed() {
        assertEquals(0xFF161616.toInt(), WebThemeColorBridge.parseThemeColor("""{"color":"#161616"}"""))
    }

    @Test
    fun shouldApplyOnlyForValidColor() {
        assertEquals(true, WebThemeColorBridge.shouldApply(0xFF161616.toInt()))
        assertEquals(false, WebThemeColorBridge.shouldApply(null))
    }
}
