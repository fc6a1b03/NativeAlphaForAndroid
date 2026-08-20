package com.cylonid.nativealpha.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
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
 * 快捷键发送面板（ModalBottomSheet）。
 *
 * 管理入口在 WebApp 设置页（点选录入）；本面板只负责**发送**已绑定快捷键。
 * - 已绑定列表：每条「发送」按钮（向页面派发该组合键）
 * - 无绑定时提示去设置页管理
 *
 * Java 调用：ShortcutMenuOverlayKt.showShortcutMenuOverlay(activity, webappID, onSendShortcut)
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Activity.showShortcutMenuOverlay(
    webappID: Int,
    onSendShortcut: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val root = findViewById<ViewGroup>(android.R.id.content) ?: return
    val composeView = ComposeView(this)
    composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    composeView.setContent {
        AppMaterialTheme {
            ShortcutMenuSheetContent(
                webappID = webappID,
                onSendShortcut = onSendShortcut,
                onOpenSettings = onOpenSettings,
                onDismiss = { root.removeView(composeView) }
            )
        }
    }
    root.addView(
        composeView,
        ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    )
}

/** 快捷键发送面板内容 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortcutMenuSheetContent(
    webappID: Int,
    onSendShortcut: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val webapp = DataManager.getInstance().getWebApp(webappID)
    // 已绑定快捷键（Gson 旧数据可能 null → 安全兜底）
    val shortcuts = remember(webappID) {
        (webapp?.keyShortcuts ?: mutableListOf()).toList()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                // 管理入口：跳 WebApp 设置页
                TextButton(onClick = {
                    onDismiss()
                    onOpenSettings()
                }) { Text("管理") }
            }
            Text(
                "发送到当前页面的组合键",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (shortcuts.isEmpty()) {
                // 无绑定：提示去设置页管理
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "暂无快捷键",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "在 WebApp 设置页添加后，可在此一键发送",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 已绑定列表：自适应 Flow 网格（每项一个卡片，自动换行填满宽度）
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    shortcuts.forEach { shortcut ->
                        // 每个快捷键一个卡片：发送按钮 + 键名（自适应宽度）
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable {
                                    onSendShortcut(shortcut)
                                    android.widget.Toast.makeText(context, "已发送 $shortcut", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Send, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                shortcut,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 提示
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Settings, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "组合键在 WebApp 设置页管理，只发送给页面不触发浏览器默认",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
