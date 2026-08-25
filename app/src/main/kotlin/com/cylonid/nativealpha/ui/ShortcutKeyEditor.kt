package com.cylonid.nativealpha.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp

/**
 * 快捷键编辑区（从 WebAppSettingsScreen.kt 拆出）：
 * - [ShortcutsSection]：设置页"快捷键"区块——已绑定列表（点删）+ 点选录入行
 * - [ShortcutAddRow]：修饰键点选 + 主键下拉 + 确认绑定（手机触摸操作，无需物理键盘）
 */

/** 快捷键区块：说明文案 + 已绑定列表 + 添加（点选录入） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShortcutsSection(
    modified: WebApp,
    updateSettings: (WebApp.() -> Unit) -> Unit,
) {
    WebAppSettingsSectionTitle(stringResource(R.string.shortcuts_section))
    WebAppSettingsCard {
        Text(
            stringResource(R.string.shortcut_send_to_page),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        // 已绑定列表
        if (modified.keyShortcuts.isEmpty()) {
            Text(
                stringResource(R.string.shortcut_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        } else {
            // 已绑定列表：自适应网格卡片（键名+删除紧凑，自动换行填满宽度）
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                modified.keyShortcuts.forEach { shortcut ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            shortcut,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        // 删除按钮紧贴卡片右侧
                        IconButton(
                            onClick = {
                                updateSettings { keyShortcuts = keyShortcuts.filter { it != shortcut }.toMutableList() }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.shortcut_delete_desc, shortcut),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
        // 添加（点选录入）
        ShortcutAddRow(
            existing = modified.keyShortcuts,
            onAdd = { combo ->
                updateSettings { keyShortcuts = (keyShortcuts + combo).toMutableList() }
            }
        )
    }
}

/** 快捷键点选录入行（手机触摸操作，无需物理键盘） */
@Composable
private fun ShortcutAddRow(
    existing: List<String>,
    onAdd: (String) -> Unit,
) {
    val context = LocalContext.current
    var ctrl by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    // 主键下拉（字母/数字/功能键）
    var key by remember { mutableStateOf("S") }
    var keyExpanded by remember { mutableStateOf(false) }
    // 预取 Toast 文案（回调内不能查资源——Lint LocalContextGetResourceValueCall）
    val msgNeedModifier = stringResource(R.string.shortcut_need_modifier)
    val msgExist = stringResource(R.string.shortcut_exist)
    val msgMaxReached = stringResource(R.string.shortcut_max_reached, MAX_KEY_SHORTCUTS)
    val msgBoundTemplate = stringResource(R.string.shortcut_bound)
    fun buildCombo(): String? {
        val parts = buildList {
            if (ctrl) add("Ctrl")
            if (shift) add("Shift")
            if (alt) add("Alt")
            add(key)
        }
        return if (parts.size >= 2) parts.joinToString("+") else null
    }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        // 修饰键点选
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ctrl,
                onClick = { ctrl = !ctrl },
                label = { Text("Ctrl") }
            )
            FilterChip(
                selected = shift,
                onClick = { shift = !shift },
                label = { Text("Shift") }
            )
            FilterChip(
                selected = alt,
                onClick = { alt = !alt },
                label = { Text("Alt") }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 主键下拉 + 确认
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { keyExpanded = true }) { Text(stringResource(R.string.shortcut_key_label, key)) }
                DropdownMenu(expanded = keyExpanded, onDismissRequest = { keyExpanded = false }) {
                    ShortcutKeys.forEach { k ->
                        DropdownMenuItem(
                            text = { Text(k) },
                            onClick = { key = k; keyExpanded = false }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    val combo = buildCombo() ?: run {
                        Toast.makeText(context, msgNeedModifier, Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (existing.contains(combo)) {
                        Toast.makeText(context, msgExist, Toast.LENGTH_SHORT).show()
                    } else if (existing.size >= MAX_KEY_SHORTCUTS) {
                        Toast.makeText(context, msgMaxReached, Toast.LENGTH_SHORT).show()
                    } else {
                        onAdd(combo)
                        ctrl = false; shift = false; alt = false
                        Toast.makeText(context, String.format(msgBoundTemplate, combo), Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = ctrl || shift || alt
            ) { Text("确认绑定") }
        }
    }
}

/** 每 WebApp 最大快捷键数（防冗余） */
private const val MAX_KEY_SHORTCUTS = 5

/** 可选主键（字母/数字/功能键） */
private val ShortcutKeys = listOf(
    "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
    "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
    "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
    "Enter", "Space", "Tab", "Backspace",
    "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"
)
