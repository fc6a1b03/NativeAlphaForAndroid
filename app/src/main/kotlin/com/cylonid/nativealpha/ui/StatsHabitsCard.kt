package com.cylonid.nativealpha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.FeatureMetrics

/**
 * §5 使用习惯区块：快捷键频次条（Top 3）/ 站内分享次数 / 矩阵格加载次数。
 * 全部来自 FeatureMetrics 既有计数与 WebApp 快捷键字段，零新增埋点。
 */
@Composable
internal fun StatsHabitsCard(webapp: WebApp, modifier: Modifier = Modifier) {
    val share = FeatureMetrics.moduleSnapshot(FeatureMetrics.MODULE_SHARE)
    val matrix = FeatureMetrics.moduleSnapshot(FeatureMetrics.MODULE_MATRIX)
    val shortcuts = webapp.keyShortcutSendCounts.entries.sortedByDescending { it.value }.take(3)
    val shares = share["sent"] ?: 0L
    val cellLoads = matrix["cell_load"] ?: 0L
    StatsSection(
        title = stringResource(R.string.stats_section_habits),
        modifier = modifier,
        isEmpty = shortcuts.isEmpty() && shares == 0L && cellLoads == 0L
    ) {
        if (shortcuts.isNotEmpty()) {
            Text(
                stringResource(R.string.stats_habit_shortcuts),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            val maxCount = shortcuts.first().value.coerceAtLeast(1)
            shortcuts.forEach { (combo, count) ->
                UsageBar(combo, count, maxCount)
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        RowData(
            label = stringResource(R.string.stats_habit_share),
            value = formatCount(shares)
        )
        RowData(
            label = stringResource(R.string.stats_habit_matrix),
            value = formatCount(cellLoads)
        )
    }
}

/** 单条使用频次条（组合键名 + 比例条 + 次数；与柱状图同一比例条语言） */
@Composable
private fun UsageBar(label: String, count: Int, maxCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(count.toFloat() / maxCount.coerceAtLeast(1))
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            formatCount(count.toLong()),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp)
        )
    }
}
