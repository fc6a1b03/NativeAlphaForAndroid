package com.cylonid.nativealpha.ui

import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计洞察引擎纯函数契约：规则触发/权重排序/plurals 契约/诚实性边界。
 * 全部 JVM 纯函数测试（无 Android 依赖——Insight 只持资源 ID 不持文案）。
 */
class StatsInsightsTest {

    private fun webapp(
        launches: Int = 0,
        loadSum: Long = 0L,
        loadCount: Int = 0,
        cacheBytes: Long = 0L,
        errors: Int = 0,
        firstLoadedAt: Long = 0L
    ): WebApp = WebApp("https://example.com", 0, 0).apply {
        statLaunches = launches
        statLoadTimeSum = loadSum
        statLoadTimeCount = loadCount
        statCacheHttpBytes = cacheBytes
        statErrors = errors
        statFirstLoadedAt = firstLoadedAt
    }

    private fun insights(webapp: WebApp, automation: Map<String, Long> = emptyMap()) =
        buildInsights(InsightContext(webapp, automation))

    @Test
    fun emptyData_yieldsNoInsights() {
        assertTrue(insights(webapp()).isEmpty())
    }

    @Test
    fun smoothLoad_producesSpeedInsight() {
        val list = insights(webapp(loadSum = 300L, loadCount = 10)) // 均 30ms
        val insight = list.first { it.textRes == R.string.insight_speed_smooth }
        assertEquals(listOf("30ms"), insight.args)
    }

    @Test
    fun midLoad_yieldsNoSpeedInsight() {
        val list = insights(webapp(loadSum = 10_000L, loadCount = 10)) // 均 1s（不在「无感」也不在「慢载」档）
        assertTrue(list.none { it.textRes == R.string.insight_speed_smooth })
        assertTrue(list.none { it.textRes == R.string.suggestion_slow_load })
    }

    @Test
    fun slowLoad_producesSuggestionWithTopWeight() {
        val list = insights(webapp(loadSum = 40_000L, loadCount = 10, cacheBytes = 1024L)) // 均 4s
        assertEquals(R.string.suggestion_slow_load, list.first().textRes)
    }

    @Test
    fun notifications_producePluralInsight() {
        val list = insights(webapp(launches = 1), automation = mapOf("notification_shown" to 7L))
        val insight = list.first { it.pluralsRes == R.plurals.insight_notification }
        assertEquals(7, insight.count)
    }

    @Test
    fun cache_producesCacheInsight() {
        val list = insights(webapp(cacheBytes = 505L * 1024))
        val insight = list.first { it.textRes == R.string.insight_cache }
        assertEquals(listOf("505.0 KB"), insight.args)
    }

    @Test
    fun sortedByWeightDescending() {
        val list = insights(
            webapp(loadSum = 40_000L, loadCount = 10, cacheBytes = 1024L),
            automation = mapOf("notification_shown" to 7L)
        )
        assertEquals(list.sortedByDescending { it.weight }, list)
    }

    @Test
    fun daysTogether_countsInclusiveDays() {
        val now = System.currentTimeMillis()
        // 2 天前首次使用 → 相伴 3 天（含当天）
        assertEquals(3, daysTogether(webapp(firstLoadedAt = now - 2L * 24 * 60 * 60 * 1000)))
        assertEquals(0, daysTogether(webapp(firstLoadedAt = 0L)))
    }
}
