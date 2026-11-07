package com.cylonid.nativealpha.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * WebView 页面主题色判定策略全形态矩阵（2026-11-07 重构后判定下沉 Kotlin，
 * 首次可 JVM 穷举——此前判定在注入 JS 里只能靠真机像素验证，六版反复翻车
 * 的结构性根源）。用例编号对应各轮实测定谳形态。
 */
class WebPageColorPolicyTest {

    private fun meta(content: String?, media: String?) = WebPageColorPolicy.MetaEntry(content, media)
    private val doubleMeta = listOf(
        meta("#fff", "(prefers-color-scheme: light)"),
        meta("#111111", "(prefers-color-scheme: dark)")
    )

    // ===== S1：声明匹配（colorScheme 明确） =====

    @Test
    fun s1_darkSchemePicksDarkMeta() {
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(doubleMeta, "dark", "rgb(34, 34, 34)", null)
        )
        assertEquals(0xFF111111.toInt(), argb)
    }

    @Test
    fun s1_lightSchemePicksLightMeta() {
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(doubleMeta, "light", "rgb(255, 255, 255)", null)
        )
        assertEquals(0xFFFFFFFF.toInt(), argb)
    }

    @Test
    fun s1_firstMatchWinsNotLast() {
        // Chromium blink/HTML 规范语义：树序第一条命中者胜，后命中不覆盖
        val metas = listOf(
            meta("#111111", "(prefers-color-scheme: dark)"),
            meta("#222222", "(prefers-color-scheme: dark)")
        )
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(metas, "dark", "rgb(34, 34, 34)", null)
        )
        assertEquals(0xFF111111.toInt(), argb)
    }

    @Test
    fun s1_ambiguousSchemeIsNotASignal() {
        // 二义 "light dark" 不算信号——回落 S2（背景亮度），不按系统猜
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(doubleMeta, "light dark", "rgb(34, 34, 34)", null)
        )
        assertEquals(0xFF111111.toInt(), argb)
    }

    // ===== S2-a：有声明但未命中 → 背景亮度选条 =====

    @Test
    fun s2_noSchemeDarkBgUsesDarkVal() {
        // linux.do 报障形态（v2.4.12/13 白栏）：系统浅+页面深+双条声明
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(doubleMeta, null, "rgb(34, 34, 34)", null)
        )
        assertEquals(0xFF111111.toInt(), argb)
    }

    @Test
    fun s2_noSchemeLightBgUsesLightVal() {
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(doubleMeta, null, "rgb(255, 255, 255)", null)
        )
        assertEquals(0xFFFFFFFF.toInt(), argb)
    }

    @Test
    fun s2_midBandDarkSideUsesDarkVal() {
        // 0.3~0.45：有 darkVal 才跟随
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(doubleMeta, null, "rgb(80, 80, 80)", null)
        )
        assertEquals(0xFF111111.toInt(), argb)
    }

    @Test
    fun s2_midBandLightSideUsesLightVal() {
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(doubleMeta, null, "rgb(200, 200, 200)", null)
        )
        assertEquals(0xFFFFFFFF.toInt(), argb)
    }

    // ===== S2-b：完全没有声明（v2.4.14 linux.do 实锤形态） =====

    @Test
    fun s2_noMetaDarkBgAppliesBackground() {
        // 口径变更核心：无任何 meta + 极暗背景(<0.3) → 直接按背景上栏
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(emptyList(), null, "rgb(34, 34, 34)", null)
        )
        assertEquals(0xFF222222.toInt(), argb)
    }

    @Test
    fun s2_noMetaBrightBgAppliesBackground() {
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(emptyList(), "light", "rgb(255, 255, 255)", null)
        )
        assertEquals(0xFFFFFFFF.toInt(), argb)
    }

    @Test
    fun s2_noMetaMidGrayDoesNotInterfere() {
        // 0.3~0.7 中间带且无声明条目：不干预（误判风险集中区）
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(emptyList(), null, "rgb(128, 128, 128)", null)
        )
        assertNull(argb)
    }

    @Test
    fun s2_bodyBgUsedWhenHtmlTransparent() {
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(
                doubleMeta, null,
                "rgba(0, 0, 0, 0)", "rgb(17, 17, 17)"
            )
        )
        assertEquals(0xFF111111.toInt(), argb)
    }

    @Test
    fun s2_deepContainerBgUsedWhenHtmlBodyTransparent() {
        // linux.do 登录深色页实锤形态（v2.4.14 theme_signal color=none）：
        // 无 meta + html/body 全透明 + 深色涂在深层容器——S2 深挖链命中
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(
                emptyList(), null,
                "rgba(0, 0, 0, 0)", "rgba(0, 0, 0, 0)",
                deepBg = "rgb(34, 34, 34)"
            )
        )
        assertEquals(0xFF222222.toInt(), argb)
    }

    @Test
    fun s2_innermostBgWinsOverOuter() {
        // 深层容器（最内层）背景优先于 body/html——视觉最贴近页面渲染
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(
                emptyList(), null,
                "rgb(255, 255, 255)", "rgb(128, 128, 128)",
                deepBg = "rgb(34, 34, 34)"
            )
        )
        assertEquals(0xFF222222.toInt(), argb)
    }

    @Test
    fun s2_transparentBgOnlyFallsThrough() {
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(doubleMeta, null, "rgba(0, 0, 0, 0)", null)
        )
        assertNull(argb)
    }

    @Test
    fun s2_semiTransparentBgFallsThrough() {
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(doubleMeta, null, "rgba(34, 34, 34, 0.5)", null)
        )
        assertNull(argb)
    }

    // ===== fallback：单条无 media（zcode 形态） =====

    @Test
    fun fallback_singleMetaWinsOverBackground() {
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(
                listOf(meta("#161616", null)), null, "rgb(34, 34, 34)", null
            )
        )
        assertEquals(0xFF161616.toInt(), argb)
    }

    @Test
    fun fallback_invalidContentIsSkipped() {
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(
                listOf(meta("transparent", null), meta("#161616", null)),
                null, "rgb(34, 34, 34)", null
            )
        )
        assertEquals(0xFF161616.toInt(), argb)
    }

    @Test
    fun s3_allEmptySignalsDoNotInterfere() {
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(emptyList(), null, null, null)
        )
        assertNull(argb)
    }

    @Test
    fun s3_semiTransparentMetaContentRejected() {
        // 栏色必须不透明：rgba alpha<1 的 content 视为无效
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(
                listOf(meta("rgba(34, 34, 34, 0.5)", null)), null, "rgb(34, 34, 34)", null
            )
        )
        // content 无效 → 无 fallback → S2 背景兜底
        assertEquals(0xFF222222.toInt(), argb)
    }

    // ===== normalize 细节 =====

    @Test
    fun normalize_threeDigitHex() {
        assertEquals("#aabbcc", WebPageColorPolicy.normalizeHex("#abc"))
    }

    @Test
    fun normalize_rgbFunction() {
        assertEquals("#161616", WebPageColorPolicy.normalizeHex("rgb(22, 22, 22)"))
    }

    @Test
    fun normalize_namedColorRejected() {
        assertNull(WebPageColorPolicy.normalizeHex("black"))
    }

    @Test
    fun s1_mediaWordBoundaries() {
        // media 词边界：'dark-alike' 不得匹配 dark
        val metas = listOf(
            meta("#333333", "(prefers-color-scheme: dark-alike)"),
            meta("#ffffff", "(prefers-color-scheme: light)")
        )
        val argb = WebPageColorPolicy.resolve(
            WebPageColorPolicy.ColorSignals(metas, "dark", "rgb(34, 34, 34)", null)
        )
        // dark-alike 含 'dark' 子串但词边界正则拒之——无 darkVal，S2 背景兜底
        assertEquals(0xFF222222.toInt(), argb)
    }

    // ===== parseSignals =====

    @Test
    fun parseSignals_validPayload() {
        val s = WebThemeColorBridge.parseSignals(
            """{"metas":[{"c":"#fff","m":"(prefers-color-scheme: light)"}],
               "cs":"dark","bg":"rgb(1, 2, 3)","bg2":"","src":"t7"}"""
        )
        assertEquals(1, s.policy.metas.size)
        assertEquals("#fff", s.policy.metas[0].content)
        assertEquals("dark", s.policy.colorScheme)
        assertEquals("rgb(1, 2, 3)", s.policy.htmlBg)
        assertNull(s.policy.bodyBg)
    }

    @Test
    fun parseSignals_invalidPayloadFallsBackEmpty() {
        val s = WebThemeColorBridge.parseSignals("not json")
        assertEquals(0, s.policy.metas.size)
        assertNull(WebPageColorPolicy.resolve(s.policy))
    }

    @Test
    fun payloadSrc_extraction() {
        assertEquals("t7", WebThemeColorBridge.payloadSrc("""{"src":"t7"}"""))
        assertEquals("t1", WebThemeColorBridge.payloadSrc("{}"))
        assertEquals("t1", WebThemeColorBridge.payloadSrc("junk"))
        assertEquals("t1", WebThemeColorBridge.payloadSrc(null))
    }
}
