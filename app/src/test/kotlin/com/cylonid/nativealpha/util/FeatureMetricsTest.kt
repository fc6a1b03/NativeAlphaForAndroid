package com.cylonid.nativealpha.util

import com.cylonid.nativealpha.util.FeatureMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FeatureMetrics 观测门面行为测试（P3.5）：
 * 聚合、阈值触发落盘（可注入 persister 捕获）、reportError 永不抛。
 * 各测试用独立 module 名隔离 object 跨用例状态（不依赖执行顺序）。
 */
class FeatureMetricsTest {

    @Test
    fun `count aggregates per module and event`() {
        FeatureMetrics.count("t1mod", "evt")
        FeatureMetrics.count("t1mod", "evt")
        FeatureMetrics.count("t1mod", "other")
        assertEquals(2L, FeatureMetrics.moduleSnapshot("t1mod")["evt"])
        assertEquals(1L, FeatureMetrics.moduleSnapshot("t1mod")["other"])
        // 模块隔离：t1mod 的计数不得泄漏到其他模块视图
        assertTrue(FeatureMetrics.moduleSnapshot("unrelated").isEmpty())
    }

    @Test
    fun `count triggers persist exactly at threshold with full snapshot`() {
        val persisted = mutableListOf<Pair<String, Map<String, Long>>>()
        val original = FeatureMetrics.persister
        FeatureMetrics.persister = { module, snapshot -> persisted.add(module to snapshot) }
        try {
            val threshold = FeatureMetrics.flushThreshold
            repeat(threshold) { FeatureMetrics.count("t2mod", "evt") }
            // 阈值恰好触发一次，快照为该模块全量计数
            assertEquals(1, persisted.size)
            assertEquals("t2mod", persisted[0].first)
            assertEquals((threshold).toLong(), persisted[0].second["evt"])
            // 阈值后单次计数不再触发（节流生效）
            FeatureMetrics.count("t2mod", "evt")
            assertEquals(1, persisted.size)
        } finally {
            FeatureMetrics.persister = original
        }
    }

    @Test
    fun `reportError never throws even with broken sink`() {
        // 透传宿主 ErrorReporter（Robolectric 外无 android 上下文也能安全返回——
        // reportError 内部 catch 全部异常，观测通道永不阻塞主功能）
        FeatureMetrics.reportError("t3mod", "Where", "message", RuntimeException("boom"))
    }
}
