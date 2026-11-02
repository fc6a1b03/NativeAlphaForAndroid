package com.cylonid.nativealpha.util

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
        val line = "Blocked aria-hidden on an element because its descendant retained focus. " +
            "The focus must not be hidden from assistive technology users."
        assertTrue(BrowserConsoleNoise.isNoise(line))
    }

    @Test
    fun `real js error passes through`() {
        assertFalse(
            BrowserConsoleNoise.isNoise("Uncaught TypeError: Cannot read properties of undefined")
        )
        assertFalse(
            BrowserConsoleNoise.isNoise("Uncaught ReferenceError: webnativeShare is not defined")
        )
    }

    @Test
    fun `null and empty are not noise`() {
        assertFalse(BrowserConsoleNoise.isNoise(null))
        assertFalse(BrowserConsoleNoise.isNoise(""))
    }
}
