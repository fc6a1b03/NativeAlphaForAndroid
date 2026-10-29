package com.cylonid.nativealpha.ui

import com.cylonid.nativealpha.model.WebApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 每次打开平均流量口径：分母优先级（打开次数 → 上报批次 → 0）。
 * Robolectric 跑：WebApp 构造依赖 Android 上下文相关工具。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatsTrafficMetricsTest {

    private fun webapp(
        traffic: Long = 0L,
        launches: Int = 0,
        samples: Int = 0
    ) = WebApp("https://example.com", 1).apply {
        statTrafficBytes = traffic
        statLaunches = launches
        statTrafficSamples = samples
    }

    @Test
    fun averageUsesLaunchCount() {
        // 1000 字节 / 4 次打开 = 250
        assertEquals(250L, perLaunchBytes(webapp(traffic = 1000L, launches = 4, samples = 9)))
    }

    @Test
    fun fallsBackToSamplesWhenNoLaunches() {
        // 无打开记录（历史数据或采集异常）时按上报批次均摊
        assertEquals(200L, perLaunchBytes(webapp(traffic = 1000L, launches = 0, samples = 5)))
    }

    @Test
    fun zeroWhenNoDenominator() {
        assertEquals(0L, perLaunchBytes(webapp(traffic = 1000L, launches = 0, samples = 0)))
    }

    @Test
    fun zeroWhenNoTraffic() {
        assertEquals(0L, perLaunchBytes(webapp(traffic = 0L, launches = 3, samples = 3)))
    }

    @Test
    fun integerDivisionFloors() {
        // 999 / 4 = 249（向下取整，不四舍五入）
        assertEquals(249L, perLaunchBytes(webapp(traffic = 999L, launches = 4, samples = 0)))
    }
}
