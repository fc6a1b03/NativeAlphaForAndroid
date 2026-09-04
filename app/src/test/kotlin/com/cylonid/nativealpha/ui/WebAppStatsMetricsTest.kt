package com.cylonid.nativealpha.ui

import com.cylonid.nativealpha.model.WebApp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 统计图表纯函数契约：分桶边界 / 最快加载真实值 / 次数缩写格式化。
 */
class WebAppStatsMetricsTest {

    @Test
    fun bucketize_splitsAtBoundaries() {
        // 边界：999/1000 落不同桶；5000 整落末桶（5s+ 开区间）
        val buckets = bucketize(listOf(999L, 1000L, 1500L, 4999L, 5000L, 9000L))
        // <1s=1, 1-2s=2, 2-3s=0, 3-5s=1, 5s+=2
        assertEquals(listOf(1, 2, 0, 1, 2).toIntArray().toList(), buckets.toList())
    }

    @Test
    fun bucketize_emptyReturnsAllZeros() {
        assertEquals(listOf(0, 0, 0, 0, 0).toIntArray().toList(), bucketize(emptyList()).toList())
    }

    @Test
    fun minLoadTime_returnsRealMinimum() {
        // 技术债清偿守卫：旧实现用平均值冒充 Fastest，这里锁死真实最小值语义
        val webapp = WebApp("https://example.com", 0, 0).apply {
            statLoadTimes = mutableListOf(4200L, 30L, 8000L)
        }
        assertEquals(30L, minLoadTime(webapp))
    }

    @Test
    fun minLoadTime_emptyReturnsZero() {
        assertEquals(0L, minLoadTime(WebApp("https://example.com", 0, 0)))
    }

    @Test
    fun formatCount_abbreviatesLargeNumbers() {
        assertEquals("999", formatCount(999))
        assertEquals("1.0K", formatCount(1000))
        assertEquals("1.2K", formatCount(1234))
        assertEquals("1.5M", formatCount(1_500_000L))
    }
}
