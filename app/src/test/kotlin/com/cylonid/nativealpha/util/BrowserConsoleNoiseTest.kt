package com.cylonid.nativealpha.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器控制台噪音过滤单测（BrowserConsoleNoise.isNoise 纯函数，宿主/矩阵
 * ChromeClient 共用）：2026-10-29 实机导出单日 22 条 Chromium 自身预警（全部
 * 来自 zcode.z.ai 页面侧代码坏味道）灌进 statErrors/FeatureMetrics 与诊断
 * 日志——三类白名单必须精确命中且不误伤真错误。
 */
class BrowserConsoleNoiseTest {

    @Test
    fun `passive listener warning is noise`() {
        val line = "Unable to preventDefault inside passive event listener invocation."
        assertTrue(BrowserConsoleNoise.isNoise(line))
    }

    @Test
    fun `resizeobserver loop is noise`() {
        val line = "ResizeObserver loop completed with undelivered notifications."
        assertTrue(BrowserConsoleNoise.isNoise(line))
    }

    @Test
    fun `aria-hidden focus warning is noise`() {
        // 两种 Chromium 措辞都须命中：旧泛指 + 新带具体标签
        // （2026-11-02 实机日志原文——v2.4.8 白名单只写了「on an element」，
        // 对实测「on a <body> element」恒 false，过滤从未生效）
        val legacy = "Blocked aria-hidden on an element because its descendant retained focus. " +
            "The focus must not be hidden from assistive technology users."
        val actual = "Blocked aria-hidden on a <body> element because it would hide the " +
            "entire accessibility tree from assistive technology users. For more details, " +
            "see the aria-hidden section of the WAI-ARIA specification at https://w3c.github.io/aria/"
        assertTrue(BrowserConsoleNoise.isNoise(legacy))
        assertTrue(BrowserConsoleNoise.isNoise(actual))
    }

    /** 2026-11-02 实机导出 14 条 WARNING 原文逐一断言（真实消息锁死白名单） */
    @Test
    fun `realdevice log batch of 14 warnings is all noise`() {
        val cspDirective = "Refused to connect to 'data:,' because it violates the following " +
            "Content Security Policy directive: \"connect-src 'self' " +
            "https://hagen.challenges.cloudflare.com https://brunhild.challenges.cloudflare.com\"."
        val cspDocument = "Refused to connect to 'data:,' because it violates the document's " +
            "Content Security Policy."
        val styleNaN = "%c%d font-size:0;color:transparent NaN"
        val styleObj = "%c%o font-size:0;color:transparent [object Object]"
        val domException = "[object DOMException]"
        val ariaBody = "Blocked aria-hidden on a <body> element because it would hide the " +
            "entire accessibility tree from assistive technology users. For more details, " +
            "see the aria-hidden section of the WAI-ARIA specification at https://w3c.github.io/aria/"
        val batch = listOf(
            cspDirective, cspDocument, styleNaN, styleObj, domException, ariaBody,
            cspDirective, cspDocument, styleNaN, styleObj, styleNaN, styleNaN,
            cspDirective, cspDocument
        )
        assertEquals(14, batch.size)
        batch.forEach { assertTrue(BrowserConsoleNoise.isNoise(it)) }
    }

    @Test
    fun `real js error passes through`() {
        assertFalse(
            BrowserConsoleNoise.isNoise("Uncaught TypeError: Cannot read properties of undefined")
        )
        assertFalse(
            BrowserConsoleNoise.isNoise("Uncaught ReferenceError: webnativeShare is not defined")
        )
        assertFalse(
            BrowserConsoleNoise.isNoise("Refused to connect to 'https://api.example.com' because it violates CSP")
        )
    }

    @Test
    fun `null and empty are not noise`() {
        assertFalse(BrowserConsoleNoise.isNoise(null))
        assertFalse(BrowserConsoleNoise.isNoise(""))
    }
}
