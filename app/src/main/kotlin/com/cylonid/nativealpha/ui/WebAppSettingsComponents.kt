package com.cylonid.nativealpha.ui

import android.app.TimePickerDialog
import android.content.Context
import android.graphics.ImageDecoder
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.DateUtils
import com.cylonid.nativealpha.util.WebAppIconManager
import java.util.Calendar

/**
 * 设置页基础行组件（从 WebAppSettingsScreen.kt 拆出）：
 * - WebAppSettingsSectionTitle / WebAppSettingsCard / WebAppSettingsActionRow / WebAppSettingsSwitchRow /
 *   WebAppSettingsSliderRow / WebAppSettingsTimeRow：通用设置行骨架，本页各区块共用
 * - [showTimePicker]：时间段深色模式的时间选择弹窗
 * - [IconSettingsRow]：应用头像行（含选择/回填/重置对话框）
 * 与 SettingsScreen.kt 内的同名 private 组件职责相同但互相独立（该文件为文件级私有）。
 */

/** 区块标题 */
@Composable
internal fun WebAppSettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

/** 区块卡片容器 */
@Composable
internal fun WebAppSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
    }
}

/** 可点击动作行（图标 + 标题 + 可选副标题） */
@Composable
internal fun WebAppSettingsActionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

/** 开关设置行（可选图标/描述/高风险警示） */
@Composable
internal fun WebAppSettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    description: String? = null,
    warning: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) { icon() }
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                if (description != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        // 高风险开关警示：调用方传入 warning 时显示（JS 关闭 / 拦截开启 / 禁图开启）
        if (warning != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** 滑杆设置行（字体/页面缩放），50~200% 步进 10 */
@Composable
internal fun WebAppSettingsSliderRow(
    title: String,
    description: String? = null,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (description != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "$value%",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(50, 200)) },
            valueRange = 50f..200f,
            steps = 14
        )
    }
}

/** 时间选择行（深色模式时间段） */
@Composable
internal fun WebAppSettingsTimeRow(
    label: String,
    value: String,
    onClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(value) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 显示时间选择器 */
internal fun showTimePicker(
    context: Context,
    current: String,
    onResult: (String) -> Unit,
) {
    val c = DateUtils.convertStringToCalendar(current) ?: Calendar.getInstance()
    val picker = TimePickerDialog(
        context,
        { _, hour, minute ->
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            onResult(DateUtils.getHourMinFormat().format(cal.time))
        },
        c.get(Calendar.HOUR_OF_DAY),
        c.get(Calendar.MINUTE),
        true
    )
    picker.show()
}

/**
 * 应用头像行（统一源 iconPath）：预览当前头像；点击弹对话框——选择相册图片 /
 * 回填网站图标（favicon）/ 重置为字母渐变图标（iconPath=null）。
 * 保存图标走 WebAppIconManager（唯一写入点）。
 */
@Composable
internal fun IconSettingsRow(
    webApp: WebApp,
    onIconSaved: (String?) -> Unit,
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    // 当前头像（iconPath 有值显示，无值显示字母图标预览）
    val currentIcon = remember(webApp.iconPath) {
        WebAppIconManager.loadIcon(context, webApp)
    }
    // 相册选图：ImageDecoder 解码 → WebAppIconManager 保存 → 回调 iconPath
    val msgPickFailed = stringResource(R.string.icon_pick_failed)
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val bmp = ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(context.contentResolver, uri)
                )
                val ok = WebAppIconManager.saveIcon(context, webApp, bmp)
                if (ok) onIconSaved(webApp.iconPath)
            } catch (e: Exception) {
                Toast.makeText(context, msgPickFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 头像预览 40dp
        if (currentIcon != null) {
            Image(
                bitmap = currentIcon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            val preview = remember(webApp.title, webApp.baseUrl) {
                runCatching {
                    // 与列表/快捷同源（resolveIconCached：iconPath→字母，无网络——组合期 UI 线程安全）
                    WebAppIconManager.resolveIconCached(context, webApp)
                }.getOrNull()
            }
            preview?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                stringResource(R.string.app_icon),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                stringResource(R.string.app_icon_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.app_icon)) },
            text = {
                Text(stringResource(R.string.app_icon_dialog_hint))
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    pickImage.launch("image/*")
                }) {
                    Text(stringResource(R.string.app_icon_pick))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showDialog = false
                        // 重置为字母图标：删除文件 + iconPath=null
                        WebAppIconManager.deleteIcon(context, webApp)
                        onIconSaved(null)
                    }) {
                        Text(stringResource(R.string.app_icon_reset))
                    }
                    TextButton(onClick = {
                        showDialog = false
                        onIconSaved(null)
                    }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
    }
}
