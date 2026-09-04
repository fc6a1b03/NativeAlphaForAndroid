package com.cylonid.nativealpha.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.PageErrorEntry
import com.cylonid.nativealpha.model.PageErrorRepository
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.DateUtils
import com.cylonid.nativealpha.util.StatAccent
import com.cylonid.nativealpha.util.FeatureMetrics
import com.cylonid.nativealpha.util.StatsDailyStore
import com.cylonid.nativealpha.util.WebVitalsEntry
import com.cylonid.nativealpha.util.WebVitalsStore

/**
 * 统计页「站点故事」：7 章叙事排版（英雄/洞察/性能/陪伴(P2)/自动化/习惯/收纳）。
 *
 * 排版由 [StatsSection] 抽象统一（圆角/折叠/零值灰化一处实现）；动效统一走
 * [statsEnter]/[rememberCountUp]；数据装配集中在 [StatsUiState]（A7 单一数据源）。
 * 错误区块（WebAppStatsErrors）与图表/格式化/洞察（WebAppStatsMetrics/StatsInsights）已拆分。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebAppStatsScreen(
    webapp: WebApp,
    revision: Int,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onClearCache: () -> Unit,
    onClearStats: () -> Unit,
    onClearErrors: () -> Unit,
    onOpenReview: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    var reloadTrigger by remember { mutableIntStateOf(0) }
    val data = rememberStatsPageData(webapp, revision, reloadTrigger)
    val accent = StatAccent.accent(context, webapp)
    // 洞察/热力图输入聚合（24 小时桶由按日快照归并）
    val hourBuckets = IntArray(24)
    data.daily.days.values.forEach { e -> e.hours.forEachIndexed { h, c -> hourBuckets[h] += c } }
    val opensPerDay = data.daily.days.mapValues { it.value.opens }

    Scaffold(
        topBar = { StatsTopBar(webapp.title, onBack) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(top = 20.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // §0 英雄卡（相伴天数 + 打开次数）
            StatsHero(webapp, daysTogether(webapp), data.daily.streakWeeks(), Modifier.statsEnter(0))
            Spacer(modifier = Modifier.height(16.dp))
            // §1 洞察句（点击轮换；无命中不占位）
            InsightCard(webapp, data.automation, hourBuckets, Modifier.statsEnter(1))
            Spacer(modifier = Modifier.height(16.dp))
            // §2 性能
            StatsPerformanceCard(webapp, accent, data.vitals, Modifier.statsEnter(2))
            Spacer(modifier = Modifier.height(16.dp))
            // §3 陪伴热力图（按日快照；无任何活跃时不占位）
            StatsHeatmapCard(
                title = stringResource(R.string.stats_companionship),
                opensPerDay = opensPerDay,
                accent = accent,
                modifier = Modifier.statsEnter(3)
            )
            Spacer(modifier = Modifier.height(16.dp))
            // §3.5 月度回顾入口（活跃≥7 天才显示——回顾需要数据积累支撑；
            // 判定与 ReviewData.build 同一纯函数，防口径漂移）
            if (ReviewData.activeDays(data.daily) >= ReviewData.MIN_ACTIVE_DAYS) {
                OutlinedButton(
                    onClick = onOpenReview,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.review_open_entry)) }
                Spacer(modifier = Modifier.height(16.dp))
            }
            // §4 自动化
            StatsAutomationCard(Modifier.statsEnter(4))
            Spacer(modifier = Modifier.height(16.dp))
            // §5 使用习惯
            StatsHabitsCard(webapp, Modifier.statsEnter(5))
            Spacer(modifier = Modifier.height(16.dp))
            // §6 收纳（工具性内容折叠降权；交互状态在区块内自管）
            StatsMaintenanceSections(
                webapp = webapp,
                pageErrors = data.pageErrors,
                onExport = onExport,
                onClearCache = onClearCache,
                onClearStats = onClearStats,
                onClearErrors = onClearErrors,
                onErrorsChanged = { reloadTrigger++ }
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/** 相伴天数（首次使用至今；未使用返回 0 不展示） */
internal fun daysTogether(webapp: WebApp): Int {
    if (webapp.statFirstLoadedAt <= 0L) return 0
    val dayMs = 24L * 60 * 60 * 1000
    return ((System.currentTimeMillis() - webapp.statFirstLoadedAt) / dayMs).toInt() + 1
}

