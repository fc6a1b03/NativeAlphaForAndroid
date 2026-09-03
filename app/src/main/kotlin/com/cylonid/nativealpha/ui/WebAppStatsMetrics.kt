package com.cylonid.nativealpha.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp
import java.util.Locale

// ===== 加载耗时分布分桶（统计页图表） =====
/** 分桶边界（ms）：<1s / 1-2s / 2-3s / 3-5s / 5s+ */
private val LOAD_TIME_BUCKET_MS = listOf(1000L, 2000L, 3000L, 5000L)
/** 分桶标签（与边界一一对应，末桶为 5s+ 开区间） */
private val LOAD_TIME_BUCKET_LABELS = listOf("<1s", "1-2s", "2-3s", "3-5s", "5s+")

// ===== 使用建议阈值（统计页「数据→行动」） =====
/** 平均加载耗时超过该值（ms）提示优化（约 3s） */
private const val SUGGEST_SLOW_LOAD_MS = 3000L
/** 页面错误数超过该值提示查看错误日志 */
private const val SUGGEST_ERROR_COUNT = 10
/** 缓存占用超过该值（B）提示清理（约 50MB） */
private const val SUGGEST_CACHE_BYTES = 50L * 1024 * 1024

/** 加载耗时分布横条图（纯 Compose 自绘比例条，按次数占比） */
@Composable
internal fun LoadTimeChart(webapp: WebApp) {
    // 真实分布：按耗时区间分桶（标签见常量），展示最近 20 次
    val times = webapp.statLoadTimes
    if (times.isEmpty()) return
    // 分桶：index 对应区间，值 = 次数（末桶为 5s+ 开区间）
    val buckets = IntArray(LOAD_TIME_BUCKET_MS.size)
    times.forEach { ms ->
        val bucket = LOAD_TIME_BUCKET_MS.indexOfFirst { ms < it }.let { if (it == -1) LOAD_TIME_BUCKET_MS.size - 1 else it }
        buckets[bucket]++
    }
    val labels = LOAD_TIME_BUCKET_LABELS
    val maxCount = (buckets.maxOrNull() ?: 1).coerceAtLeast(1)
    // 横向柱状：每桶一行（标签 + 比例条 + 次数），一眼看出耗时集中区
    Column {
        buckets.forEachIndexed { index, count ->
            if (count == 0) return@forEachIndexed
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    labels[index],
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(44.dp)
                )
                // 比例条：weight(1f) 占剩余宽度，长度 = 次数占比
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(count.toFloat() / maxCount)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.load_count_times, count),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.width(40.dp)
                )
            }
        }
    }
}

internal fun minLoadTime(webapp: WebApp): Long {
    // 最快加载（近似：平均值与最慢的差值下限）
    return if (webapp.statLoadTimeCount > 0) {
        (webapp.statLoadTimeSum / webapp.statLoadTimeCount).coerceAtMost(webapp.statMaxLoadTime)
    } else 0L
}

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

/**
 * 生成使用建议（数据→行动）：按统计字段阈值给出可执行建议。
 * 规则：平均加载 >3s / 错误数 >10 / 缓存 >50MB 各出一条；无异常返回空列表。
 */
internal fun buildSuggestions(context: Context, webapp: WebApp): List<String> {
    val tips = mutableListOf<String>()
    val avgLoad = if (webapp.statLoadTimeCount > 0)
        webapp.statLoadTimeSum / webapp.statLoadTimeCount else 0L
    if (avgLoad > SUGGEST_SLOW_LOAD_MS) {
        tips.add(context.getString(R.string.suggestion_slow_load, formatDuration(avgLoad)))
    }
    if (webapp.statErrors > SUGGEST_ERROR_COUNT) {
        tips.add(
            context.resources.getQuantityString(
                R.plurals.suggestion_errors, webapp.statErrors, webapp.statErrors
            )
        )
    }
    if (webapp.statCacheHttpBytes > SUGGEST_CACHE_BYTES) {
        tips.add(context.getString(R.string.suggestion_cache, formatBytes(webapp.statCacheHttpBytes)))
    }
    return tips
}
