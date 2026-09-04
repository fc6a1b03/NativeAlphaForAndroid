package com.cylonid.nativealpha.util

import android.content.Context
import android.util.Log
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.ErrorType
import com.cylonid.nativealpha.model.PageErrorRepository
import com.cylonid.nativealpha.model.WebApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 统计埋点异步抽象（Phase 4.6）。
 *
 * 设计原则：
 * - 绝不阻塞主线程：全部走独立单线程队列（守护线程）
 * - 与主功能解耦：埋点失败/异常不影响页面加载、导航、交互
 * - 生命周期安全：不持有 Activity/View 强引用（弱引用 + 应用上下文）
 * - 防数据遗漏：内存缓冲 + 失败重试 + onPause/onDestroy 兜底 flush
 *
 * 线程模型：单线程队列（串行执行，无并发竞争），任务内 try-catch 兜底。
 */
object StatsRecorder {

    /** 加载耗时明细上限（最近 N 次，分布图数据源） */
    private const val STAT_LOAD_TIMES_LIMIT = 20

    // 单线程队列（守护线程：进程结束自动退出，不阻塞）
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "stats-recorder").apply { isDaemon = true }
    }

    // 协程作用域（IO 线程，SupervisorJob 防子任务取消级联）
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 应用上下文（弱引用防泄漏；Application 生命周期长，实际不会回收）
    private val appContext: Context? get() = App.getAppContext()

    /**
     * 所有埋点统一入口：异步、安全、不抛异常。
     * 任务失败静默（不影响主功能），数据保留内存缓冲由后续重试。
     */
    fun record(task: Runnable) {
        try {
            executor.execute {
                try {
                    task.run()
                } catch (e: Exception) {
                    // 埋点任务异常：静默（不影响主功能）
                    Log.w("StatsRecorder", "tracking task failed", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            // 队列关闭/异常：丢弃本次埋点
        }
    }

    /**
     * 协程埋点（读/写 DataStore 等挂起操作）。同样异步安全。
     */
    fun recordSuspend(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                // 埋点异常静默
                Log.w("StatsRecorder", "suspend tracking failed", e)
            }
        }
    }

    /**
     * 记录页面打开：打开次数 +1、首次/最近使用时间。
     * 调用点：WebViewActivity.onCreate。
     */
    fun recordLaunch(webappId: Int) {
        record {
            updateStats(webappId) { w ->
                w.statLaunches++
                val now = System.currentTimeMillis()
                if (w.statFirstLoadedAt == 0L) w.statFirstLoadedAt = now
                w.statLastUsedAt = now
            }
            // 按日快照（热力图/streak/时段洞察数据源）；同队列串行，失败静默不影响主功能
            recordSuspend { StatsDailyStore.appendOpen(App.getAppContext()) }
        }
    }

    /**
     * 记录主体加载完成耗时（ms）。调用点：onPageFinished。
     */
    fun recordPageLoaded(webappId: Int, loadMs: Long) {
        record {
            updateStats(webappId) { w ->
                w.statLoadTimeSum += loadMs
                w.statLoadTimeCount++
                if (loadMs > w.statMaxLoadTime) w.statMaxLoadTime = loadMs
                w.statLastUsedAt = System.currentTimeMillis()
                // 耗时明细（最近 20 次，超出丢最旧——分布图数据源）
                val times = w.statLoadTimes + loadMs
                w.statLoadTimes = times.takeLast(STAT_LOAD_TIMES_LIMIT).toMutableList()
            }
        }
    }

    /**
     * 记录页面错误。调用点：onReceivedError/onReceivedHttpError/SSL/RenderGone。
     */
    fun recordPageError(webappId: Int, type: String, code: String, description: String) {
        record {
            if (!shouldRecordPageError(type, isNetworkLoggingEnabled())) return@record
            updateStats(webappId) { w ->
                w.statErrors++
                w.statLastError = "$type($code): $description"
                w.statLastUsedAt = System.currentTimeMillis()
            }
            // 页面错误明细写入 DataStore（KEY_PAGE_ERRORS，按站）
            recordSuspend {
                PageErrorRepository.append(appContext ?: return@recordSuspend, webappId, type, code, description)
            }
        }
    }

    /**
     * 页面错误记录判定（纯函数，可单测）。
     * 网络类默认不记录：断网/DNS 失败属环境观测非站点缺陷（用户定调为噪音），
     * 开启 statLogNetworkErrors 后恢复完整记录。
     */
    internal fun shouldRecordPageError(type: String, networkLoggingEnabled: Boolean): Boolean {
        if (type == ErrorType.NETWORK.name && !networkLoggingEnabled) return false
        return true
    }

    /** 后台线程读取设置（executor 内调用，与 updateStats 同范式） */
    private fun isNetworkLoggingEnabled(): Boolean =
        try {
            DataManager.getInstance().settings.statLogNetworkErrors
        } catch (e: Exception) {
            // 设置未初始化等异常：按默认口径（不记录网络类），不阻塞埋点
            false
        }

    /**
     * 记录快捷键发送次数（统计反馈：面板/统计页显示使用频率）。
     */
    fun recordShortcutSent(webappId: Int, shortcut: String) {
        record {
            updateStats(webappId) { w ->
                val counts = w.keyShortcutSendCounts.toMutableMap()
                counts[shortcut] = (counts[shortcut] ?: 0) + 1
                w.keyShortcutSendCounts = counts
            }
        }
    }

    /**
     * 落盘触发：内存统计写入持久化（onPause/onDestroy 兜底）。
     * P2 收口后走 flushPendingSave——跳过 debounce 即时落盘，L3 串行排队执行。
     */
    fun flush() {
        record {
            try {
                DataManager.getInstance().flushPendingSave()
            } catch (e: Exception) {
                // 落盘失败：下次 flush 重试（内存数据仍在）
            }
        }
    }

    /** 更新指定 WebApp 统计字段（内存 + 持久化标记） */
    private fun updateStats(webappId: Int, block: (WebApp) -> Unit) {
        // 统一拿原对象（忽略全局合并）：副本 replace 会破坏统计字段
        val w = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappId, true) ?: return
        block(w)
        DataManager.getInstance().replaceWebApp(w)
    }
}
