package com.cylonid.nativealpha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.PageErrorEntry
import com.cylonid.nativealpha.model.PageErrorRepository
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.DateUtils
import com.cylonid.nativealpha.util.StatsRecorder
import kotlinx.coroutines.launch

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

/**
 * 统计页（按 WebApp 进入 · 开发者向）。
 *
 * Bento Grid 布局：KPI 卡 2×2 / 加载耗时柱状图 / 缓存详情 / 错误日志（导出）/ 元信息。
 * 全部复用 M3 组件体系（Card/Snackbar/AlertDialog），深浅色自动适配。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebAppStatsScreen(
    webapp: WebApp,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onClearCache: () -> Unit,
    onClearStats: () -> Unit,

    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 页面错误列表（DataStore 异步加载）
    var pageErrors by remember { mutableStateOf<List<PageErrorEntry>>(emptyList()) }
    // 清缓存/清空统计确认对话框（状态驱动，防误触）
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearStatsDialog by remember { mutableStateOf(false) }

    // 加载该站错误日志
    LaunchedEffect(webapp.ID) {
        pageErrors = PageErrorRepository.getForSite(context, webapp.ID)
    }

    // 计算统计指标（0 值显示「—」）
    val avgLoad = if (webapp.statLoadTimeCount > 0)
        webapp.statLoadTimeSum / webapp.statLoadTimeCount else 0L
    val allErrors = pageErrors
    // 当前查看详情的错误（点击行弹出，null=未选中）
    var selectedError by remember { mutableStateOf<PageErrorEntry?>(null) }
    // 分组展开状态（key=错误类型，true=展开显示明细）
    var expandedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title, webapp.displayName ?: webapp.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 自适应边距：innerPadding 含系统栏 inset，再加 topPadding 留出与标题栏间距（避免内容顶上去）
                .padding(innerPadding)
                .padding(top = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // KPI 卡 2×2
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KpiCard(stringResource(R.string.stat_launches), webapp.statLaunches.toString(), Modifier.weight(1f))
                KpiCard(stringResource(R.string.stat_avg_load), formatDuration(avgLoad), Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KpiCard(stringResource(R.string.stat_http_cache), formatBytes(webapp.statCacheHttpBytes), Modifier.weight(1f))
                KpiCard(stringResource(R.string.stat_page_errors), webapp.statErrors.toString(), Modifier.weight(1f))
            }

            // 使用建议（数据→行动：加载慢/错误多/缓存大给出可执行建议）
            val suggestions = buildSuggestions(context, webapp)
            if (suggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                StatsCard {
                    Text(stringResource(R.string.suggestions_title), style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    suggestions.forEach { tip ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "💡",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(28.dp)
                            )
                            Text(
                                tip,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 加载耗时分布（Vico 图表，数据 <2 次显示引导）
            StatsCard {
                Text(stringResource(R.string.load_time_chart), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.load_time_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (webapp.statLoadTimeCount >= 2) {
                    LoadTimeChart(webapp)
                } else {
                    Text(
                        stringResource(R.string.load_chart_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.load_summary, formatDuration(avgLoad), formatDuration(minLoadTime(webapp)), formatDuration(webapp.statMaxLoadTime)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 缓存详情
            StatsCard {
                Text(stringResource(R.string.cache_details), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                CacheRow(stringResource(R.string.cache_http_total), formatBytes(webapp.statCacheHttpBytes))
                CacheRow(stringResource(R.string.cache_site_storage), formatBytes(webapp.statCacheStoreBytes))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showClearCacheDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.clear_cache))
                }
                // 清缓存确认对话框（状态驱动，防误触）
                if (showClearCacheDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearCacheDialog = false },
                        title = { Text(stringResource(R.string.clear_cache)) },
                        text = { Text(stringResource(R.string.clear_cache_confirm)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showClearCacheDialog = false
                                onClearCache()
                            }) { Text(stringResource(R.string.confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearCacheDialog = false }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 错误日志（导入/导出对称）
            StatsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "错误日志",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    // 导出按钮（错误日志只导出不导入）
                    IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Download, contentDescription = "导出", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                if (allErrors.isEmpty()) {
                    Text(
                        stringResource(R.string.no_errors),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    // 按错误类型分组（长列表友好：分组折叠，不用一直下滑）
                    val grouped = allErrors.groupBy { it.type }
                    grouped.forEach { (type, entries) ->
                        val expanded = type in expandedGroups
                        // 分组标题行（点击展开/收起）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    expandedGroups = if (expanded) expandedGroups - type
                                    else expandedGroups + type
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 类型徽标
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(errorColor(type))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                type,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${entries.size}条",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (expanded) "▾" else "▸",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 展开时显示明细（每条可点详情）
                        if (expanded) {
                            entries.forEach { entry ->
                                ErrorRow(entry, onClick = { selectedError = entry })
                                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }

            // 错误详情对话框（点击行弹出，查看全部内容）
            selectedError?.let { err ->
                AlertDialog(
                    onDismissRequest = { selectedError = null },
                    title = { Text("${err.type}(${err.code})") },
                    text = {
                        Column {
                            Text(
                                err.description.ifBlank { "—" },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                DateUtils.formatTimestamp(err.time),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { selectedError = null }) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 元信息 + 清空统计
            StatsCard {
                Text(stringResource(R.string.first_used), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                CacheRow(stringResource(R.string.first_used), if (webapp.statFirstLoadedAt > 0) DateUtils.formatTimestamp(webapp.statFirstLoadedAt) else "—")
                CacheRow(stringResource(R.string.last_used), if (webapp.statLastUsedAt > 0) DateUtils.formatTimestamp(webapp.statLastUsedAt) else "—")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showClearStatsDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.clear_stats))
                }
                // 清空统计确认对话框（状态驱动，防误触）
                if (showClearStatsDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearStatsDialog = false },
                        title = { Text(stringResource(R.string.clear_stats)) },
                        text = { Text(stringResource(R.string.clear_stats_confirm)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showClearStatsDialog = false
                                onClearStats()
                            }) { Text(stringResource(R.string.confirm)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearStatsDialog = false }) { Text(stringResource(R.string.cancel)) }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/** KPI 卡：大数字 + 小标签 */
