package com.cylonid.nativealpha.ui

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.ShortcutIconUtils
import com.cylonid.nativealpha.util.WebAppDataFetcher
import com.cylonid.nativealpha.util.WebAppIconManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 重新创建快捷方式弹窗（第三刀：Compose 重写原 ShortcutDialogFragment）。
 *
 * 行为对齐原实现：打开即后台抓取（标题/图标/新 baseUrl），标题可编辑，
 * 图标可从相册选取；OK 时名称/图标回填 WebApp 落库并请求 pin 快捷方式。
 * 抓取统一走 [WebAppDataFetcher]（消灭旧 buildIconMap/fetchWebappData 重复实现），
 * 超时 5s（对齐原 CountDownTimer 打断语义），失败走本地图标兜底 + Toast。 */
@Composable
fun ShortcutRecreateDialog(
    webapp: WebApp,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(webapp.title ?: "") }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var customBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var fetching by remember { mutableStateOf(true) }
    var fetchFailed by remember { mutableStateOf(false) }
    var resolvedUrl by remember { mutableStateOf(webapp.baseUrl) }

    // 后台抓取（超时 5s 对齐原实现；Dispatcher IO，结果回主线程重组）
    LaunchedEffect(webapp.ID) {
        val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) { WebAppDataFetcher.fetch(webapp.baseUrl) }
        }
        val favicon = result?.faviconUrl?.let { url ->
            withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { WebAppDataFetcher.loadBitmap(url) }
            }
        }
        fetching = false
        if (result == null && favicon == null) {
            fetchFailed = true
        } else {
            // 标题过滤挑战页脏文案（对齐原 setShortcutTitle 语义）
            val fetchedTitle = result?.title
            if (!fetchedTitle.isNullOrBlank() &&
                !WebAppDataFetcher.isChallengeTitle(fetchedTitle)
            ) {
                title = fetchedTitle
            }
            // 新 baseUrl 回填（PWA manifest start_url；对齐原 applyNewBaseUrl）
            result?.newBaseUrl?.let { newUrl ->
                resolvedUrl = newUrl
                webapp.baseUrl = newUrl
                if (webapp.title.isNullOrBlank()) {
                    webapp.title = com.cylonid.nativealpha.util.UrlUtils.displayHost(newUrl)
                }
                DataManager.getInstance().saveWebAppData()
            }
            bitmap = favicon
            if (favicon == null) fetchFailed = true
        }
    }

    // 相册选图（对齐原 onActivityResult 语义：自定义图标直接预览）
    val pickIcon = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val source = context.contentResolver.openInputStream(uri)
            customBitmap = source?.use { android.graphics.BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            customBitmap = null
        }
    }

    // 展示图标优先级：自定义 > 抓取 > 本地兜底（iconPath→字母，无网络）
    val preview: Bitmap = customBitmap ?: bitmap ?: WebAppIconManager.resolveIconCached(context, webapp)

    // 失败提示（一次性 Toast，对齐原 showFailedMessage）
    if (fetchFailed) {
        val msg = stringResource(R.string.icon_fetch_failed_line1, webapp.title ?: "") +
            stringResource(R.string.icon_fetch_failed_line2) +
            stringResource(R.string.icon_fetch_failed_line3)
        LaunchedEffect(fetchFailed) {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.re_create_shortcut)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (fetching) {
                        CircularProgressIndicator(modifier = Modifier.size(72.dp))
                    } else {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                    OutlinedButton(onClick = { pickIcon.launch("image/*") }) {
                        Text(stringResource(R.string.custom_icon))
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.shortcut_title)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            // 消费型防抖：双击窗口内只执行一次（连点会弹多个系统 pin 确认框）
            var confirmed by remember { mutableStateOf(false) }
            TextButton(
                onClick = {
                    if (confirmed) return@TextButton
                    confirmed = true
                    val activity = context as? Activity ?: return@TextButton
                    pinShortcut(activity, webapp, customBitmap ?: bitmap, title.ifBlank { webapp.title ?: "Unknown" })
                    onDismiss()
                }
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}

/** pin 快捷方式 + 名称/图标回填落库（对齐原 addShortcutToHomeScreen 语义）。
 *  必须在 UI 线程调用（resolveIconCached 与 ShortcutManagerCompat 均主线程操作）。 */
private fun pinShortcut(activity: Activity, webapp: WebApp, fetched: Bitmap?, title: String) {
    // 名称回填（title 唯一名称——与列表/设置页一致）
    if (title != webapp.title) webapp.title = title
    // 图标回填：抓取成功 → 持久化 iconPath（列表图标即时更新，对齐旧实现）
    if (fetched != null) {
        WebAppIconManager.saveIcon(activity, webapp, fetched)
    }
    DataManager.getInstance().saveWebAppData()

    val intent = com.cylonid.nativealpha.util.WebViewLauncher.createWebViewIntent(webapp, activity)
        ?: return
    val bmp = fetched ?: WebAppIconManager.resolveIconCached(activity, webapp)
    val icon = androidx.core.graphics.drawable.IconCompat.createWithBitmap(bmp)
    val shortcutId = ShortcutIconUtils.pinnedShortcutId(webapp.ID)
    val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(activity, shortcutId)
        .setIcon(icon)
        .setShortLabel(title)
        .setLongLabel(title)
        .setIntent(intent)
        .build()

    val scManager = activity.getSystemService(android.content.pm.ShortcutManager::class.java)
    if (scManager == null || scManager.pinnedShortcuts.none { it.id == shortcutId }) {
        androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(activity, info, null)
    } else {
        com.cylonid.nativealpha.util.NotificationUtils.showToast(
            activity, activity.getString(R.string.shortcut_already_exists),
            android.widget.Toast.LENGTH_SHORT
        )
    }
}

private val FETCH_TIMEOUT_MS = 5_000L
