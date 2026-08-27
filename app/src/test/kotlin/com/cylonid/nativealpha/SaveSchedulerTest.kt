package com.cylonid.nativealpha

import com.cylonid.nativealpha.model.SaveScheduler
import com.cylonid.nativealpha.model.WebApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SaveScheduler 行为契约（P2 数据层）：debounce 合并 / 最新胜出 / 窗口分隔。
 * 全部走 runTest 虚拟时钟——生产路径不注入任何测试后门。
 *
 * 实测坑（coroutines-test 1.10.2）：backgroundScope 协程首个挂起点后不被
 * advanceUntilIdle 恢复——消费循环必须以 TestScope 直接子协程启动（scope=this），
 * 收尾显式 cancel loopJob 防 runTest 等待超时。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveSchedulerTest {

    @Test
    fun `burst of 5 snapshots coalesces into single persist of latest value`() = runTest {
        val persisted = mutableListOf<Int>()
        val scheduler = SaveScheduler(this, 500) { snapshot ->
            persisted.add(snapshot.sumOf { it.ID })
        }
        repeat(5) { i -> scheduler.submit(listOf(WebApp("https://a.com", i, i))) }
        advanceUntilIdle()
        assertEquals(1, persisted.size)
        // 合并后只落最新快照（ID=4 的单站点列表）
        assertEquals(4, persisted.single())
        scheduler.loopJob.cancel()
    }

    @Test
    fun `snapshots separated by debounce window persist separately`() = runTest {
        val persisted = mutableListOf<Int>()
        val scheduler = SaveScheduler(this, 500) { snapshot ->
            persisted.add(snapshot.sumOf { it.ID })
        }
        scheduler.submit(listOf(WebApp("https://a.com", 1, 0)))
        // 推进 600ms：首个合并窗口关闭并落盘
        advanceTimeBy(600)
        scheduler.submit(listOf(WebApp("https://b.com", 2, 0)))
        advanceUntilIdle()
        assertEquals(listOf(1, 2), persisted)
        scheduler.loopJob.cancel()
    }

    @Test
    fun `latest snapshot wins inside debounce window`() = runTest {
        var lastPersistedSize = 0
        val scheduler = SaveScheduler(this, 500) { snapshot ->
            lastPersistedSize = snapshot.size
        }
        scheduler.submit(listOf(WebApp("https://a.com", 1, 0)))
        scheduler.submit(
            listOf(
                WebApp("https://a.com", 1, 0),
                WebApp("https://b.com", 2, 1)
            )
        )
        advanceUntilIdle()
        // 首个快照被 CONFLATED 合并，落盘的只有两站点的最新快照
        assertEquals(2, lastPersistedSize)
        scheduler.loopJob.cancel()
    }
}
