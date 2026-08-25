package com.cylonid.nativealpha.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Collections

/**
 * 应用自身运行错误日志（全局，与统计页页面错误分离）。
 *
 * 数据域：应用崩溃/未捕获异常（KEY_APP_ERRORS 唯一存储源）。
 * 结构：{time, level, tag, message, stackTrace}——与页面错误 {time, site, type, code, description} 不同构。
 */
data class AppErrorEntry(
    val time: Long = 0L,          // 发生时间（epoch ms）
    val level: String = LEVEL_ERROR,  // 级别：ERROR / WARNING / CRASH（见 companion 常量）
    val tag: String = "",         // 来源（类名/线程）
    val message: String = "",     // 错误信息
    val stackTrace: String = ""   // 堆栈（崩溃时记录，其余可为空）
) {
    companion object {
        /** 级别：崩溃（未捕获异常/进程终止） */
        const val LEVEL_CRASH = "CRASH"
        /** 级别：一般错误 */
        const val LEVEL_ERROR = "ERROR"
        /** 级别：警告 */
        const val LEVEL_WARNING = "WARNING"
        private val gson = Gson()

        /** 序列化为 JSON 数组 */
        fun toJson(entries: List<AppErrorEntry>): String = gson.toJson(entries)

        /** 反序列化：损坏返回空列表（不崩溃） */
        fun fromJson(json: String): List<AppErrorEntry> {
            return try {
                val type = object : TypeToken<List<AppErrorEntry>>() {}.type
                val list: List<AppErrorEntry>? = gson.fromJson(json, type)
                list ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        /** 截断堆栈到指定行数，防存储膨胀 */
        fun truncateStackTrace(stack: String, maxLines: Int): String {
            if (stack.isEmpty()) return ""
            val lines = stack.split("\n")
            if (lines.size <= maxLines) return stack
            return lines.take(maxLines).joinToString("\n") + "\n... (" + (lines.size - maxLines) + " more lines)"
        }
    }
}

/** 应用错误日志仓库：DataStore 唯一读写（异步，不阻塞主线程）。
 *  保留策略：仅保留近 [com.cylonid.nativealpha.util.Const.APP_ERROR_DAYS] 天
 *  （超龄在写入时清理——唯一清理触发）+ 条数上限丢最旧；导出只读不清除。 */
object AppErrorLogRepository {
    private const val MAX_ENTRIES = com.cylonid.nativealpha.util.Const.ERROR_LOG_LIMIT  // 上限（条），超出丢最旧

    /** 保留窗口起点（epoch ms）——清理/过滤统一口径（能力唯一实现处） */
    private fun retentionCutoff(): Long =
        System.currentTimeMillis() - com.cylonid.nativealpha.util.Const.APP_ERROR_DAYS * 24L * 60 * 60 * 1000

    /** 追加一条错误日志（异步，协程内调用）；写入时清理超龄（>3 天）记录 */
    suspend fun append(
        context: android.content.Context,
        entry: AppErrorEntry
    ) {
        try {
            val current = com.cylonid.nativealpha.util.AppStorage.readString(
                context,
                com.cylonid.nativealpha.util.AppStorage.KEY_APP_ERRORS
            )
            val list = AppErrorEntry.fromJson(current).toMutableList()
            list.add(entry)
            // 超龄清理：仅保留近 3 天（在 add 之后执行，保证任何写入后库内无超龄记录；
            // 导出不清除——此处是唯一清理点）
            list.removeAll { it.time < retentionCutoff() }
            // 超上限丢最旧
            if (list.size > MAX_ENTRIES) {
                Collections.sort(list) { a, b -> a.time.compareTo(b.time) }
                val overflow = list.size - MAX_ENTRIES
                list.subList(0, overflow).clear()
            }
            com.cylonid.nativealpha.util.AppStorage.writeString(
                context,
                com.cylonid.nativealpha.util.AppStorage.KEY_APP_ERRORS,
                AppErrorEntry.toJson(list)
            )
        } catch (e: Exception) {
            // 日志写入失败静默：不影响主功能
        }
    }

    /** 读取全部（异步，协程内调用） */
    suspend fun getAll(context: android.content.Context): List<AppErrorEntry> {
        return try {
            val json = com.cylonid.nativealpha.util.AppStorage.readString(
                context,
                com.cylonid.nativealpha.util.AppStorage.KEY_APP_ERRORS
            )
            AppErrorEntry.fromJson(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 读取近 3 天日志（导出/崩溃提示统一口径；只读不清除——每次导出都是完整 3 天窗口） */
    suspend fun getRecent(context: android.content.Context): List<AppErrorEntry> {
        val cutoff = retentionCutoff()
        return getAll(context).filter { it.time >= cutoff }
    }

    /**
     * 同步追加（崩溃兜底专用：Java 未捕获异常处理器调用，runBlocking 确保写入完成）。
     */
    fun appendSync(context: android.content.Context, entry: AppErrorEntry) {
        try {
            kotlinx.coroutines.runBlocking {
                append(context, entry)
            }
        } catch (e: Exception) {
            // 崩溃场景写入失败静默
        }
    }
}
