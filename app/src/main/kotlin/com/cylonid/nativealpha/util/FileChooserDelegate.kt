package com.cylonid.nativealpha.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import com.cylonid.nativealpha.R
import com.google.android.material.snackbar.Snackbar

/**
 * WebView 文件选择统一委托（宿主/矩阵格共用，R3 单一实现）：
 * onShowFileChooser 的三分发——
 * - 图片类+Photo Picker 可用：系统 Photo Picker（实时相册，截图立即可选）；
 * - 图片类+Picker 不可用（Android 11/12 无 GMS backport 的国产 ROM 常态）：
 *   ACTION_PICK 直连相册（返回 media provider URI、权限完好），替代原
 *   ACTION_GET_CONTENT——实机三修不改的根因取证：GET_CONTENT 被厂商文件
 *   管理器接管后「相册」为二级转发，返回的 URI 常无读权限（grant 断链）
 *   或为 file://，WebView 渲染进程读取失败=上传静默不上（页面无任何报错）；
 * - 非图片/多选：原始 ACTION_GET_CONTENT（文件管理器，文档场景）。
 *
 * 返回归一化收编 FileChooserUriNormalizer（可读直通/file 物化/物理不可读
 * 透传+取证）。**取证双通道，不依赖 adb**：失败/降级分支自动写入应用错误
 * 日志（设置 → 导出错误日志，WARNING 级）+ 当场 Snackbar 提示用户；logcat
 * -s FileChooser 仍有全量 debug 细节供开发者深挖。
 *
 * 使用约束：Activity 构造期实例化（内部 registerForActivityResult 要求）；
 * 同一时刻仅允许一个进行中的选择（防重入返回 false）。
 */
internal class FileChooserDelegate(private val activity: ComponentActivity) {

    /** 分发路径（纯函数可单测） */
    enum class Path { PHOTO_PICKER, GALLERY, LEGACY }

    /** 进行中的 WebView 回调（选完/取消时回执并清空） */
    private var pending: android.webkit.ValueCallback<Array<Uri>>? = null

    private val photoPicker = activity.registerForActivityResult(PickVisualMedia()) { uri ->
        Log.d(TAG, "photo picker returned: $uri")
        // Photo Picker 系统授权必然可读：直通不走归一化
        settle(uri?.let { arrayOf(it) })
    }

    private val chooserLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        Log.d(
            TAG,
            "chooser returned code=${result.resultCode} type=${data?.type} " +
                "flags=0x${Integer.toHexString(data?.flags ?: 0)} " +
                "dataUri=${data?.data} clipCount=${data?.clipData?.itemCount ?: 0}"
        )
        val uris = extractChooserUris(result.resultCode, data)
        // 用户实际做了选择（OK）却连 ClipData 兜底都提取不到任何 URI：厂商返回
        // 彻底异常——写应用错误日志（设置 → 导出错误日志可见）。用户取消
        // （CANCELED）与 ClipData 成功提取（厂商 clipData 形态兼容，见
        // extractChooserUris KDoc）均不记，防噪音。
        if (uris == null && result.resultCode == Activity.RESULT_OK) {
            ErrorReporter.report(
                activity, TAG,
                "user picked a file but no URI extractable " +
                    "(type=${data?.type} dataUri=${data?.data} " +
                    "clip=${data?.clipData?.itemCount ?: 0} " +
                    "flags=0x${Integer.toHexString(data?.flags ?: 0)})",
                level = com.cylonid.nativealpha.model.AppErrorEntry.LEVEL_WARNING
            )
        }
        val outcome = FileChooserUriNormalizer.normalize(activity, uris?.toList())
        // 物理不可读的 URI：页面端只会静默不上传——当场提示 + 错误日志取证
        if (outcome.degraded.isNotEmpty()) {
            ErrorReporter.report(
                activity, TAG,
                "unreadable uri(s) passed through: ${outcome.degraded}",
                level = com.cylonid.nativealpha.model.AppErrorEntry.LEVEL_WARNING
            )
            NotificationUtils.showInfoSnackbar(
                activity,
                activity.getString(R.string.filechooser_unreadable_file),
                Snackbar.LENGTH_LONG
            )
        }
        settle(outcome.uris)
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
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val pickerAvailable = PickVisualMedia.isPhotoPickerAvailable(this.activity)
        val path = decidePath(params.acceptTypes, multiple, pickerAvailable)
        Log.d(
            TAG,
            "launch path=$path accept=${params.acceptTypes.toList()} " +
                "multiple=$multiple pickerAvailable=$pickerAvailable"
        )
        return try {
            when (path) {
                Path.PHOTO_PICKER -> {
                    val mediaType = if (multiple) {
                        PickVisualMedia.ImageAndVideo
                    } else {
                        PickVisualMedia.ImageOnly
                    }
                    photoPicker.launch(PickVisualMediaRequest(mediaType))
                }
                // ACTION_PICK 不支持多选：多选图片且无 Picker 时回退文件管理器
                Path.GALLERY -> chooserLauncher.launch(
                    Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                )
                Path.LEGACY -> chooserLauncher.launch(params.createIntent())
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "launcher failed", e)
            ErrorReporter.report(activity, TAG, "file chooser launch failed", e)
            settle(null)
            false
        }
    }

    /** 回执并清理挂起回调 */
    private fun settle(value: Array<Uri>?) {
        val callback = pending
        pending = null
        Log.d(TAG, "settle uris=${value?.contentToString()}")
        callback?.onReceiveValue(value)
    }

    companion object {
        private const val TAG = "FileChooser"

        /**
         * 选择结果 URI 提取（实机日志实锤的根因修复）：厂商 ROM 把所选 URI
         * 放进 ClipData 而 getData() 为 null（实机导出日志：code=OK、
         * flags=0x1 带读权限、clip=1、dataUri=null），而 framework 的
         * `WebChromeClient.FileChooserParams.parseResult` 只认 getData()，
         * 此形态返回 null → 此前在此静默当「取消」，表现为「相册选截图
         * 不进输入框」。
         * 自提取完全取代 framework parseResult：ClipData 优先（单选/多选
         * 统一）、data 兜底，与 Chromium 新版语义等价且行为跨厂商一致；
         * 两者皆空返回 null（真异常，由调用方记错误日志）。
         */
        internal fun extractChooserUris(resultCode: Int, data: Intent?): Array<Uri>? {
            if (resultCode != Activity.RESULT_OK || data == null) return null
            val fromClip = data.clipData
                ?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }
                .orEmpty()
            return when {
                fromClip.isNotEmpty() -> fromClip.toTypedArray()
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
        }

        /**
         * 分发决策（纯函数，可单测）：
         * 图片类（含空 accept=AI 站通用上传）优先 Photo Picker；
         * Picker 不可用且单选 → ACTION_PICK 直连相册（绕开厂商文件管理器转发，
         * 返回 media provider URI 权限完好）；其余（非图片/多选+无 Picker）
         * 走 ACTION_GET_CONTENT。
         */
        internal fun decidePath(
            acceptTypes: Array<String>,
            multiple: Boolean,
            photoPickerAvailable: Boolean
        ): Path {
            val wantsImageOnly = acceptTypes.any { it.startsWith("image") } || acceptTypes.isEmpty()
            return when {
                wantsImageOnly && photoPickerAvailable -> Path.PHOTO_PICKER
                wantsImageOnly && !multiple -> Path.GALLERY
                else -> Path.LEGACY
            }
        }
    }
}
