package com.cylonid.nativealpha.util

import android.content.Context
import android.util.Log
import com.cylonid.nativealpha.model.AppErrorEntry
import com.cylonid.nativealpha.model.AppErrorLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 统一错误记录器：任何非致命异常/可恢复错误同时进 logcat 与应用错误日志
 * （KEY_APP_ERRORS——「设置 → 导出错误日志」可见，实机排查唯一入口）。
 *
 * 设计约束：
 * - 记录动作自身绝不抛（再失败也静默）——错误上报不能成为新错误源
 * - 独立 CoroutineScope(SupervisorJob)：与调用方生命周期解耦，
 *   页面销毁后写入仍完成（IO 线程）
 * - 崩溃级（未捕获异常）不走这里——App 的 UncaughtExceptionHandler 已兜底
 */
object ErrorReporter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 记录一条错误到应用错误日志（异步、永不抛）。
     * @param tag 来源（类名/模块）
     * @param message 错误信息（用户可读描述）
     * @param error 可选异常（堆栈自动提取）
     * @param level 级别（ERROR/WARNING；CRASH 留给系统兜底）
     */
    fun report(context: Context, tag: String, message: String, error: Throwable? = null, level: String = AppErrorEntry.LEVEL_ERROR) {
        Log.w(tag, message, error)
        val appContext = context.applicationContext
        scope.launch {
            try {
                val entry = AppErrorEntry(
                    time = System.currentTimeMillis(),
                    level = level,
                    tag = tag,
                    message = message + (error?.let { ": " + it.message } ?: ""),
                    stackTrace = error?.let { Log.getStackTraceString(it) } ?: ""
                )
                AppErrorLogRepository.append(appContext, entry)
            } catch (ignored: Exception) {
                // 上报失败静默（logcat 已有副本）
            }
        }
    }

    /**
     * 安全执行块：异常自动进错误日志，调用方拿 null 兜底。
     * 用于「失败可降级但不能崩」的路径（菜单动作/设置保存等 UI 入口）。
     */
    fun <T> runCatchingReport(context: Context, tag: String, message: String, block: () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            report(context, tag, message, e)
            null
        }
    }
}
