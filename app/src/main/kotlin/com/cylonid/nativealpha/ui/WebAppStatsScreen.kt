package com.cylonid.nativealpha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.PageErrorEntry
import com.cylonid.nativealpha.model.PageErrorRepository
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.DateUtils

/**
 * 统计页（按 WebApp 进入 · 开发者向）。
 *
 * Bento Grid 布局：KPI 卡 2×2 / 加载耗时柱状图 / 缓存详情 / 错误日志（导出）/ 元信息。
 * 全部复用 M3 组件体系（Card/Snackbar/AlertDialog），深浅色自动适配。
 * 错误区块（WebAppStatsErrors）与图表/格式化/建议（WebAppStatsMetrics）已拆分。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebAppStatsScreen(
    webapp: WebApp,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onClearCache: () -> Unit,
    onClearStats: () -> Unit,
    onClearErrors: () -> Unit,

    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    // 页面错误列表（DataStore 异步加载）
    var pageErrors by remember { mutableStateOf<List<PageErrorEntry>>(emptyList()) }
    // 清缓存/清空统计确认对话框（状态驱动，防误触）
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearStatsDialog by remember { mutableStateOf(false) }

    // 加载该站错误日志（reloadKey 变化时重载——清理后触发刷新）
    var reloadKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(webapp.ID, reloadKey) {
        pageErrors = PageErrorRepository.getForSite(context, webapp.ID)
        // 默认折叠（收起）：不预填 expandedGroups
    }

    // 计算统计指标（0 值显示「—」）
    val avgLoad = if (webapp.statLoadTimeCount > 0)
        webapp.statLoadTimeSum / webapp.statLoadTimeCount else 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title, webapp.title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.stats_back))
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

            // 错误日志（导出/清空对称；分组默认展开，每组限显示10条防过长）
            StatsErrorSection(
                errors = pageErrors,
                onExport = onExport,
                onClearErrors = {
                    onClearErrors()
                    // 清理后重新加载错误日志（防列表不刷新）
                    reloadKey++
                }
            )

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

/** 统计卡片容器（统一 20dp 圆角；WebAppStatsErrors 同用） */
@Composable
internal fun StatsCard(content: @Composable ColumnScope.() -> Unit) {
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
