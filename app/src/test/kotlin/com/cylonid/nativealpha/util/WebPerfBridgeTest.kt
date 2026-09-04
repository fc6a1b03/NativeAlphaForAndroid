package com.cylonid.nativealpha.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Web Vitals 采集桥纯函数契约：载荷解析容错（宁缺勿脏）+ JS 脚本锚点。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebPerfBridgeTest {

    @Test
    fun buildVitalsEntry_parsesAllFields() {
        val e = WebPerfBridge.buildVitalsEntry(
            """{"dns":12,"tcp":34,"ttfb":56,"fcp":120,"lcp":230,"domNodes":812}""",
            at = 42L
        )
        assertEquals(12, e?.dns)
        assertEquals(34, e?.tcp)
        assertEquals(56, e?.ttfb)
        assertEquals(120, e?.fcp)
        assertEquals(230, e?.lcp)
        assertEquals(812, e?.domNodes)
        assertEquals(42L, e?.at)
    }

    @Test
    fun buildVitalsEntry_missingFieldsDefaultZero() {
        val e = WebPerfBridge.buildVitalsEntry("""{"dns":5}""")
        assertEquals(5, e?.dns)
        assertEquals(0, e?.lcp)
    }

    @Test
    fun buildVitalsEntry_rejectsInvalidJson() {
        assertNull(WebPerfBridge.buildVitalsEntry("not json"))
        assertNull(WebPerfBridge.buildVitalsEntry(null))
        assertNull(WebPerfBridge.buildVitalsEntry(""))
    }

    @Test
    fun buildVitalsEntry_clampsAbsurdClocks() {
        // 负值归零、超大值钳到 10 分钟（异常时钟差防护）
        val e = WebPerfBridge.buildVitalsEntry("""{"dns":-5,"ttfb":99999999}""")
        assertEquals(0, e?.dns)
        assertEquals(600_000, e?.ttfb)
    }

    @Test
    fun perfJs_containsContractAnchors() {
        val js = WebPerfBridge.PERF_JS
        assertTrue(js.contains("__wnPerfInit"))
        assertTrue(js.contains("__wnPerfSent"))
        assertTrue(js.contains("webnativePerf.postMessage"))
        assertTrue(js.contains("largest-contentful-paint"))
        assertTrue(js.contains("navigation"))
    }
}
