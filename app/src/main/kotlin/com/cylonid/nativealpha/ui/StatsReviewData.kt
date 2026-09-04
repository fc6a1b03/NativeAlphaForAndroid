package com.cylonid.nativealpha.ui

import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.StatsDailyStore.Snapshot
import java.util.Calendar

/**
 * 月度回顾数据聚合（Phase 4，纯函数可单测）：从按日快照与既有统计字段
 * 提炼「本月与你的相处之最」。
 *
 * 诚实红线：全部数值来自真实记录；活跃天数 <7 不生成回顾（样本不足以
 * 支撑「回顾」叙事，入口随之隐藏）；本月无打开记录同样不生成。
 */
internal data class ReviewData(
    val totalOpens: Int,
    val activeDays: Int,
    val busiestDay: String?,
    val busiestDayOpens: Int,
    val avgLoad: Long,
    val fastestLoad: Long,
    val streakWeeks: Int,
    val notificationShown: Long
) {
    companion object {

        /** 生成回顾所需最小活跃天数（本月内） */
        internal const val MIN_ACTIVE_DAYS = 7

        /** 活跃天数（入口显隐与 build 同一口径，避免两处判定漂移） */
        internal fun activeDays(daily: Snapshot): Int =
            daily.days.count { it.value.opens > 0 }

        /** 聚合（口径=快照全窗口近 90 天；活跃 <7 天 → null 不生成） */
        internal fun build(
            webapp: WebApp,
            daily: Snapshot,
            notificationShown: Long,
            today: Calendar = Calendar.getInstance()
        ): ReviewData? {
            val activeDays = daily.days.filter { (_, entry) -> entry.opens > 0 }
            if (activeDays.size < MIN_ACTIVE_DAYS) return null
            val totalOpens = activeDays.values.sumOf { it.opens }
            if (totalOpens <= 0) return null
            val busiest = activeDays.maxByOrNull { it.value.opens }
            val loadTimes = webapp.statLoadTimes.filter { it > 0 }
            return ReviewData(
                totalOpens = totalOpens,
                activeDays = activeDays.size,
                busiestDay = busiest?.key,
                busiestDayOpens = busiest?.value?.opens ?: 0,
                avgLoad = if (webapp.statLoadTimeCount > 0) webapp.statLoadTimeSum / webapp.statLoadTimeCount else 0L,
                fastestLoad = loadTimes.minOrNull() ?: 0L,
                streakWeeks = daily.streakWeeks(today),
                notificationShown = notificationShown
            )
        }
    }
}
