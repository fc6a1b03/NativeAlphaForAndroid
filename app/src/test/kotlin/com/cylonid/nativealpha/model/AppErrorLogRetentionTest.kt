package com.cylonid.nativealpha.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断日志超上限逐出策略单测（AppErrorLogRepository.applyRetention 纯函数）：
 * v2.4.8 实锤——探针洪泛时 200 条配额 68% 是 page_load_start，纯先进先出会把
 * 真错误/崩溃挤出 3 天保留窗口；策略必须「先丢最旧 INFO，仍超再丢最旧」。
 */
class AppErrorLogRetentionTest {

    private fun entry(time: Long, level: String) =
        AppErrorEntry(time = time, level = level, tag = "T", message = "m$time")

    @Test
    fun `under limit unchanged`() {
        val list = listOf(entry(1, "INFO"), entry(2, "ERROR"))
        assertEquals(list, AppErrorLogRepository.applyRetention(list, 10))
    }

    @Test
    fun `oldest info dropped first`() {
        val list = listOf(
            entry(1, "INFO"),   // 最旧 INFO → 先逐出
            entry(2, "INFO"),
            entry(3, "ERROR"),
            entry(4, "CRASH")
        )
        val kept = AppErrorLogRepository.applyRetention(list, 3)
        assertEquals(listOf(2L, 3L, 4L), kept.map { it.time })
    }

    @Test
    fun `falls back to oldest overall when info exhausted`() {
        val list = listOf(
            entry(1, "ERROR"),
            entry(2, "ERROR"),
            entry(3, "INFO"),
            entry(4, "ERROR")
        )
        val kept = AppErrorLogRepository.applyRetention(list, 2)
        // 唯一 INFO 先丢，再丢最旧 ERROR(1)
        assertEquals(listOf(2L, 4L), kept.map { it.time })
    }

    @Test
    fun `output stays time ordered`() {
        val list = (1..30L).map { entry(it, if (it % 2 == 0L) "INFO" else "ERROR") }
        val kept = AppErrorLogRepository.applyRetention(list, 10)
        assertEquals(10, kept.size)
        assertEquals(kept.sortedBy { it.time }, kept)
        // 逐出后不应剩 INFO（30 条砍半，INFO 足额可丢）
        assertTrue(kept.none { it.level == "INFO" })
    }

    @Test
    fun `info kept when enough capacity`() {
        val list = listOf(entry(1, "INFO"), entry(2, "ERROR"), entry(3, "INFO"))
        assertEquals(list, AppErrorLogRepository.applyRetention(list, 3))
    }
}
