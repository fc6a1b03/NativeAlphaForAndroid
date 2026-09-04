package com.cylonid.nativealpha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.util.StatAccent
import java.util.Calendar

/**
 * §3 陪伴热力图（Phase 2）：近 12 周 × 7 天活跃格网（GitHub contributions 式）。
 * 5 档色阶由站点强调色派生（视觉语言三源约束）；0 活跃为容器色。
 * 列=周（旧→新，末列为本周）、行=星期；数据不足 12 周的左侧格子留空。
 */
@Composable
internal fun StatsHeatmapCard(
    title: String,
    opensPerDay: Map<String, Int>,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val total = opensPerDay.values.sum()
    StatsSection(
        title = title,
        modifier = modifier
    ) {
        HeatmapGrid(opensPerDay, StatAccent.heatScale(accent))
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            total.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** 单格尺寸（dp）与间距——热力图视觉常量，仅此一处 */
private const val CELL_DP = 13
private const val CELL_GAP_DP = 2

/** 热力图格网：12 列周 × 7 行日（末列为本周） */
@Composable
private fun HeatmapGrid(opensPerDay: Map<String, Int>, scale: List<Color>) {
    val today = Calendar.getInstance()
    val cursor = (today.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        // 跨月安全回退（同 streakWeeks：set(DAY_OF_WEEK) 有当月滚动陷阱）
        val diff = (get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7
        add(Calendar.DAY_OF_YEAR, -diff)
    }
    // 回推 11 周：cursor 变为热力图首列周首
    cursor.add(Calendar.DAY_OF_YEAR, -11 * 7)
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP_DP.dp)) {
        repeat(12) {
            Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP_DP.dp)) {
                repeat(7) {
                    val key = com.cylonid.nativealpha.util.StatsDailyStore.dateKey(cursor)
                    val opens = opensPerDay[key] ?: 0
                    val future = cursor.after(today)
                    // 次数→档位：1 / 2-3 / 4-7 / 8+（4 档活跃，0 档空态）
                    val level = when {
                        future || opens <= 0 -> 0
                        opens >= 8 -> 4
                        opens >= 4 -> 3
                        opens >= 2 -> 2
                        else -> 1
                    }
                    val color = if (level == 0) empty else scale[level - 1]
                    Box(
                        modifier = Modifier
                            .size(CELL_DP.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color)
                    )
                    cursor.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        }
    }
}
