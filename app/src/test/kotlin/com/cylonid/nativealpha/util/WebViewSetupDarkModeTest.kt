package com.cylonid.nativealpha.util

import com.cylonid.nativealpha.model.WebApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按站强制深色判定单测（宿主/矩阵唯一同源实现——矩阵暗色失效修复的
 * 回归锚点：判定结果决定 algorithmic darkening 开关）。
 *
 * 时段窗口用例只取全天窗口（00:00–23:59）保证与「当前时刻」无关的
 * 确定性；任意窗口的边界语义由 DateUtils.isInInterval 既有行为覆盖。
 */
class WebViewSetupDarkModeTest {

    private fun webapp(): WebApp = WebApp("https://example.com", 0, 0)

    @Test
    fun `force dark mode flag wins`() {
        val app = webapp().apply { isForceDarkMode = true }
        assertTrue(WebViewSetup.needsForcedDarkMode(app))
    }

    @Test
    fun `no dark settings means light`() {
        val app = webapp().apply { isForceDarkMode = false }
        assertFalse(WebViewSetup.needsForcedDarkMode(app))
    }

    @Test
    fun `timespan mode covers whole day`() {
        val app = webapp().apply {
            isForceDarkMode = false
            isUseTimespanDarkMode = true
            timespanDarkModeBegin = "00:00"
            timespanDarkModeEnd = "23:59"
        }
        assertTrue(WebViewSetup.needsForcedDarkMode(app))
    }

    @Test
    fun `timespan mode without valid interval stays light`() {
        val app = webapp().apply {
            isForceDarkMode = false
            isUseTimespanDarkMode = true
            // 非法时间串解析为 null：按不在窗口处理（原实现 !! 抛 NPE 崩页面，
            // 共用抽取时改为防御性容错）
            timespanDarkModeBegin = "garbage"
            timespanDarkModeEnd = "garbage"
        }
        assertFalse(WebViewSetup.needsForcedDarkMode(app))
    }

    @Test
    fun `force flag respected even when timespan disabled`() {
        val app = webapp().apply {
            isForceDarkMode = true
            isUseTimespanDarkMode = false
        }
        assertTrue(WebViewSetup.needsForcedDarkMode(app))
    }

    // ===== cellDarkContextPlan：矩阵格深色上下文决策 =====

    @Test
    fun `forced site pins dark theme and night yes`() {
        val (style, uiMode) = WebViewSetup.cellDarkContextPlan(forced = true, globalThemeId = 0)
        assertEquals(com.cylonid.nativealpha.R.style.AppThemeDark, style)
        assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_YES, uiMode)
    }

    @Test
    fun `forced site wins over global light setting`() {
        val (style, uiMode) = WebViewSetup.cellDarkContextPlan(forced = true, globalThemeId = 1)
        assertEquals(com.cylonid.nativealpha.R.style.AppThemeDark, style)
        assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_YES, uiMode)
    }

    @Test
    fun `global dark setting pins dark without site flag`() {
        val (style, uiMode) = WebViewSetup.cellDarkContextPlan(forced = false, globalThemeId = 2)
        assertEquals(com.cylonid.nativealpha.R.style.AppThemeDark, style)
        assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_YES, uiMode)
    }

    @Test
    fun `global light setting pins light theme`() {
        val (style, uiMode) = WebViewSetup.cellDarkContextPlan(forced = false, globalThemeId = 1)
        assertEquals(com.cylonid.nativealpha.R.style.AppTheme, style)
        assertEquals(android.content.res.Configuration.UI_MODE_NIGHT_NO, uiMode)
    }

    @Test
    fun `follow system keeps resource qualifier resolution`() {
        // 跟随系统：不覆写 uiMode（-1），让 values-night 资源解析实现「跟随」
        val (style, uiMode) = WebViewSetup.cellDarkContextPlan(forced = false, globalThemeId = 0)
        assertEquals(com.cylonid.nativealpha.R.style.AppTheme, style)
        assertEquals(-1, uiMode)
    }
}
