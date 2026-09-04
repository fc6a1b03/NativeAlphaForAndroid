package com.cylonid.nativealpha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.ErrorType
import com.cylonid.nativealpha.model.PageErrorEntry
import com.cylonid.nativealpha.util.DateUtils

/**
 * 统计页错误日志内容（外层卡片容器已上移 StatsSection 统一抽象）：
 * 分类占比条 + 导出/清空按钮 + 按类型分组列表 + 详情/清空确认对话框。
 * 区块内交互状态（分组展开/选中错误/清空确认）在此自管——仅本区块消费。
 */
@Composable
internal fun StatsErrorContent(
    errors: List<PageErrorEntry>,
    onExport: () -> Unit,
    onClearErrors: () -> Unit,
) {
    // 分组展开状态（key=错误类型，true=展开显示明细）
    var expandedGroups by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 当前查看详情的错误（点击行弹出，null=未选中）
    var selectedError by remember { mutableStateOf<PageErrorEntry?>(null) }
    // 清空错误日志确认对话框
    var showClearErrorsDialog by remember { mutableStateOf(false) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.stats_error_log),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            // 清空按钮（清空该站错误日志）
            if (errors.isNotEmpty()) {
                IconButton(onClick = {
                    // 确认后清空（防误触）
                    showClearErrorsDialog = true
                }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.stats_clear), modifier = Modifier.size(18.dp))
                }
            }
            // 导出按钮（错误日志只导出不导入）
            IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.stats_export), modifier = Modifier.size(18.dp))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        ErrorMixBar(errors)
        if (errors.isEmpty()) {
            Text(
                stringResource(R.string.no_errors),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            // 按错误类型分组（默认全展开：LaunchedEffect 预填所有 type；
            // 点击正常开/关——此前 isEmpty() hack 导致开关失效）
            val grouped = errors.groupBy { it.type }
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
                        if (entries.size > 10) stringResource(R.string.stats_top_n_of, 10, entries.size) else pluralStringResource(R.plurals.stats_count_n, entries.size, entries.size),
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
                // 展开时显示明细（默认展开；每组最多10条，展开显示全部）
                if (expanded) {
                    val showEntries = if (entries.size > 10) entries.take(10) else entries
                    showEntries.forEach { entry ->
                        ErrorRow(entry, onClick = { selectedError = entry })
                        HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                    }
                    if (entries.size > 10) {
                        Text(
                            pluralStringResource(R.plurals.stats_total_n_tap_detail, entries.size, entries.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
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

    // 清空错误日志确认对话框
    if (showClearErrorsDialog) {
        AlertDialog(
            onDismissRequest = { showClearErrorsDialog = false },
            title = { Text(stringResource(R.string.clear_errors_title)) },
            text = { Text(stringResource(R.string.clear_errors_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearErrorsDialog = false
                    onClearErrors()
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearErrorsDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
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

/** 错误分类占比条：单条横向堆叠，宽度=类型占比（色沿用 errorColor 语义映射） */
@Composable
private fun ErrorMixBar(errors: List<PageErrorEntry>) {
    if (errors.isEmpty()) return
    val grouped = errors.groupBy { it.type }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        grouped.forEach { (_, entries) ->
            Box(
                modifier = Modifier
                    .weight(entries.size.toFloat())
                    .height(8.dp)
                    .background(errorColor(entries.first().type))
            )
        }
    }
}

/** 错误类型配色：HTTP 橙 / 网络红 / SSL 黄 / RENDER 紫 / JS 青 */
@Composable
private fun errorColor(type: String): Color = when (type) {
    ErrorType.HTTP.name -> MaterialTheme.colorScheme.tertiary
    ErrorType.NETWORK.name -> MaterialTheme.colorScheme.error
    ErrorType.SSL.name -> MaterialTheme.colorScheme.secondary
    ErrorType.RENDER.name -> MaterialTheme.colorScheme.primary
    ErrorType.JS.name -> MaterialTheme.colorScheme.tertiaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
