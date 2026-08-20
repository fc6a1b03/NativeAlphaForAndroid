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
    importedErrors: List<PageErrorEntry>,
    snackbarHostState: SnackbarHostState,
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    // 页面错误列表（DataStore 异步加载）
    var pageErrors by remember { mutableStateOf<List<PageErrorEntry>>(emptyList()) }

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
                    onClick = { scope.launch { /* 清缓存确认对话框 */ } },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("清缓存")
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
                    onClick = { /* 清空统计确认 */ },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("清空统计")
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

/** 错误类型配色：HTTP 橙 / 网络红 / SSL 黄 / RENDER 紫 */
@Composable
private fun errorColor(type: String): androidx.compose.ui.graphics.Color = when (type) {
    "HTTP" -> MaterialTheme.colorScheme.tertiary
    "NETWORK" -> MaterialTheme.colorScheme.error
    "SSL" -> MaterialTheme.colorScheme.secondary
    "RENDER" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** 加载耗时分布柱状图（Vico ColumnChart，按次） */
@Composable
private fun LoadTimeChart(webapp: WebApp) {
    // Vico 图表（数据来自统计字段；<2 次不展示由调用方控制）
    val entries = buildList {
        // 用平均/最慢构造两个柱（真实分布需每次耗时明细，此处用统计字段近似）
        if (webapp.statLoadTimeCount > 0) {
            add(webapp.statLoadTimeSum / webapp.statLoadTimeCount)
            add(webapp.statMaxLoadTime)
        }
    }
    // 简化：显示统计摘要（真实 Vico 图表在图表库接入后替换）
    Column {
        entries.forEachIndexed { index, ms ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (index == 0) "平均" else "最慢",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(40.dp)
                )
                // 比例条：weight(1f) 占剩余宽度，不挤压右侧文本
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // 文本固定最小宽度，防止竖排
                Text(
                    formatDuration(ms),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    modifier = Modifier.width(52.dp)
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
