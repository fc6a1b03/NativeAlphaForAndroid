package com.cylonid.nativealpha.util

import com.cylonid.nativealpha.util.StatsDailyStore.DayEntry
import com.cylonid.nativealpha.util.StatsDailyStore.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * 按日活动快照契约：连续周数计算（streak）。
 * Snapshot 为纯数据模型，streakWeeks 是 JVM 纯函数（周界由入参 Calendar 注入）。
 */
class StatsDailyStoreTest {

    /** 以固定「今天」构造测试基准（2026-09-04 周五） */
    private fun today(): Calendar = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 4)
    }

    private fun days(vararg dates: String): Snapshot =
        Snapshot(dates.associateWith { DayEntry(opens = 1) })

    @Test
    fun streakWeeks_emptySnapshotIsZero() {
        assertEquals(0, Snapshot().streakWeeks(today()))
    }

    @Test
    fun streakWeeks_countsContinuousActiveWeeks() {
        // 本周（08-30 周日起）+ 上周（08-23..29）各有一天活跃 → 连续 2 周
        assertEquals(2, days("2026-09-01", "2026-08-25").streakWeeks(today()))
    }

    @Test
    fun streakWeeks_gapBreaksChain() {
        // 上上周断档（只留 08-16）→ 连续止于 0
        assertEquals(0, days("2026-08-16").streakWeeks(today()))
    }

    @Test
    fun streakWeeks_inactiveCurrentWeekTolerated() {
        // 本周尚无活跃（周中视角）：从上周起算不破连续 → 上周+上上周 = 2
        assertEquals(2, days("2026-08-27", "2026-08-20").streakWeeks(today()))
    }

    @Test
    fun streakWeeks_singleActiveDayThisWeek() {
        assertEquals(1, days("2026-09-04").streakWeeks(today()))
    }
}
