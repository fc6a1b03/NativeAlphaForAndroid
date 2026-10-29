package com.cylonid.nativealpha

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器控制台噪音过滤单测（CustomWebChromeClient.isBrowserConsoleNoise 纯函数）：
 * 2026-10-29 实机导出单日 22 条 Chromium 自身预警（全部来自 zcode.z.ai 页面侧
 * 代码坏味道）灌进 statErrors 与诊断日志——三类白名单必须精确命中且不误伤真错误。
 */
class WebViewChromeClientNoiseTest {

    @Test
    fun `passive listener warning is noise`() {
        val line = "Unable to preventDefault inside passive event listener invocation."
        assertTrue(CustomWebChromeClient.isBrowserConsoleNoise(line))
    }

    @Test
    fun `resizeobserver loop is noise`() {
        val line = "ResizeObserver loop completed with undelivered notifications."
        assertTrue(CustomWebChromeClient.isBrowserConsoleNoise(line))
    }

    @Test
    fun `aria-hidden focus warning is noise`() {
        val line = "Blocked aria-hidden on an element because its descendant retained focus. " +
            "The focus must not be hidden from assistive technology users."
        assertTrue(CustomWebChromeClient.isBrowserConsoleNoise(line))
    }

    @Test
    fun `real js error passes through`() {
        assertFalse(
            CustomWebChromeClient.isBrowserConsoleNoise("Uncaught TypeError: Cannot read properties of undefined")
        )
        assertFalse(
            CustomWebChromeClient.isBrowserConsoleNoise("Uncaught ReferenceError: webnativeShare is not defined")
        )
    }

    @Test
    fun `null and empty are not noise`() {
        assertFalse(CustomWebChromeClient.isBrowserConsoleNoise(null))
        assertFalse(CustomWebChromeClient.isBrowserConsoleNoise(""))
    }
}
