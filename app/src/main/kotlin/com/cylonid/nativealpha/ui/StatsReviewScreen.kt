package com.cylonid.nativealpha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.StatAccent

/**
 * 月度回顾页（Phase 4，Wrapped 式单页叙事）。
 * 数据由 [StatsReviewData.build] 聚合（调用方保证非 null 才进入本页）。
 * 排版沿用统计页视觉语言（StatsCardShape/two-level corners/数字大字重）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatsReviewScreen(
    webapp: WebApp,
    review: ReviewData,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val accent = StatAccent.accent(LocalContext.current, webapp)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review_title)) },
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
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(20.dp))
            // 主数字：本月总打开
            ReviewBigNumber(
                value = rememberCountUp(review.totalOpens).toString(),
                label = stringResource(R.string.stat_launches)
            )
            Spacer(modifier = Modifier.height(20.dp))
            ReviewRow(stringResource(R.string.review_active_days), review.activeDays.toString())
            ReviewRow(
                stringResource(R.string.review_streak),
                stringResource(R.string.stats_hero_streak_weeks_only, review.streakWeeks)
            )
            if (review.busiestDay != null) {
                ReviewRow(
                    stringResource(R.string.review_busiest_day),
                    stringResource(R.string.review_busiest_day_value, review.busiestDay, review.busiestDayOpens)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            // 性能小节（强调色标题）
            Text(
                stringResource(R.string.stats_section_performance),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
            Spacer(modifier = Modifier.height(8.dp))
            ReviewRow(stringResource(R.string.stat_avg_load), formatDuration(review.avgLoad))
            ReviewRow(stringResource(R.string.review_fastest), formatDuration(review.fastestLoad))
            Spacer(modifier = Modifier.height(20.dp))
            // 自动化小节
            Text(
                stringResource(R.string.stats_section_automation),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = accent
            )
            Spacer(modifier = Modifier.height(8.dp))
            ReviewRow(
                stringResource(R.string.stats_automation_subtitle),
                formatCount(review.notificationShown)
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/** 回顾主数字块（大数字居中） */
@Composable
private fun ReviewBigNumber(value: String, label: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = StatsCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(24.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 回顾数据行（与 RowData 同语言，独立实现避免跨文件耦合私有组件） */
@Composable
private fun ReviewRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
