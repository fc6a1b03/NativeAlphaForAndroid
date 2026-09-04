package com.cylonid.nativealpha.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.util.FeatureMetrics

/**
 * §4 自动化区块：网页事件通知累计大数字（CountUp）+ 规则链路明细。
 * 数据源 FeatureMetrics（已埋未展示）；零通知时整卡灰化（零值语义：
 * 「还没配置规则」而非「坏了」）。
 */
@Composable
internal fun StatsAutomationCard(modifier: Modifier = Modifier) {
    val stats = FeatureMetrics.moduleSnapshot(FeatureMetrics.MODULE_WEBEVENT)
    val notified = (stats["notification_shown"] ?: 0L).toInt()
    StatsSection(
        title = stringResource(R.string.stats_section_automation),
        modifier = modifier,
        isEmpty = notified == 0
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                rememberCountUp(notified).toString(),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.stats_automation_subtitle),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        RowData(
            label = stringResource(R.string.stats_automation_matched),
            value = formatCount(stats["rule_matched"] ?: 0L)
        )
        RowData(
            label = stringResource(R.string.stats_automation_fired),
            value = formatCount(stats["fired"] ?: 0L)
        )
    }
}
