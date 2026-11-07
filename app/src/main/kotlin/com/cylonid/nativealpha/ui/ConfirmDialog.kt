package com.cylonid.nativealpha.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cylonid.nativealpha.R

/**
 * 通用确认对话框（不可逆动作统一入口）：标题+说明+确认/取消。
 * 从统计页私有实现提取共享（备份导入接入为第二调用点）——高风险动作
 * 的确认形态全应用一致；文案由调用方传入（stringResource 组合期预取）。
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
