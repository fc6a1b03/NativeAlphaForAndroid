package com.cylonid.nativealpha.ui

import android.app.Activity
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.util.AppMaterialTheme

/**
 * WebView 长按菜单 —— 在当前 Activity 内叠加显示（不跳转页面，WebView 保留在后面）。
 *
 * 实现：Activity extension 往 content 根视图 addView 一个全屏 ComposeView，
 * 内部 ModalBottomSheet 弹出。菜单关闭时从视图树移除 ComposeView。
 *
 * Java 调用：WebViewMenuOverlayKt.showWebViewMenu(activity, ...)
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Activity.showWebViewMenuOverlay(
    url: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    initialTextZoom: Int,
    initialPageZoom: Int,
    onAction: (String) -> Unit,
    onApplyTextZoom: (Int) -> Unit,
    onApplyPageZoom: (Int) -> Unit,
    onSave: () -> Unit,
) {
    val root = findViewById<ViewGroup>(android.R.id.content) ?: return
    val composeView = ComposeView(this)
    composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    composeView.setContent {
        AppMaterialTheme {
            WebViewMenuSheetContent(
                url = url,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                initialTextZoom = initialTextZoom,
                initialPageZoom = initialPageZoom,
                onAction = onAction,
                onApplyTextZoom = onApplyTextZoom,
                onApplyPageZoom = onApplyPageZoom,
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

/** 菜单内容（叠加层内的 ModalBottomSheet），关闭时回调 onDismiss 移除自身 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebViewMenuSheetContent(
    url: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    initialTextZoom: Int,
    initialPageZoom: Int,
    onAction: (String) -> Unit,
    onApplyTextZoom: (Int) -> Unit,
    onApplyPageZoom: (Int) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var textZoom by remember { mutableIntStateOf(initialTextZoom) }
    var pageZoom by remember { mutableIntStateOf(initialPageZoom) }
    var saving by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = {
            if (!saving) {
                saving = true
                onSave()   // 关闭即保存
                onDismiss()
            }
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
            // 当前 URL
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // ===== 常用操作（图标行）：先保存缩放再执行动作（操作即触发持久化）=====
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MenuIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", enabled = canGoBack) { onSave(); onAction("back") }
                MenuIconButton(Icons.AutoMirrored.Filled.ArrowForward, "前进", enabled = canGoForward) { onSave(); onAction("forward") }
                MenuIconButton(Icons.Default.Refresh, "刷新") { onSave(); onAction("reload") }
                MenuIconButton(Icons.Default.ContentCopy, "复制") { onSave(); onAction("copy") }
                MenuIconButton(Icons.Default.Share, "分享") { onSave(); onAction("share") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MenuIconButton(Icons.Default.Home, "主页") { onSave(); onAction("home") }
                MenuIconButton(Icons.Default.Keyboard, "快捷键") { onSave(); onAction("shortcuts") }
                MenuIconButton(Icons.Default.Close, "关闭") { onSave(); onAction("close") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // ===== 字体缩放（实时预览）=====
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.TextFields, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "字体大小", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                )
                Text("${textZoom}%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = textZoom.toFloat(),
                onValueChange = {
                    textZoom = it.toInt().coerceIn(50, 200)
                    onApplyTextZoom(textZoom)
                },
                valueRange = 50f..200f,
                steps = 14
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ===== 页面缩放（实时预览）=====
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ZoomIn, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "页面缩放", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                )
                Text("${pageZoom}%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = pageZoom.toFloat(),
                onValueChange = {
                    pageZoom = it.toInt().coerceIn(50, 200)
                    onApplyPageZoom(pageZoom)
                },
                valueRange = 50f..200f,
                steps = 14
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun MenuIconButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Icon(
            icon, contentDescription = label,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        )
    }
}