/** 洞察卡：一次一条权重最高，点击轮换全部命中（Wrapped 式叙事；空池不占位） */
@Composable
private fun InsightCard(
    webapp: WebApp,
    automation: Map<String, Long>,
    hourBuckets: IntArray,
    modifier: Modifier = Modifier
) {
    val insights = remember(webapp, automation, hourBuckets) {
        buildInsights(InsightContext(webapp, automation, hourBuckets = hourBuckets))
    }
    if (insights.isEmpty()) return
    var index by remember { mutableIntStateOf(0) }
    val insight = insights[index % insights.size]
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { index++ },
        shape = StatsCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = insight.count?.let { count ->
                    // 契约：count 非空时 pluralsRes 必非空（InsightStrategy 构建侧保证）
                    pluralStringResource(insight.pluralsRes!!, count, count)
                } ?: stringResource(insight.textRes!!, *insight.args.toTypedArray()),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** 页面数据装配（A7 单一数据源）：错误日志/自动化计数/按日快照/Vitals 一次装配 */
internal data class StatsPageData(
    val pageErrors: List<com.cylonid.nativealpha.model.PageErrorEntry> = emptyList(),
    val automation: Map<String, Long> = emptyMap(),
    val daily: StatsDailyStore.Snapshot = StatsDailyStore.Snapshot(),
    val vitals: List<WebVitalsEntry> = emptyList()
)

/** 数据装配（组合期一次性执行；revision/reloadKey 变化强制重读——同引用赋值会被 skip） */
@Composable
private fun rememberStatsPageData(webapp: WebApp, revision: Int, reloadKey: Int): StatsPageData {
    val context = LocalContext.current
    var pageErrors by remember { mutableStateOf(emptyList<com.cylonid.nativealpha.model.PageErrorEntry>()) }
    var daily by remember { mutableStateOf(StatsDailyStore.Snapshot()) }
    var vitals by remember { mutableStateOf(emptyList<WebVitalsEntry>()) }
    LaunchedEffect(webapp.ID, revision, reloadKey) {
        pageErrors = PageErrorRepository.getForSite(context, webapp.ID)
        daily = StatsDailyStore.snapshot(context)
        vitals = WebVitalsStore.getForSite(context, webapp.ID)
    }
    // FeatureMetrics 快照同步读取（内存聚合，无 IO；清理后随 reloadKey 重读）
    val automation = remember(webapp.ID, reloadKey) { FeatureMetrics.moduleSnapshot(FeatureMetrics.MODULE_WEBEVENT) }
    return StatsPageData(pageErrors, automation, daily, vitals)
}

/**
 * §6 收纳区（存储/历史维护/错误日志三折叠）。
 * 交互状态（折叠展开/清空确认对话框）在此自管——遵循错误区块自管先例，
 * 主编排函数只传回调不持有对话框状态。
 */
@Composable
private fun StatsMaintenanceSections(
    webapp: WebApp,
    pageErrors: List<com.cylonid.nativealpha.model.PageErrorEntry>,
    onExport: () -> Unit,
    onClearCache: () -> Unit,
    onClearStats: () -> Unit,
    onClearErrors: () -> Unit,
    onErrorsChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showClearStatsDialog by remember { mutableStateOf(false) }
    StatsSection(
        title = stringResource(R.string.stats_section_storage),
        modifier = modifier.statsEnter(5),
        collapsible = true,
        initiallyCollapsed = true
    ) {
        RowData(stringResource(R.string.cache_http_total), formatBytes(webapp.statCacheHttpBytes))
        RowData(stringResource(R.string.cache_site_storage), formatBytes(webapp.statCacheStoreBytes))
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showClearCacheDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.clear_cache))
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    StatsSection(
        title = stringResource(R.string.stats_history_meta),
        modifier = Modifier.statsEnter(6),
        collapsible = true,
        initiallyCollapsed = true
    ) {
        RowData(
            stringResource(R.string.first_used),
            if (webapp.statFirstLoadedAt > 0) com.cylonid.nativealpha.util.DateUtils.formatTimestamp(webapp.statFirstLoadedAt) else "—"
        )
        RowData(
            stringResource(R.string.last_used),
            if (webapp.statLastUsedAt > 0) com.cylonid.nativealpha.util.DateUtils.formatTimestamp(webapp.statLastUsedAt) else "—"
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { showClearStatsDialog = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.clear_stats))
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
    StatsSection(
        title = stringResource(R.string.stats_error_log),
        modifier = Modifier.statsEnter(7),
        collapsible = true,
        initiallyCollapsed = true,
        isEmpty = pageErrors.isEmpty()
    ) {
        StatsErrorContent(
            errors = pageErrors,
            onExport = onExport,
            onClearErrors = {
                onClearErrors()
                onErrorsChanged()
            }
        )
    }
    if (showClearCacheDialog) {
        ConfirmDialog(
            title = stringResource(R.string.clear_cache),
            text = stringResource(R.string.clear_cache_confirm),
            onConfirm = {
                showClearCacheDialog = false
                onClearCache()
            },
            onDismiss = { showClearCacheDialog = false }
        )
    }
    if (showClearStatsDialog) {
        ConfirmDialog(
            title = stringResource(R.string.clear_stats),
            text = stringResource(R.string.clear_stats_confirm),
            onConfirm = {
                showClearStatsDialog = false
                onClearStats()
            },
            onDismiss = { showClearStatsDialog = false }
        )
    }
}

/** 顶栏（标题+返回，M3 primaryContainer 语义色） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.stats_title, title)) },
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
}

/** 危险操作确认对话框（清缓存/清空统计共用） */
@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
