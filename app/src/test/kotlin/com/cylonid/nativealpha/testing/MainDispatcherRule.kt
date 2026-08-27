package com.cylonid.nativealpha.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * 协程测试主线程调度器替换：把 Dispatchers.Main 指向 StandardTestDispatcher，
 * 让依赖主线程调度的协程代码（ViewModel/StateFlow/lifecycleScope 相关）在
 * JVM 测试中确定序执行，配合 runTest 虚拟时钟 advanceUntilIdle 驱动。
 *
 * v2.2.0 测试基建（V2_2_0_PLAN §2.5）：P1 建立，P2 数据层异步化起消费。
 */
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
