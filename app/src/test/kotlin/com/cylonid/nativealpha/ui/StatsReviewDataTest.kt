package com.cylonid.nativealpha.ui

import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.StatsDailyStore.DayEntry
import com.cylonid.nativealpha.util.StatsDailyStore.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

/**
 * 月度回顾聚合契约：活跃天数门槛 / 本月口径过滤 / 之最聚合。
 */
class StatsReviewDataTest {

    /** 固定「今天」：2026-09-04 */
    private fun today(): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 4)
    }

    private fun snapshot(vararg dateOpens: Pair<String, Int>): Snapshot =
        Snapshot(dateOpens.associate { (d, n) -> d to DayEntry(opens = n) })

    @Test
    fun activeDays_countsOnlyPositiveOpensAcrossWindow() {
        val daily = snapshot(
            "2026-09-01" to 3, "2026-09-02" to 0, "2026-09-03" to 1,
            "2026-08-30" to 9 // 90 天窗口内跨月均计入
        )
        assertEquals(3, ReviewData.activeDays(daily))
    }

    @Test
    fun build_returnsNullBelowSampleThreshold() {
        val webapp = WebApp("https://example.com", 0, 0)
        // 3 天活跃 < 7 天门槛
        val daily = snapshot("2026-09-01" to 2, "2026-09-02" to 2, "2026-09-03" to 2)
        assertNull(ReviewData.build(webapp, daily, 0, today()))
    }

    @Test
    fun build_aggregatesWholeWindow() {
        val webapp = WebApp("https://example.com", 0, 0).apply {
            statLoadTimes = mutableListOf(30L, 5L, 0L) // 0 为无效计时样本，应被 Fastest 忽略
            statLoadTimeSum = 40L
            statLoadTimeCount = 3
        }
        val daily = snapshot(
            "2026-09-01" to 2, "2026-09-02" to 5, "2026-09-03" to 1,
            "2026-09-04" to 2, "2026-09-05" to 4, "2026-09-06" to 1,
            "2026-09-07" to 1,
            "2026-08-20" to 99 // 上月高分不计入本月聚合
        )
        val review = ReviewData.build(webapp, daily, 12L, today())
        assertNotNull(review)
        assertEquals(115, review!!.totalOpens) // 2+5+1+0+4+1+1
        assertEquals(8, review!!.activeDays) // 0 次的 09-04 不算活跃
        assertEquals("2026-08-20", review!!.busiestDay)
        assertEquals(99, review!!.busiestDayOpens)
        assertEquals(5L, review!!.fastestLoad) // 0 值被过滤后的非零最快
        assertEquals(12L, review!!.notificationShown)
    }
}
