package com.cylonid.nativealpha.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.util.AppMaterialTheme

/**
 * 组合快捷键面板（ModalBottomSheet）。
 *
 * 录制闭环：
 * - 面板点「添加组合键」→ onRecordingChanged(true) → Activity 置 shortcutRecording
 * - 用户按组合键 → Activity dispatchKeyEvent 捕获 → 保存 WebApp → notifyShortcutRecorded()
 * - notifyShortcutRecorded 调用本文件注册槽 → 面板加入列表 + 退出录制态 + Toast
 *
 * 限制：每条 WebApp 最多 5 个组合键（防冗余），重复绑定提示。
 *
 * Java 调用：ShortcutMenuOverlayKt.showShortcutMenuOverlay(activity, webappID, ...)
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Activity.showShortcutMenuOverlay(
    webappID: Int,
    onSendShortcut: (String) -> Unit,
    onRecordingChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    val root = findViewById<ViewGroup>(android.R.id.content) ?: return
    val composeView = ComposeView(this)
    composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    composeView.setContent {
        AppMaterialTheme {
            ShortcutMenuSheetContent(
                webappID = webappID,
                onSendShortcut = onSendShortcut,
                onRecordingChanged = onRecordingChanged,
                onSave = onSave,
                onDismiss = { root.removeView(composeView) }
            )
        }
    }
    root.addView(
        composeView,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    )
}

/**
 * 录制完成处理器注册槽（面板打开时注册，关闭时清空）。
 * Activity 捕获组合键并保存到 WebApp 后调用 notifyShortcutRecorded 触发面板刷新。
 */
private var recordedHandler: ((String) -> Unit)? = null

/** 每 WebApp 最大快捷键数（防冗余） */
private const val MAX_SHORTCUTS = 5

/** Activity 层调用：通知面板「组合键已录制」 */
fun notifyShortcutRecorded(shortcut: String) {
    recordedHandler?.invoke(shortcut)
}

/** 快捷键面板内容 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortcutMenuSheetContent(
    webappID: Int,
    onSendShortcut: (String) -> Unit,
    onRecordingChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val webapp = DataManager.getInstance().getWebApp(webappID)
    // 快捷键列表（本地状态，保存时写回；Gson 旧数据可能为 null → 安全兜底）
    var shortcuts by remember {
        mutableStateOf((webapp?.keyShortcuts ?: mutableListOf()).toMutableList())
    }
    // 录制状态：true = 等待用户按键（由 Activity dispatchKeyEvent 捕获）
    var recording by remember { mutableStateOf(false) }

    // 注册录制完成处理器（面板生命周期内有效）
    DisposableEffect(Unit) {
        recordedHandler = { shortcut ->
            if (shortcut.isNotBlank()) {
                if (shortcuts.contains(shortcut)) {
                    android.widget.Toast.makeText(context, "已存在该组合键", android.widget.Toast.LENGTH_SHORT).show()
                } else if (shortcuts.size >= MAX_SHORTCUTS) {
                    android.widget.Toast.makeText(context, "最多 $MAX_SHORTCUTS 个组合键", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    shortcuts = (shortcuts + shortcut).toMutableList()
                    android.widget.Toast.makeText(context, "已绑定 $shortcut", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            recording = false
            onRecordingChanged(false)
        }
        onDispose {
            recordedHandler = null
        }
    }

    // 发送快捷键：调用回调 + Toast
    fun sendShortcut(shortcut: String) {
        onSendShortcut(shortcut)
        android.widget.Toast.makeText(context, "已发送 $shortcut", android.widget.Toast.LENGTH_SHORT).show()
    }

    // 保存快捷键到 WebApp
    fun saveShortcuts() {
        val w = DataManager.getInstance().getWebApp(webappID) ?: return
        w.keyShortcuts = shortcuts
        DataManager.getInstance().replaceWebApp(w)
        onSave()
    }

    ModalBottomSheet(
        onDismissRequest = {
            saveShortcuts()
            onRecordingChanged(false)
            onDismiss()
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Keyboard, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "快捷键", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    saveShortcuts()
                    onRecordingChanged(false)
                    onDismiss()
                }) { Text("完成") }
            }
            Text(
                "发送到当前页面的组合键",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 已绑定列表
            shortcuts.forEach { shortcut ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { sendShortcut(shortcut) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 发送按钮
                    Button(
                        onClick = { sendShortcut(shortcut) },
                        modifier = Modifier.weight(0.4f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("发送", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        shortcut,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    // 删除
                    IconButton(onClick = { shortcuts = shortcuts.filter { it != shortcut }.toMutableList() }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            }

            // 添加按钮 / 录制状态
            if (recording) {
                OutlinedButton(
                    onClick = { /* 录制由 Activity dispatchKeyEvent 捕获 */ },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("请按下组合键...（Ctrl/Shift/Alt + 键）")
                }
            } else {
                OutlinedButton(
                    onClick = {
                        if (shortcuts.size >= MAX_SHORTCUTS) {
                            android.widget.Toast.makeText(context, "最多 $MAX_SHORTCUTS 个组合键", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            recording = true
                            onRecordingChanged(true)
                            android.widget.Toast.makeText(context, "请按下组合键", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("添加组合键")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 提示
            Text(
                "ⓘ 组合键只发送给页面，不触发浏览器默认功能",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
