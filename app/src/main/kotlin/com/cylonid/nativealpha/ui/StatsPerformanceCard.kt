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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp


/**
 * §2 性能区块：平均加载主数字 + 五档柱状分布 + 最快/最慢汇总 + 缓存省流量行。
 * Web Vitals 瀑布在 Phase 3 数据可用后插入柱状图上方（区块位次已预留）。
 */
@Composable
internal fun StatsPerformanceCard(
    webapp: WebApp,
    accent: Color,
    vitals: List<com.cylonid.nativealpha.util.WebVitalsEntry>,
    modifier: Modifier = Modifier
) {
    val avgLoad = avgLoadMs(webapp)
    StatsSection(
        title = stringResource(R.string.stats_section_performance),
        modifier = modifier,
        isEmpty = webapp.statLoadTimeCount == 0 && webapp.statCacheHttpBytes == 0L
    ) {
        RowData(
            label = stringResource(R.string.stat_avg_load),
            value = formatDuration(avgLoad),
            emphasized = true
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (webapp.statLoadTimeCount >= 2) {
            LoadTimeChart(webapp, accent)
        } else {
            Text(
                stringResource(R.string.load_chart_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }
        if (vitals.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            VitalsWaterfall(vitals.first(), accent)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(
                R.string.load_summary,
                formatDuration(avgLoad),
                formatDuration(minLoadTime(webapp)),
                formatDuration(webapp.statMaxLoadTime)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (webapp.statCacheHttpBytes > 0L) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                stringResource(R.string.stats_save_traffic, formatBytes(webapp.statCacheHttpBytes)),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** 标签-值行（数据行统一呈现；emphasized = 主数字行用大字重） */
@Composable
internal fun RowData(label: String, value: String, emphasized: Boolean = false) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = if (emphasized) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

/**
 * Web Vitals 瀑布（Phase 3）：最近一次页面加载的五段细分横条。
 * 条长按五段最大值归一（各自独立行，非堆叠时序——移动端窄幅更可读）。
 */
@Composable
private fun VitalsWaterfall(latest: com.cylonid.nativealpha.util.WebVitalsEntry, accent: Color) {
    val rows = listOf(
        stringResource(R.string.vitals_dns) to latest.dns,
        stringResource(R.string.vitals_tcp) to latest.tcp,
        stringResource(R.string.vitals_ttfb) to latest.ttfb,
        stringResource(R.string.vitals_fcp) to latest.fcp,
        stringResource(R.string.vitals_lcp) to latest.lcp
    )
    val maxMs = rows.maxOf { it.second }.coerceAtLeast(1)
    Text(
        stringResource(R.string.vitals_caption, latest.domNodes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(6.dp))
    rows.forEach { (label, ms) ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(40.dp)
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
                        .fillMaxWidth(if (ms > 0) ms.toFloat() / maxMs else 0f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(accent)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                formatDuration(ms.toLong()),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp)
            )
        }
    }
}
