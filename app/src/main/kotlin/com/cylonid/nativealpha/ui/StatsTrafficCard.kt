package com.cylonid.nativealpha.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp

/**
 * §2.5 流量区块：累计流量主数字 + 每次打开平均 + 口径说明。
 *
 * 数据口径（与采集端同源，勿在此处重算）：[WebApp.statTrafficBytes] 为
 * Resource Timing transferSize 累计（跨域未授权资源不计 → 真实用量下界）。
 *
 * 视觉复用统计页既有约定：主数字走 [RowData] 的 emphasized 档，不新造排版。
 * 刻意不做「累计流量 vs 缓存占用」占比条——前者是历史累计、后者是当前快照，
 * 两者量纲不同不可比，画成比例条会误导。
 */
@Composable
internal fun StatsTrafficCard(
    webapp: WebApp,
    modifier: Modifier = Modifier
) {
    val total = webapp.statTrafficBytes
    StatsSection(
        title = stringResource(R.string.stats_section_traffic),
        modifier = modifier,
        isEmpty = total <= 0L
    ) {
        RowData(
            label = stringResource(R.string.stat_traffic_total),
            value = formatBytes(total),
            emphasized = true
        )
        Spacer(modifier = Modifier.height(6.dp))
        RowData(
            label = stringResource(R.string.stat_traffic_per_launch),
            value = formatBytes(perLaunchBytes(webapp))
        )
        Column {
            Text(
                stringResource(R.string.stat_traffic_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/**
 * 每次打开平均流量（纯函数，可单测）：累计字节 / 打开次数。
 * 分母优先 statLaunches（真实打开次数）；无打开记录时回退上报批次数，
 * 两者皆 0 返回 0（零值态由 StatsSection 灰化，不显示除零结果）。
 */
internal fun perLaunchBytes(webapp: WebApp): Long {
    val launches = webapp.statLaunches.takeIf { it > 0 } ?: webapp.statTrafficSamples
    if (launches <= 0) return 0L
    return webapp.statTrafficBytes / launches
}
