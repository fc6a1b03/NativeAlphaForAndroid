package com.cylonid.nativealpha.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 站点主题色桥采集层契约（2026-11-07 重构后：JS 只做信号采集，判定全部
 * 下沉 [WebPageColorPolicy]——其全形态矩阵见 WebPageColorPolicyTest）。
 */
class WebThemeColorBridgeTest {

    @Test
    fun themeJsCollectsRawSignalsOnly() {
        val js = WebThemeColorBridge.THEME_JS
        // 全量采集 meta（querySelector 单条命中是 v2.4.12 白栏根因，禁止回归）
        assertEquals(true, js.contains("querySelectorAll('meta[name=\"theme-color\"]')"))
        assertEquals(false, js.contains("querySelector('meta[name=\"theme-color\"]')"))
        assertEquals(true, js.contains("colorScheme"))
        assertEquals(true, js.contains("backgroundColor"))
    }

    @Test
    fun themeJsContainsNoJudgmentLogic() {
        // 判定下沉 Kotlin（WebPageColorPolicy）——JS 里禁止再出现判定/归一化
        // 逻辑（六版反复翻车的结构性根源即「JS 判定不可单测」）
        val js = WebThemeColorBridge.THEME_JS
        assertEquals(false, js.contains("darkVal"))
        assertEquals(false, js.contains("lightVal"))
        assertEquals(false, js.contains("0.299"))
        assertEquals(false, js.contains("function normalize"))
        assertEquals(false, js.contains("actualScheme"))
    }

    @Test
    fun themeJsObserverReadsSynchronously() {
        // 内核怪癖（2026-11-07 真机定谳）：WebView 131 上 observer callback
        // 内注册的 setTimeout 永不执行（CDP 实证 cb:1/inner:0）——callback
        // 必须同步直读，禁止 setTimeout 形态防抖回归
        val js = WebThemeColorBridge.THEME_JS
        assertEquals(true, js.contains("new MutationObserver(function(){read('t2');})"))
        assertEquals(false, js.contains("function schedule()"))
        // 挂载重试的 setTimeout 保留（启动期执行，不在 callback 内）
        assertEquals(true, js.contains("setTimeout(startObserver,200)"))
    }

    @Test
    fun themeJsObserversCoverHtmlBodyHead() {
        val js = WebThemeColorBridge.THEME_JS
        // F5：body class 是 Discourse 系切主题的载体，漏看会栏色滞留
        assertEquals(true, js.contains("obHtml.observe(document.documentElement,{attributes:true,subtree:false"))
        assertEquals(true, js.contains("obBody.observe(document.body,{attributes:true,subtree:false"))
        assertEquals(true, js.contains("obHead.observe(document.head"))
        // body 内元素零监听（渲染性能红线）
        assertEquals(false, js.contains("obBody.observe(document.body,{childList"))
    }

    /**
     * 样式表 load 监听钉（2026-11-07 真机定谳「滑动列表才变色」的修复）：
     * Discourse 切配色=替换/追加 link[rel=stylesheet]，新 CSS 经网络下载
     * 应用——「应用完成」不是 DOM 变化，MutationObserver 全盲。read 必须
     * 为每个未挂监听的 stylesheet 挂 load（CSS 应用完成即重读）。
     * **此块曾在重构中被覆盖丢失（用户红线：新方案不得遗漏旧逻辑），
     * 本测试防再丢。**
     */
    @Test
    fun themeJsBindStylesheetLoad() {
        val js = WebThemeColorBridge.THEME_JS
        assertEquals(true, js.contains("link[rel~=\"stylesheet\"]"))
        assertEquals(true, js.contains("__wnBound"))
        assertEquals(true, js.contains("addEventListener('load',function(){read('t2');})"))
    }

    @Test
    fun themeJsHasAllTriggerSources() {
        val js = WebThemeColorBridge.THEME_JS
        assertEquals(true, js.contains("__wnThemeReread"))
        assertEquals(true, js.contains("addEventListener('load'"))
        assertEquals(true, js.contains("visibilitychange"))
        // t7 强制上报（绕过信号变化去重）——宿主踢必有回音，活性可确认
        assertEquals(true, js.contains("src!=='t7'"))
        assertEquals(true, js.contains("src:src||'t1'"))
    }

    // ===== 载荷解析 =====

    @Test
    fun parseSignals_validPayload() {
        val s = WebThemeColorBridge.parseSignals(
            """{"metas":[{"c":"#fff","m":"(prefers-color-scheme: light)"},
               {"c":"#111111","m":"(prefers-color-scheme: dark)"}],
               "cs":"dark","bg":"rgb(1, 2, 3)","bg2":"rgba(0, 0, 0, 0)","src":"t2"}"""
        )
        assertEquals(2, s.policy.metas.size)
        assertEquals("#fff", s.policy.metas[0].content)
        assertEquals("(prefers-color-scheme: dark)", s.policy.metas[1].media)
        assertEquals("dark", s.policy.colorScheme)
        assertEquals("rgb(1, 2, 3)", s.policy.htmlBg)
        assertEquals("rgba(0, 0, 0, 0)", s.policy.bodyBg)
    }

    @Test
    fun parseSignals_deepBgPayload() {
        val s = WebThemeColorBridge.parseSignals(
            "{\"metas\":[],\"cs\":\"\",\"bg\":\"rgba(0, 0, 0, 0)\",\"bg2\":\"rgba(0, 0, 0, 0)\",\"bg3\":\"rgb(34, 34, 34)\",\"src\":\"t1\"}"
        )
        assertEquals("rgb(34, 34, 34)", s.policy.deepBg)
    }

    @Test
    fun parseSignals_emptyAndInvalidPayloads() {
        assertEquals(0, WebThemeColorBridge.parseSignals(null).policy.metas.size)
        assertEquals(0, WebThemeColorBridge.parseSignals("").policy.metas.size)
        assertEquals(0, WebThemeColorBridge.parseSignals("junk").policy.metas.size)
        assertEquals(0, WebThemeColorBridge.parseSignals("{}").policy.metas.size)
    }

    @Test
    fun payloadSrc_extraction() {
        assertEquals("t7", WebThemeColorBridge.payloadSrc("""{"src":"t7"}"""))
        assertEquals("t1", WebThemeColorBridge.payloadSrc("{}"))
        assertEquals("t1", WebThemeColorBridge.payloadSrc("junk"))
        assertEquals("t1", WebThemeColorBridge.payloadSrc(null))
    }
}
