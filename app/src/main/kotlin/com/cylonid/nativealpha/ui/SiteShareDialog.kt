package com.cylonid.nativealpha.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
 * 扫码不内置（避免引入 GMS/CameraX 依赖）——桌面端/浏览器打开同一
 * 链接的场景由系统应用竞争接管，本应用只承诺自家深链语义。
 *
 * QR 生成在 Default 调度器（512px 编码为 CPU 操作，不占主线程）。
 */
@Composable
internal fun SiteShareDialog(
    webApp: WebApp,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val shareLink = remember(webApp.ID, webApp.baseUrl, webApp.title) {
        SiteShareCodec.buildShareLink(webApp.baseUrl, webApp.title)
    }
    val msgCopied = stringResource(R.string.share_link_copied)
    val qrBitmap by produceState<Bitmap?>(initialValue = null, shareLink) {
        if (shareLink != null) {
            value = withContext(Dispatchers.Default) { generateQrBitmap(shareLink) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (shareLink != null) {
                        val clipboard = context.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE
                        ) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText("url", shareLink)
                        )
                        Toast.makeText(context, msgCopied, Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = shareLink != null
            ) { Text(stringResource(R.string.share_copy_link)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.share_close)) }
        },
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
                    Spacer(modifier = Modifier.height(12.dp))
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
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bitmap
} catch (ignored: Exception) {
    null
}
