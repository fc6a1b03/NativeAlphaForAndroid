package com.cylonid.nativealpha.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import java.util.Locale

// ===== 加载耗时分布分桶（统计页图表） =====
/** 分桶边界（ms）：<1s / 1-2s / 2-3s / 3-5s / 5s+ */
private val LOAD_TIME_BUCKET_MS = listOf(1000L, 2000L, 3000L, 5000L)
/** 分桶标签（与边界一一对应，末桶为 5s+ 开区间） */
private val LOAD_TIME_BUCKET_LABELS = listOf("<1s", "1-2s", "2-3s", "3-5s", "5s+")

/** 柱状图最大高度（dp）——视觉语言常量，仅此一处 */
private const val CHART_MAX_HEIGHT_DP = 96

/**
 * 加载耗时分布竖向柱状图（纯 Compose Box 组合，无图表库）。
 *
 * @param accent 柱色（站点强调色，色彩三源之一；由调用方传入解耦图标提取）
 * 数据 <2 次时调用方应显示引导文案（样本过少分布无意义）。
 */
@Composable
internal fun LoadTimeChart(webapp: WebApp, accent: Color) {
    val times = webapp.statLoadTimes
    if (times.isEmpty()) return
    // 分桶：index 对应区间，值 = 次数（末桶为 5s+ 开区间）
    val buckets = bucketize(times)
    val maxCount = (buckets.maxOrNull() ?: 1).coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth().height(CHART_MAX_HEIGHT_DP.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        buckets.forEachIndexed { index, count ->
            val fraction = count.toFloat() / maxCount
            val growth by animateFloatAsState(
                targetValue = fraction,
                animationSpec = tween(durationMillis = COUNT_UP_MS),
                label = "barGrowth$index"
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                if (count > 0) {
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height((CHART_MAX_HEIGHT_DP * growth).dp.coerceAtLeast(if (count > 0) 4.dp else 0.dp))
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(if (count > 0) accent else MaterialTheme.colorScheme.surfaceContainerHighest)
                )
                Text(
                    LOAD_TIME_BUCKET_LABELS[index],
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** 分桶纯函数（可单测）：耗时明细 → 各桶次数 */
internal fun bucketize(times: List<Long>): IntArray {
    // 4 个边界 → 5 桶（size+1）；开区间末桶（5s+）index=size——
    // 旧实现 size 数组+coerce size-1 把 5s+ 桶整个吞掉（存量债清偿）
    val buckets = IntArray(LOAD_TIME_BUCKET_MS.size + 1)
    times.forEach { ms ->
        val bucket = LOAD_TIME_BUCKET_MS.indexOfFirst { ms < it }
            .let { if (it == -1) LOAD_TIME_BUCKET_MS.size else it }
        buckets[bucket]++
    }
    return buckets
}

/**
 * 最快加载（真实值）：取最近 20 次明细的最小值。
 * 旧实现用平均值冒充「Fastest」——技术债清偿（显示值必须与标签一致）。
 */
internal fun minLoadTime(webapp: WebApp): Long =
    // 0ms 样本=缓存命中瞬间完成、计时未捕获的无效值——Fastest 取非零最快
    webapp.statLoadTimes.filter { it > 0 }.minOrNull() ?: 0L

/** 时长格式化（ms → 秒/分钟） */
internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "—"
    return if (ms < 1000) "${ms}ms"
    else if (ms < 60000) String.format(Locale.getDefault(), "%.1fs", ms / 1000.0)
    else String.format(Locale.getDefault(), "%.1fm", ms / 60000.0)
}

/** 字节格式化（B → KB → MB） */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    }
}