@Composable
private fun KpiCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 统计卡片容器（统一 20dp 圆角） */
@Composable
private fun StatsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** 缓存/元信息行 */
@Composable
private fun CacheRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** 错误条目行 */
@Composable
private fun ErrorRow(entry: PageErrorEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 类型徽标（配色按错误类型）
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(errorColor(entry.type))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${entry.type}(${entry.code})",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                entry.description.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Text(
            DateUtils.formatTimestamp(entry.time),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 错误类型配色：HTTP 橙 / 网络红 / SSL 黄 / RENDER 紫 / JS 青 */
@Composable
private fun errorColor(type: String): androidx.compose.ui.graphics.Color = when (type) {
    com.cylonid.nativealpha.model.ErrorType.HTTP.name -> MaterialTheme.colorScheme.tertiary
    com.cylonid.nativealpha.model.ErrorType.NETWORK.name -> MaterialTheme.colorScheme.error
    com.cylonid.nativealpha.model.ErrorType.SSL.name -> MaterialTheme.colorScheme.secondary
    com.cylonid.nativealpha.model.ErrorType.RENDER.name -> MaterialTheme.colorScheme.primary
    com.cylonid.nativealpha.model.ErrorType.JS.name -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 加载耗时分布柱状图（Vico ColumnChart，按次） */
@Composable
private fun LoadTimeChart(webapp: WebApp) {
    // 真实分布：按耗时区间分桶（标签见常量），展示最近 20 次
    val times = webapp.statLoadTimes ?: emptyList()
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

private fun minLoadTime(webapp: WebApp): Long {
    // 最快加载（近似：平均值与最慢的差值下限）
    return if (webapp.statLoadTimeCount > 0) {
        (webapp.statLoadTimeSum / webapp.statLoadTimeCount).coerceAtMost(webapp.statMaxLoadTime)
    } else 0L
}

/** 时长格式化（ms → 秒/分钟） */
private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "—"
    return if (ms < 1000) "${ms}ms"
    else if (ms < 60000) String.format("%.1fs", ms / 1000.0)
    else String.format("%.1fm", ms / 60000.0)
}

/** 字节格式化（B → KB → MB） */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }
}

/**
 * 生成使用建议（数据→行动）：按统计字段阈值给出可执行建议。
 * 规则：平均加载 >3s / 错误数 >10 / 缓存 >50MB 各出一条；无异常返回空列表。
 */
private fun buildSuggestions(context: android.content.Context, webapp: WebApp): List<String> {
    val tips = mutableListOf<String>()
    val avgLoad = if (webapp.statLoadTimeCount > 0)
        webapp.statLoadTimeSum / webapp.statLoadTimeCount else 0L
    if (avgLoad > SUGGEST_SLOW_LOAD_MS) {
        tips.add(context.getString(R.string.suggestion_slow_load, formatDuration(avgLoad)))
    }
    if (webapp.statErrors > SUGGEST_ERROR_COUNT) {
        tips.add(context.getString(R.string.suggestion_errors, webapp.statErrors))
    }
    if (webapp.statCacheHttpBytes > SUGGEST_CACHE_BYTES) {
        tips.add(context.getString(R.string.suggestion_cache, formatBytes(webapp.statCacheHttpBytes)))
    }
    return tips
}
