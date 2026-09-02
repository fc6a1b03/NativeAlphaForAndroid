package com.cylonid.nativealpha.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.OutlinedTextField
import com.cylonid.nativealpha.R

/**
 * 添加向导 Step 2 页面（自 AddWebAppActivity 迁移，零行为变更）：
 * 名称输入（自动识别回填）+ 图标区（获取中/预览/失败重试）+ 自定义图标入口 + 完成按钮。
 */
@Composable
internal fun Step2Content(
    urlText: String,
    nameText: String,
    onNameChange: (String) -> Unit,
    isFetching: Boolean,
    isSaving: Boolean,
    fetchFailed: Boolean,
    fetchedFavicon: Bitmap?,
    customIcon: Bitmap?,
    onPickImage: () -> Unit,
    onResetIcon: () -> Unit,
    onRetry: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 名称（自动识别回填，可编辑）
        OutlinedTextField(
            value = nameText,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.display_name_hint)) },
            placeholder = { Text(stringResource(R.string.display_name_auto)) },
            leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onFinish() })
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 图标区域
        Text(
            text = stringResource(R.string.shortcut_icon),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isFetching -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.fetching_icon),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                customIcon != null || fetchedFavicon != null -> {
                    val iconBmp = customIcon ?: fetchedFavicon
                    if (iconBmp != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconPreview(bmp = iconBmp)
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = onResetIcon) {
                                Text(stringResource(R.string.use_dynamic_icon))
                            }
                        }
                    } else {
                        // 两个源都为空（异常分支）：提示用动态图标
                        Text(
                            text = stringResource(R.string.icon_will_be_generated),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                fetchFailed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.fetch_failed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.retry))
                    }
                }

                else -> Text(
                    text = stringResource(R.string.icon_will_be_generated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 自定义图标入口
        OutlinedButton(
            onClick = onPickImage,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Image, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.custom_icon))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 完成按钮（保存中禁用+转圈：坏站 favicon 补拉 20s+，防连点重复创建）
        Button(
            onClick = onFinish,
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(Icons.Default.Add, contentDescription = null)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(if (isSaving) R.string.saving else R.string.add_to_home_screen))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = urlText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun IconPreview(bmp: Bitmap) {
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(20.dp))
    )
}
