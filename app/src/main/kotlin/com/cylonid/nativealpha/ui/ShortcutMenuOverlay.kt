package com.cylonid.nativealpha.ui

import android.app.Activity
import android.view.KeyEvent
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
 * - 已绑定列表：每条左侧「发送」按钮（向页面派发该组合键）、右侧删除
 * - 添加（录制模式）：点「＋」→ 按钮变「请按下组合键...」→ 用户按组合键 → 捕获绑定
 * - 操作反馈：发送 Toast；录制支持 Ctrl/Shift/Alt + 字母/数字/功能键
 * - 限制：每条 WebApp 最多 5 个组合键（防冗余），重复绑定提示
 *
 * Java 调用：ShortcutMenuOverlayKt.showShortcutMenu(activity, webappID, onSendShortcut, ...)
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Activity.showShortcutMenuOverlay(
    webappID: Int,
    onSendShortcut: (String) -> Unit,
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

/** 快捷键面板内容 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortcutMenuSheetContent(
    webappID: Int,
    onSendShortcut: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val webapp = DataManager.getInstance().getWebApp(webappID)
    // 快捷键列表（本地状态，保存时写回）
    var shortcuts by remember {
        mutableStateOf((webapp?.keyShortcuts ?: mutableListOf()).toMutableList())
    }
    // 录制状态：null = 未录制；否则显示「请按下组合键...」
    var recording by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    // 发送快捷键：调用回调 + Toast
    fun sendShortcut(shortcut: String) {
        onSendShortcut(shortcut)
        toastMsg = "已发送 $shortcut"
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
                TextButton(onClick = { saveShortcuts(); onDismiss() }) { Text("完成") }
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
                    onClick = { /* 录制由 dispatchKeyEvent 捕获，此处仅提示 */ },
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
                        if (shortcuts.size >= 5) {
                            android.widget.Toast.makeText(context, "最多 5 个组合键", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            recording = true
                            // 提示用户按键（由 Activity 层 dispatchKeyEvent 捕获）
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
