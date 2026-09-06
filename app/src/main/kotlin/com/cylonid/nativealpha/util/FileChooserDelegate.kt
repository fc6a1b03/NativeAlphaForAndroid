package com.cylonid.nativealpha.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia

/**
 * WebView 文件选择统一委托（宿主/矩阵格共用，R3 单一实现）：
 * onShowFileChooser 的双路径分发——
 * - 图片类：系统 Photo Picker（实时相册，截图立即可选；不受厂商文件管理器
 *   接管 ACTION_GET_CONTENT 后「找不到图片/相册转发丢 URI」的影响）；
 * - 非图片/不支持 Picker：原始 ACTION_GET_CONTENT（文件管理器，文档场景）。
 *
 * 使用约束：Activity 构造期实例化（内部 registerForActivityResult 要求）；
 * 同一时刻仅允许一个进行中的选择（防重入返回 false）。
 */
internal class FileChooserDelegate(private val activity: ComponentActivity) {

    /** 进行中的 WebView 回调（选完/取消时回执并清空） */
    private var pending: android.webkit.ValueCallback<Array<Uri>>? = null

    private val photoPicker = activity.registerForActivityResult(PickVisualMedia()) { uri ->
        settle(uri?.let { arrayOf(it) })
    }

    private val legacyChooser = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        settle(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    /**
     * onShowFileChooser 的实现体。
     * @return true=已接管选择流程（WebView 等待回调）；false=无法处理（调用方兜底）
     */
    fun onShowFileChooser(
        activity: Activity,
        params: WebChromeClient.FileChooserParams,
        callback: android.webkit.ValueCallback<Array<Uri>>
    ): Boolean {
        if (pending != null) return false
        pending = callback
        // 图片类走 Photo Picker（仅图片/空 accept；含视频等多类型走文件管理器）
        val wantsImageOnly = params.acceptTypes.any { it.startsWith("image") } ||
            params.acceptTypes.isEmpty()
        return try {
            if (wantsImageOnly && PickVisualMedia.isPhotoPickerAvailable(activity)) {
                val mediaType = if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    PickVisualMedia.ImageAndVideo
                } else {
                    PickVisualMedia.ImageOnly
                }
                photoPicker.launch(PickVisualMediaRequest(mediaType))
            } else {
                legacyChooser.launch(params.createIntent())
            }
            true
        } catch (e: Exception) {
            settle(null)
            false
        }
    }

    /** 回执并清理挂起回调 */
    private fun settle(value: Array<Uri>?) {
        pending?.onReceiveValue(value)
        pending = null
    }
}
