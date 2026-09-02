package com.cylonid.nativealpha.model

import com.cylonid.nativealpha.WebViewActivity
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 核心业务逻辑测试：
 * - URL 规范化/校验（添加向导）
 * - WebApp 设置拷贝（全局/应用主次）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoreLogicTest {

    // ---------- UrlUtils ----------

    @Test
    fun `normalize adds https prefix when missing`() {
        assertEquals("https://example.com", UrlUtils.normalize("example.com"))
        assertEquals("https://example.com", UrlUtils.normalize("  example.com  "))
    }

    @Test
    fun `normalize keeps existing protocol`() {
        assertEquals("http://example.com", UrlUtils.normalize("http://example.com"))
        assertEquals("https://example.com", UrlUtils.normalize("https://example.com"))
    }

    @Test
    fun `normalize returns empty for blank`() {
        assertEquals("", UrlUtils.normalize(""))
        assertEquals("", UrlUtils.normalize("   "))
    }

    @Test
    fun `validate accepts valid urls`() {
        assertNull(UrlUtils.validate("example.com"))
        assertNull(UrlUtils.validate("https://example.com"))
        assertNull(UrlUtils.validate("https://sub.example.com/path?q=1"))
    }

    @Test
    fun `validate rejects empty and invalid`() {
        assertEquals("empty", UrlUtils.validate(""))
        assertEquals("empty", UrlUtils.validate("   "))
        assertEquals("invalid", UrlUtils.validate("not a url"))
    }

    @Test
    fun `hostOf extracts host`() {
        assertEquals("example.com", UrlUtils.hostOf("https://example.com/path"))
        assertNull(UrlUtils.hostOf(""))
    }

    @Test
    fun `displayHost strips protocol and path keeps domain`() {
        assertEquals("linux.do", UrlUtils.displayHost("https://linux.do/t/topic/2799634"))
        assertEquals("example.com", UrlUtils.displayHost("https://www.example.com/app?q=1"))
    }

    @Test
    fun `displayHost keeps port for ip sites`() {
        assertEquals("114.132.159.229:15666", UrlUtils.displayHost("http://114.132.159.229:15666/#token=abc"))
    }

    // ---------- WebApp 拷贝（全局/应用主次核心） ----------

    @Test
    fun `webapp copy copies all settings`() {
        val original = WebApp("https://example.com", 0, 1).apply {
            title = "Example"
            isAllowJs = false
            isAllowCookies = false
            isRequestDesktop = true
            userAgent = "Custom UA"
            timeAutoreload = 30
            isForceDarkMode = true
            timespanDarkModeBegin = "21:00"
            timespanDarkModeEnd = "07:00"
        }
        val copy = WebApp(original.baseUrl, original.ID, original.order).apply {
            title = original.title
            copySettings(original)
        }
        assertEquals(original.title, copy.title)
        assertEquals(original.isAllowJs, copy.isAllowJs)
        assertEquals(original.isAllowCookies, copy.isAllowCookies)
        assertEquals(original.isRequestDesktop, copy.isRequestDesktop)
        assertEquals(original.userAgent, copy.userAgent)
        assertEquals(original.timeAutoreload, copy.timeAutoreload)
        assertEquals(original.isForceDarkMode, copy.isForceDarkMode)
        assertEquals(original.timespanDarkModeBegin, copy.timespanDarkModeBegin)
        assertEquals(original.timespanDarkModeEnd, copy.timespanDarkModeEnd)
    }

    @Test
    fun `copySettings does not mutate source`() {
        val source = WebApp("https://a.com", 1, 2).apply {
            isAllowJs = false
            isAllowCookies = false
        }
        val target = WebApp("https://b.com", 2, 3).apply {
            isAllowJs = true
        }
        target.copySettings(source)
        // source 不应被修改
        assertEquals(false, source.isAllowJs)
        assertEquals(false, source.isAllowCookies)
        // target 应被覆盖
        assertEquals(false, target.isAllowJs)
        assertEquals(false, target.isAllowCookies)
    }

    @Test
    fun `webapp default settings are sensible`() {
        val w = WebApp("https://example.com", 0, 0)
        assertTrue(w.isAllowJs)
        assertTrue(w.isAllowCookies)
        assertTrue(w.isActiveEntry)
    }

    @Test
    fun `applySettingsForNewWebApp disables override`() {
        val w = WebApp("https://example.com", 0, 0)
        w.applySettingsForNewWebApp()
        assertEquals(false, w.isOverrideGlobalSettings)
    }

    @Test
    fun `alphanumericBaseUrl strips symbols`() {
        val w = WebApp("https://www.example.com", 0, 0)
        val result = w.alphanumericBaseUrl
        // 不含特殊符号
        assertTrue(!result.contains("://"))
        assertTrue(!result.contains("."))
    }

    // ---------- Shortcut parsing ----------

    @Test
    fun `parseShortcut parses multiple modifiers and key`() {
        val result = WebViewActivity.parseShortcut("Ctrl+Shift+S")
        assertNotNull(result)
        assertEquals(true, result!!.ctrl)
        assertEquals(true, result.shift)
        assertEquals(false, result.alt)
        assertEquals("S", result.key)
    }

    @Test
    fun `parseShortcut parses single modifier and function key`() {
        val result = WebViewActivity.parseShortcut("Alt+Enter")
        assertNotNull(result)
        assertEquals(false, result!!.ctrl)
        assertEquals(false, result.shift)
        assertEquals(true, result.alt)
        assertEquals("Enter", result.key)
    }

    @Test
    fun `parseShortcut returns null for bare plus sign`() {
        // 旧数据/异常输入可能单独传入 "+"，必须避免当正则 compile 导致 crash
        assertNull(WebViewActivity.parseShortcut("+"))
    }

    @Test
    fun `parseShortcut returns null for empty string`() {
        assertNull(WebViewActivity.parseShortcut(""))
    }

    @Test
    fun `parseShortcut returns null for modifiers only`() {
        assertNull(WebViewActivity.parseShortcut("Ctrl+Shift"))
    }

    @Test
    fun `parseShortcut ignores empty segments from consecutive plus`() {
        // "Ctrl++S" 中间的空段应被忽略，仍然识别 Ctrl+S
        val result = WebViewActivity.parseShortcut("Ctrl++S")
        assertNotNull(result)
        assertEquals(true, result!!.ctrl)
        assertEquals("S", result.key)
    }
}
