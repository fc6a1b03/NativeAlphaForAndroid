package com.cylonid.nativealpha

import com.cylonid.nativealpha.util.SiteReconnectSupervisor
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * 断线自动恢复监督者（连接治理）：退避序列/可达判定/探测循环端到端
 * （probe 注入 + 虚拟时钟）+ 网络恢复信号插队 + stop 语义。
 *
 * 注：scope 传 runTest 的 TestScope 本体（backgroundScope 的调度在
 * 本项目协程测试配置下不随 advanceUntilIdle 推进，实测 x 恒 0）；
 * 生产代码传入 lifecycleScope/真实 scope，机制一致仅调度器不同。
 */
class SiteReconnectSupervisorTest {

    @Test
    fun backoff_exponentialWithCap() {
        assertEquals(5_000L, SiteReconnectSupervisor.backoffDelayMs(1))
        assertEquals(10_000L, SiteReconnectSupervisor.backoffDelayMs(2))
        assertEquals(20_000L, SiteReconnectSupervisor.backoffDelayMs(3))
        assertEquals(40_000L, SiteReconnectSupervisor.backoffDelayMs(4))
        assertEquals(60_000L, SiteReconnectSupervisor.backoffDelayMs(5))
        assertEquals(60_000L, SiteReconnectSupervisor.backoffDelayMs(50))
    }

    @Test
    fun reachable_anyHttpResponseMeansSiteAlive() {
        assertTrue(SiteReconnectSupervisor.isReachableCode(200))
        assertTrue(SiteReconnectSupervisor.isReachableCode(302))
        assertTrue(SiteReconnectSupervisor.isReachableCode(404)) // 404 也说明服务活着
        assertTrue(SiteReconnectSupervisor.isReachableCode(405)) // HEAD 被拒亦是
        assertFalse(SiteReconnectSupervisor.isReachableCode(-1)) // 连接异常
    }

    @Test
    fun probeLoop_autoRecoversAfterFailures() = runTest {
        var count = 0
        var recovered = ""
        val supervisor = SiteReconnectSupervisor(
            context = mock(android.content.Context::class.java),
            scope = this,
            probeFn = { _ ->
                count++
                if (count >= 3) 200 else -1
            }
        )
        supervisor.start("https://site.example") { recovered = it }
        advanceUntilIdle()

        assertEquals(3, count)
        assertEquals("https://site.example", recovered)
    }

    @Test
    fun networkAvailableSignal_interruptsBackoffImmediately() = runTest {
        var count = 0
        var recovered = false
        val supervisor = SiteReconnectSupervisor(
            context = mock(android.content.Context::class.java),
            scope = this,
            probeFn = { _ ->
                count++
                if (count >= 2) 200 else -1
            }
        )
        supervisor.start("https://site.example") { recovered = true }
        advanceTimeBy(1) // 首轮探测失败，进入 5s 退避
        assertEquals(1, count)
        // 网络恢复信号：不等 5s 退避，立即二轮探测
        supervisor.notifyNetworkAvailable()
        advanceUntilIdle()
        assertEquals(2, count)
        assertTrue(recovered)
    }

    @Test
    fun stop_cancelsRecovery() = runTest {
        var count = 0
        var recovered = false
        val supervisor = SiteReconnectSupervisor(
            context = mock(android.content.Context::class.java),
            scope = this,
            probeFn = { _ -> count++; -1 }
        )
        supervisor.start("https://site.example") { recovered = true }
        // 恒失败 probe + advanceUntilIdle = 无限退避无限推进（队列永不清空
        // 会挂死）——只推进 1ms 驱动首轮探测，退避等待保持挂起
        advanceTimeBy(1)
        assertEquals(1, count)
        // stop 取消退避中的监视协程：不再有后续探测（不二次 advance——
        // 已取消协程无需驱动，避免与全量运行时的清理时序耦合）
        supervisor.stop()
        assertFalse(recovered)
    }
}
