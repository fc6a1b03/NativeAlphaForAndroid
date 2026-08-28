package com.cylonid.nativealpha.matrix

/**
 * 崩溃退避策略（P4 D3/A 方案，纯函数核心）。
 *
 * 共享渲染进程下崩溃全窗同死；恢复体验=批量静默重载。防 OOM 死循环：
 * 时间窗内崩溃达到阈值即停止自动恢复（保持错误态 + 顶栏提示），用户
 * 手动点击重试重置计数（给一次新机会，且手动行为自带冷却）。
 */
internal class MatrixCrashBackoff(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxCrashes: Int = DEFAULT_MAX_CRASHES
) {

    private val crashTimestampsMs = ArrayDeque<Long>()

    /**
     * 登记一次崩溃。
     * @return true=允许自动静默重载；false=退避生效（停手保持错误态）。
     * 语义：窗口内崩溃计数达到阈值即停手——第 2 次（≥2 次/30s）不再自动恢复
     */
    fun onCrash(nowMs: Long): Boolean {
        crashTimestampsMs.addLast(nowMs)
        while (crashTimestampsMs.isNotEmpty() && nowMs - crashTimestampsMs.first() > windowMs) {
            crashTimestampsMs.removeFirst()
        }
        return crashTimestampsMs.size < maxCrashes
    }

    /** 用户手动点击重试：重置退避计数（D3 决策） */
    fun onManualRetry() = crashTimestampsMs.clear()

    companion object {
        const val DEFAULT_WINDOW_MS = 30_000L
        const val DEFAULT_MAX_CRASHES = 2
    }
}
