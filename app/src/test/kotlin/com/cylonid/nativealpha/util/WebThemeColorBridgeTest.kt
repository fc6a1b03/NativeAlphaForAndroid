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
     * light/dark）形态下，脚本必须全量扫描，禁止 querySelector 单条首命中
     * 的选择逻辑回归。
     */
    @Test
    fun themeJsScansAllMetasWithMediaMatching() {
        val js = WebThemeColorBridge.THEME_JS
        // 全量扫描（querySelector 单条命中是报障根因，禁止回归）
        assertEquals(true, js.contains("querySelectorAll('meta[name=\"theme-color\"]')"))
        assertEquals(false, js.contains("querySelector('meta[name=\"theme-color\"]')"))
        // 系统深浅切换重算监听（T3）
        assertEquals(true, js.contains("prefers-color-scheme"))
    }

    /**
     * 判据层级钉（2026-11-07 终版设计）：**系统 matchMedia 不得参与 meta
     * 选条**——Discourse 深浅与系统无关（v2.4.12/2.4.13 两轮白栏根因），
     * S1 明确按声明匹配、S1 无信号走 S2 背景亮度选条。
     */
    @Test
    fun themeJsNeverUsesSystemMatchMediaForMetaSelection() {
        val js = WebThemeColorBridge.THEME_JS
        // 系统求值已彻底移除（唯一保留的 matchMedia 是 T3 的 dark 监听）
        assertEquals(false, js.contains("matchMedia(md)"))
        assertEquals(false, js.contains("mediaMatches"))
        // S1 声明匹配 + S2 亮度选条两级都在
        assertEquals(true, js.contains("scheme==='dark'&&hasDark&&!hasLight"))
        assertEquals(true, js.contains("scheme==='light'&&hasLight&&!hasDark"))
        assertEquals(true, js.contains("lum<0.45&&darkVal!==null"))
        assertEquals(true, js.contains("lum>=0.55&&lightVal!==null"))
    }

    /**
     * linux.do 二次报障回归钉（2026-11-04，v2.4.12 修复无效的根因）：
     * Discourse 的深浅由**站点账号主题设置**决定，与系统 prefers-color-scheme
     * 无关——实测系统 light + htmlBg rgb(34,34,34)，两条 meta 按系统值求值
     * 命中 light=#fff，页面却是深色，仍是白栏。
     *
     * 修法：用页面实际渲染的 `getComputedStyle(documentElement).colorScheme`
     * 作为判定源（S1），明确 dark/light 时优先按声明匹配。**若有人把
     * actualScheme 判定删掉退回纯系统求值，该测试先行报警。**
     */
    @Test
    fun themeJsPrefersPageColorSchemeOverSystemPreference() {
        val js = WebThemeColorBridge.THEME_JS
        // 页面实际配色方案读取（修复核心判据）
        assertEquals(true, js.contains("getComputedStyle"))
        assertEquals(true, js.contains("colorScheme"))
        assertEquals(true, js.contains("actualScheme"))
        // 二义（"light dark"）或未声明不算信号，不能误判成深/浅
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
     * observer 直读钉（2026-11-07 真机定谳）：WebView 131 上 observer
     * callback 内注册的 setTimeout 永不执行（CDP 实证 cb:1/inner:0）——
     * v2.4.13 的 80ms 防抖 setTimeout 恰是 t2 死因。callback 必须同步直读。
     * **禁止重新引入 callback 内 setTimeout 形态的防抖。**
     */
    @Test
    fun themeJsObserverReadsSynchronously() {
        val js = WebThemeColorBridge.THEME_JS
        assertEquals(true, js.contains("new MutationObserver(function(){read('t2');})"))
        // 防抖形态禁止回归
        assertEquals(false, js.contains("function schedule()"))
        // 挂载重试（startObserver 的 200ms setTimeout）保留——它在启动期执行，
        // 不在 observer callback 内，不受该内核怪癖影响
        assertEquals(true, js.contains("setTimeout(startObserver,200)"))
    }

    /**
     * body 属性观察钉（2026-11-07 故障树复盘 F5）：Discourse 等站点在
     * 站点设置内切深浅改的是 body class（CSS 变量随之翻转）——html/head
     * 观察点看不见且无 configChanged/resume，漏看会导致站点内切主题时
     * 栏色滞留。body 观察必须存在（attributes only，body 内元素仍零监听）。
     */
    @Test
    fun themeJsObservesBodyClassChanges() {
        val js = WebThemeColorBridge.THEME_JS
        assertEquals(true, js.contains("obBody.observe(document.body"))
        // body 只观察自身属性，不监听子树（渲染性能红线）
        assertEquals(true, js.contains("obBody.observe(document.body,{attributes:true,subtree:false"))
    }

    /**
     * 渲染性能钉（2026-11-07 方案评审红线）：observer 必须收窄到 html 自身
     * 属性 + head 子树两点——subtree 全页 childList/class 监听会让 SPA 每帧
     * DOM 变更都进回调（渲染管线常驻税）。禁止回归成全页监听。
     */
    @Test
    fun themeJsObserversAreNarrowedToHtmlAndHead() {
        val js = WebThemeColorBridge.THEME_JS
        // html 自身属性：subtree 必须为 false
        assertEquals(true, js.contains("obHtml.observe(document.documentElement,{attributes:true,subtree:false"))
        // head 子树监听存在
        assertEquals(true, js.contains("obHead.observe(document.head"))
        // 全页监听禁止回归：不允许 html 上挂 subtree:true
        assertEquals(false, js.contains("obHtml.observe(document.documentElement,{childList:true,subtree:true"))
    }

    /**
     * 触发器完备钉：t5 load 补读（SPA 晚挂 meta）/ t6 回前台单次重算 /
     * t7 宿主 evaluateJavascript 踢入口（__wnThemeReread——WebView 131 动态
     * 路径失效的宿主侧兜底）。
     */
    @Test
    fun themeJsHasAllTriggerSources() {
        val js = WebThemeColorBridge.THEME_JS
        assertEquals(true, js.contains("__wnThemeReread"))
        assertEquals(true, js.contains("addEventListener('load'"))
        assertEquals(true, js.contains("visibilitychange"))
        // t7 强制上报（绕过去重）：宿主踢必有回音，活性可确认
        assertEquals(true, js.contains("src==='t7'||c!==last"))
        // 上报载荷带触发器来源标记（探针判路用）
        assertEquals(true, js.contains("src:src||'t1'"))
    }

    /**
     * S2 背景色兜底契约钉：仅在「有 meta 但全部未命中」时按亮度在
     * dark/light 条目间补选；无 meta 站点不干预（坑位 16 口径）；透明背景
     * 与 0.45~0.55 中间带不采用。
     */
    @Test
    fun themeJsHasBackgroundLuminanceFallback() {
        val js = WebThemeColorBridge.THEME_JS
        assertEquals(true, js.contains("darkVal"))
        assertEquals(true, js.contains("lightVal"))
        assertEquals(true, js.contains("lum<0.45&&darkVal!==null"))
        assertEquals(true, js.contains("lum>=0.55&&lightVal!==null"))
        assertEquals(true, js.contains("a>=0.99"))
        // S2 门控（2026-11-07 终版）：无声明站点也走兜底——背景极暗(<0.3)/
        // 极亮(>0.7)直接按背景上栏（linux.do 深色页无 meta 实锤逼出的口径
        // 变更，向 Chrome 背景色着色行为对齐）；0.3~0.7 中间带仅在有对应
        // 声明条目时跟随
        assertEquals(true, js.contains("if(!c){"))
        assertEquals(true, js.contains("lum<0.3)c=darkVal||normalize(col)"))
        assertEquals(true, js.contains("lum>=0.7)c=lightVal||normalize(col)"))
    }

    /** 载荷 src 字段解析：正常/缺省/空串/非法载荷 */
    @Test
    fun payloadSrcExtractedWithFallback() {
        assertEquals("t7", WebThemeColorBridge.payloadSrc("""{"color":"#161616","src":"t7"}"""))
        assertEquals("t1", WebThemeColorBridge.payloadSrc("""{"color":"#161616"}"""))
        assertEquals("t1", WebThemeColorBridge.payloadSrc("""{"color":"#161616","src":""}"""))
        assertEquals("t1", WebThemeColorBridge.payloadSrc("not json"))
        assertEquals("t1", WebThemeColorBridge.payloadSrc(null))
    }
}
