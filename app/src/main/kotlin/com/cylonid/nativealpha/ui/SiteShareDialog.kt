package com.cylonid.nativealpha.ui

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.SiteShareCodec
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 站点分享对话框（C-分享：二维码 + 深链）。
 *
 * 二维码内容为 `webnative://add?...` 深链（[SiteShareCodec] 编解码，
 * fail-closed 校验）；对端系统相机扫码 → 唤起本应用添加向导预填。
 * 无按钮（用户定调：关闭/复制没用）——外点/返回键即关；「复制链接」
 * 能力由主页卡片菜单既有 Copy URL 覆盖，不重复。
 *
 * QR 生成在 Default 调度器（512px 编码为 CPU 操作，不占主线程）。
 */
@Composable
internal fun SiteShareDialog(
    webApp: WebApp,
    onDismiss: () -> Unit
) {
    val shareLink = remember(webApp.ID, webApp.baseUrl, webApp.title) {
        SiteShareCodec.buildShareLink(webApp.baseUrl, webApp.title, webApp)
    }
    val qrBitmap by produceState<Bitmap?>(initialValue = null, shareLink) {
        if (shareLink != null) {
            value = withContext(Dispatchers.Default) { generateQrBitmap(shareLink) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(stringResource(R.string.share_site_entry)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (shareLink == null) {
                    Text(
                        text = stringResource(R.string.share_invalid_url),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                } else {
                    // 白底承载（扫码对比度）：二维码黑模块白背景
                    val scanned = qrBitmap
                    if (scanned != null) {
                        Image(
                            bitmap = scanned.asImageBitmap(),
                            contentDescription =
                                stringResource(R.string.share_qr_content_desc),
                            modifier = Modifier
                                .size(240.dp)
                                .background(Color.White)
                        )
                    } else {
                        Spacer(
                            modifier = Modifier
                                .size(240.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(
                        text = stringResource(R.string.share_hint_scan),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    )
}

/** ZXing 生成二维码位图（静默白边 1 模块；失败返回 null） */
private fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? = try {
    val matrix = QRCodeWriter().encode(
        content, BarcodeFormat.QR_CODE, sizePx, sizePx,
        mapOf(EncodeHintType.MARGIN to 1)
    )
    val bitmap = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap[x, y] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    bitmap
} catch (ignored: Exception) {
    null
}
