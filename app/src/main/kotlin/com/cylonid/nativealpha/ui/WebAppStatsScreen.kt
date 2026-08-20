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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Bento Grid 布局：KPI 卡 2×2 / 加载耗时柱状图 / 缓存详情 / 错误日志（导入导出）/ 元信息。
 * 全部复用 M3 组件体系（Card/Snackbar/AlertDialog），深浅色自动适配。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebAppStatsScreen(
    webapp: WebApp,
    onBack: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onClearCache: () -> Unit,
    onClearStats: () -> Unit,
    importedErrors: List<PageErrorEntry>,
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
    val allErrors = importedErrors + pageErrors  // 导入数据仅展示层合并（不落盘）

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("统计 · ${webapp.displayName ?: webapp.title}") },
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
                KpiCard("打开次数", webapp.statLaunches.toString(), Modifier.weight(1f))
                KpiCard("平均主体加载", formatDuration(avgLoad), Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                KpiCard("HTTP 缓存", formatBytes(webapp.statCacheHttpBytes), Modifier.weight(1f))
                KpiCard("页面错误", webapp.statErrors.toString(), Modifier.weight(1f))
            }

            // 使用建议（数据→行动：加载慢/错误多/缓存大给出可执行建议）
            val suggestions = buildSuggestions(webapp)
            if (suggestions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                StatsCard {
                    Text("使用建议", style = MaterialTheme.typography.titleSmall)
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
                Text("加载耗时分布", style = MaterialTheme.typography.titleSmall)
                Text(
                    "主体加载耗时（不含流式内容生成）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (webapp.statLoadTimeCount >= 2) {
                    LoadTimeChart(webapp)
                } else {
                    Text(
                        "使用 2 次后展示分布图",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "平均 ${formatDuration(avgLoad)} · 最快 ${formatDuration(minLoadTime(webapp))} · 最慢 ${formatDuration(webapp.statMaxLoadTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 缓存详情
            StatsCard {
                Text("缓存详情", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                CacheRow("HTTP 缓存总量", formatBytes(webapp.statCacheHttpBytes))
                CacheRow("站点存储（localStorage 等）", formatBytes(webapp.statCacheStoreBytes))
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showClearCacheDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("清缓存")
                }
                // 清缓存确认对话框（状态驱动，防误触）
                if (showClearCacheDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearCacheDialog = false },
                        title = { Text("清缓存") },
                        text = { Text("将清除本应用的全部 WebView 缓存与站点存储，确定？") },
                        confirmButton = {
                            TextButton(onClick = {
                                showClearCacheDialog = false
                                onClearCache()
                            }) { Text("确定") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearCacheDialog = false }) { Text("取消") }
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
                    // 导入/导出按钮（对称并排，不割裂）
                    IconButton(onClick = onImport, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Upload, contentDescription = "导入", modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Download, contentDescription = "导出", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                if (allErrors.isEmpty()) {
                    Text(
                        "✅ 此站点无错误记录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    allErrors.forEach { entry ->
                        ErrorRow(entry)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 元信息 + 清空统计
            StatsCard {
                Text("元信息", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                CacheRow("首次使用", if (webapp.statFirstLoadedAt > 0) DateUtils.formatTimestamp(webapp.statFirstLoadedAt) else "—")
                CacheRow("最近使用", if (webapp.statLastUsedAt > 0) DateUtils.formatTimestamp(webapp.statLastUsedAt) else "—")
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
                    Text("清空统计")
                }
                // 清空统计确认对话框（状态驱动，防误触）
                if (showClearStatsDialog) {
                    AlertDialog(
                        onDismissRequest = { showClearStatsDialog = false },
                        title = { Text("清空统计") },
                        text = { Text("将重置本应用的打开次数、加载耗时与错误计数，确定？") },
                        confirmButton = {
                            TextButton(onClick = {
                                showClearStatsDialog = false
                                onClearStats()
                            }) { Text("确定") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearStatsDialog = false }) { Text("取消") }
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
private fun ErrorRow(entry: PageErrorEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
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
                    "${count}次",
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
private fun buildSuggestions(webapp: WebApp): List<String> {
    val tips = mutableListOf<String>()
    val avgLoad = if (webapp.statLoadTimeCount > 0)
        webapp.statLoadTimeSum / webapp.statLoadTimeCount else 0L
    if (avgLoad > SUGGEST_SLOW_LOAD_MS) {
        tips.add("平均加载 ${formatDuration(avgLoad)} 偏慢，可在设置中尝试「桌面版请求」或检查网络")
    }
    if (webapp.statErrors > SUGGEST_ERROR_COUNT) {
        tips.add("已有 ${webapp.statErrors} 次页面错误，建议查看下方错误日志定位问题")
    }
    if (webapp.statCacheHttpBytes > SUGGEST_CACHE_BYTES) {
        tips.add("HTTP 缓存已达 ${formatBytes(webapp.statCacheHttpBytes)}，可在下方清理释放空间")
    }
    return tips
}
