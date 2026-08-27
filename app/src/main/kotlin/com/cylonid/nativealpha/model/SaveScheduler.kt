package com.cylonid.nativealpha.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 异步保存调度器（P2 数据层收口的持久化核心）。
 *
 * 链路：调用方快照投递 → CONFLATED channel（最新胜出）→ debounce 合并窗口 →
 * 消费协程（L3 串行）执行 persist。Gson 序列化与 SP 落盘全部发生在消费协程，
 * 提交方只承担 µs 级的引用列表拷贝——主线程从此零序列化（损耗红线）。
 *
 * 为什么独立 internal 类：debounce 合并语义需要虚拟时钟可测（runTest），
 * DataManager 的保存线程是真实 IO——抽出后测试注入 TestScope 即可穷举
 * 「N 发 1 落 / 最新胜出」行为，生产路径零反射、零测试后门。
 */
internal class SaveScheduler(
    private val scope: CoroutineScope,
    private val debounceMs: Long,
    private val persist: suspend (List<WebApp>) -> Unit
) {

    private val channel = Channel<List<WebApp>>(Channel.CONFLATED)

    /** 消费循环 Job：测试收尾显式 cancel（runTest 不自动取消直接子协程）；
     * 生产路径随 saveScope 生命周期，无需手动管理 */
    val loopJob: Job = scope.launch {
        while (isActive) {
            // 挂起等首个快照；窗口期内的后续快照被 CONFLATED 合并，
            // delay 结束后 tryReceive 取最新——5 发 1 落、最新胜出
            val first = channel.receive()
            delay(debounceMs)
            val latest = channel.tryReceive().getOrNull() ?: first
            persist(latest)
        }
    }

    /** 提交快照（任意线程安全；CONFLATED 无挂起、永不阻塞调用方） */
    fun submit(snapshot: List<WebApp>) {
        channel.trySend(snapshot)
    }
}
