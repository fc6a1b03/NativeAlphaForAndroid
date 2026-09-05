package com.cylonid.nativealpha.util

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 按日活动快照仓库（Phase 2）：日期 → {打开次数, 24 小时桶}。
 *
 * 用途：热力图（近 12 周）/ 连续使用周数（streak）/「深夜型用户」时段洞察。
 * 存储：daily_activity（DataStore，经 JsonPrefsStore 模板）；滚动保留 90 天，
 * 写入时顺带裁剪。全部本地、零上报。
 *
 * 线程纪律：appendOpen 走 StatsRecorder 单线程队列（埋点不阻塞主线程，
 * 与既有埋点同队列串行，无并发竞争）。
 */
internal object StatsDailyStore {

    /** 滚动保留窗口（天）——热力图 12 周(84 天)取整 90 留余量 */
    private const val RETAIN_DAYS = 90

    /** 单日数据 */
    internal data class DayEntry(val opens: Int = 0, val hours: IntArray = IntArray(24))

    /** 快照模型：日期串（yyyy-MM-dd）→ 当日数据 */
    internal data class Snapshot(val days: Map<String, DayEntry> = emptyMap()) {
        /** streak：连续活跃周数（本周或上周起回溯，周内任一天活跃即算） */
        fun streakWeeks(today: Calendar = Calendar.getInstance()): Int {
            if (days.isEmpty()) return 0
            var weeks = 0
            val cursor = (today.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                // 跨月安全回退：set(DAY_OF_WEEK) 只在当月内滚动（月中调用错位一周），
                // 必须按「星期差值 add 回退」计算周首
                val diff = (get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
                add(Calendar.DAY_OF_YEAR, -diff)
            }
            // 本周（cursor..+6）无活跃则从上周起算（周中看不破连续）
            if (!weekActive(cursor)) cursor.add(Calendar.DAY_OF_YEAR, -7)
            while (weekActive(cursor)) {
                weeks++
                cursor.add(Calendar.DAY_OF_YEAR, -7)
            }
            return weeks
        }

        private fun Snapshot.weekActive(weekStart: Calendar): Boolean {
            val fmt = dateFormat()
            repeat(7) { i ->
                val c = (weekStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, i) }
                if ((days[fmt.format(c.time)]?.opens ?: 0) > 0) return true
            }
            return false
        }
    }

    private val store = object : JsonPrefsStore<Snapshot>(stringPreferencesKey("daily_activity")) {
        private val gson = com.google.gson.Gson()
        override fun empty() = Snapshot()
        override fun decode(json: String): Snapshot = try {
            val raw = gson.fromJson(json, Raw::class.java)
            // 防御清洗：同 WebVitalsStore（统一 filterInstances，坏条目丢弃）
            val days = raw.days.mapNotNull { (key, v) ->
                listOf(v).filterInstances<DayEntry>().firstOrNull()
                    ?.let { key to DayEntry(it.opens, it.hours.clone()) }
            }.toMap()
            Snapshot(days)
        } catch (e: Exception) {
            empty()
        }
        override fun encode(value: Snapshot): String =
            gson.toJson(Raw(value.days))
    }

    // Gson 载荷（days 保持 JSON map 形态，避免 TypeToken 样板）
    private class Raw(val days: Map<String, DayEntry> = emptyMap())

    /** 记录一次打开：当日 opens+1、对应小时桶+1、顺带裁剪 90 天外旧数据 */
    suspend fun appendOpen(context: Context, at: Calendar = Calendar.getInstance()) =
        withContext(Dispatchers.IO) {
            val fmt = dateFormat()
            val today = fmt.format(at.time)
            val snap = store.read(context)
            val prev = snap.days[today] ?: DayEntry()
            val hour = at.get(Calendar.HOUR_OF_DAY)
            val entry = DayEntry(opens = prev.opens + 1, hours = prev.hours.clone().also { it[hour]++ })
            val cutoff = fmt.format(Date(at.time.time - RETAIN_DAYS * 24L * 60 * 60 * 1000))
            val pruned = snap.days.filterKeys { it >= cutoff } + (today to entry)
            store.write(context, Snapshot(pruned))
        }

    /** 快照读取（挂起，统计页装配用） */
    suspend fun snapshot(context: Context): Snapshot = store.read(context)

    /** 清空（StatsClearer 编排点） */
    suspend fun clear(context: Context) = store.write(context, Snapshot())

    private fun dateFormat() = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 日期键（yyyy-MM-dd，US locale——与存储/裁剪同源；热力图复用避免格式双份） */
    internal fun dateKey(calendar: Calendar): String = dateFormat().format(calendar.time)
}
