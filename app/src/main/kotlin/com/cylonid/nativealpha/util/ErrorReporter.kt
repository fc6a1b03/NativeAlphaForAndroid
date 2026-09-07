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

    /**
     * 取证探针（「程序内日志 → 导出 → 实锤」闭环的标准入口）：
     * 模拟器测不出、只有实机能复现的厂商兼容/内核差异类问题，在关键决策点
     * 打 [probe] 记录结构化现场——用户实机复现后从「设置 → 导出错误日志」
     * 导出 JSON 即含完整现场，无需 adb。
     *
     * 约定（固化本模式，调用方不再自行拼装）：
     * - 默认 [AppErrorEntry.LEVEL_INFO]（取证信息非故障，崩溃弹窗/错误统计
     *   不受影响）；确认的失败/降级升级 WARNING/ERROR；
     * - 正常路径也记录（对照现场与失败现场同等重要，FileChooser 实锤案例
     *   的教训：只记失败看不到正常形态长什么样）；量由调用方在关键决策点
     *   打点控制（选择/返回/降级级事件，非逐帧）；
     * - fields 结构化传参，统一 `event k1=v1 k2=v2` 格式（可读可 grep）；
     * - logcat 同步留 INFO 副本（开发者深挖通道，双重保险）。
     */
    fun probe(
        context: Context,
        tag: String,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
        level: String = AppErrorEntry.LEVEL_INFO
    ) {
        val message = formatProbe(event, fields)
        Log.i(tag, message)
        val appContext = context.applicationContext
        scope.launch {
            try {
                AppErrorLogRepository.append(
                    appContext,
                    AppErrorEntry(
                        time = System.currentTimeMillis(),
                        level = level,
                        tag = tag,
                        message = message,
                        stackTrace = ""
                    )
                )
            } catch (ignored: Exception) {
                // 上报失败静默（logcat 已有副本）
            }
        }
    }

    /** 探针现场格式化（纯函数可单测）：`event k1=v1 k2=v2`，null 显式保留 */
    internal fun formatProbe(event: String, fields: Map<String, Any?>): String {
        if (fields.isEmpty()) return event
        val rendered = fields.entries.joinToString(" ") { (k, v) -> "$k=$v" }
        return "$event $rendered"
    }
}
