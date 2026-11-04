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

    /**
     * linux.do 报障回归钉（2026-11-03）：Discourse 双 meta（media 限定
     * light/dark）形态下，脚本必须全量扫描并按 media 命中取值，而非
     * querySelector 单条首命中的选择逻辑在 JVM 层无法执行，此处钉脚本契约
     * ——选段逻辑若被改回单条命中，该测试先行报警。
     */
    @Test
    fun themeJsScansAllMetasWithMediaMatching() {
        val js = WebThemeColorBridge.THEME_JS
        // 全量扫描（querySelector 单条命中是报障根因，禁止回归）
        assertEquals(true, js.contains("querySelectorAll('meta[name=\"theme-color\"]')"))
        assertEquals(false, js.contains("querySelector('meta[name=\"theme-color\"]')"))
        // media 命中判定 + 无 media 回落 + 深浅切换时重算
        assertEquals(true, js.contains("matchMedia(md).matches"))
        assertEquals(true, js.contains("fallback"))
        assertEquals(true, js.contains("prefers-color-scheme"))
    }

    /**
     * linux.do 二次报障回归钉（2026-11-04，v2.4.12 修复无效的根因）：
     * Discourse 的深浅由**站点账号主题设置**决定，与系统 prefers-color-scheme
     * 无关——实测系统 light + htmlBg rgb(34,34,34)，两条 meta 按系统值求值
     * 命中 light=#fff，页面却是深色，仍是白栏。
     *
     * 修法：用页面实际渲染的 `getComputedStyle(documentElement).colorScheme`
     * 作为判定源，明确 dark/light 时优先于系统 matchMedia。**若有人把
     * actualScheme 判定删掉退回纯 matchMedia，该测试先行报警。**
     */
    @Test
    fun themeJsPrefersPageColorSchemeOverSystemPreference() {
        val js = WebThemeColorBridge.THEME_JS
        // 页面实际配色方案读取（修复核心判据）
        assertEquals(true, js.contains("getComputedStyle"))
        assertEquals(true, js.contains("colorScheme"))
        assertEquals(true, js.contains("actualScheme"))
        // 判定源优先级：先页面声明，未声明才回落系统 matchMedia
        assertEquals(true, js.contains("mediaMatches(md,scheme)"))
        assertEquals(true, js.contains("scheme==='dark'"))
        assertEquals(true, js.contains("scheme==='light'"))
        // 二义（"light dark"）或未声明必须回落系统，不能误判成深色
        assertEquals(true, js.contains("hasDark&&!hasLight"))
        assertEquals(true, js.contains("hasLight&&!hasDark"))
    }

    /**
     * 运行期主题切换回归钉：站点改 `<html class>` / 内联 style 切深色时
     * meta 文本不变，观察器必须能感知——attributeFilter 漏掉 class/style
     * 会让栏色滞留旧值（旧实现只监听 content）。
     */
    @Test
    fun themeJsObservesClassAndStyleChanges() {
        val js = WebThemeColorBridge.THEME_JS
        assertEquals(true, js.contains("attributeFilter"))
        assertEquals(true, js.contains("'class'"))
        assertEquals(true, js.contains("'style'"))
    }

    /**
     * 观察器防抖钉：SPA 的 class 变更极频繁，裸 read() 每次都会
     * getComputedStyle 强制样式重算——必须合并到单次读取。
     */
    @Test
    fun themeJsDebouncesObserverReads() {
        val js = WebThemeColorBridge.THEME_JS
        assertEquals(true, js.contains("pending"))
        assertEquals(true, js.contains("setTimeout"))
    }
}
