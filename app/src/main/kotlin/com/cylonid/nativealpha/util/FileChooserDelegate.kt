package com.cylonid.nativealpha.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import com.cylonid.nativealpha.model.AppErrorEntry
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
 * 透传+降级提示）。**取证统一走 ErrorReporter.probe 模式**：关键决策点
 * launch/returned/no_uri/degraded 全打点（INFO 现场+失败降级升级 WARNING
 * 并当场 Snackbar），实机导出错误日志即含完整现场，不依赖 adb。
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
        // Photo Picker 系统授权必然可读：直通不走归一化
        ErrorReporter.probe(
            activity, TAG, "picker_returned",
            fields = mapOf("uri" to uri, "granted" to (uri != null))
        )
        settle(uri?.let { arrayOf(it) })
    }

    private val chooserLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val uris = extractChooserUris(result.resultCode, data)
        // 取证探针（ErrorReporter.probe 统一模式）：返回形态无论成败都记 INFO
        // 现场——厂商兼容问题需要「正常/异常形态对照」才能实锤（v2.3.11
        // ClipData 实锤案例的教训）
        ErrorReporter.probe(
            activity, TAG, "returned",
            fields = mapOf(
                "code" to result.resultCode,
                "type" to data?.type,
                "dataUri" to data?.data,
                "clip" to data?.clipData?.itemCount,
                "flags" to data?.flags?.let { "0x${Integer.toHexString(it)}" },
                "extracted" to uris?.contentToString()
            )
        )
        // 用户实际做了选择（OK）却连 ClipData 兜底都提取不到任何 URI：厂商返回
        // 彻底异常，升级 WARNING。用户取消（CANCELED）正常不升级。
        if (uris == null && result.resultCode == Activity.RESULT_OK) {
            ErrorReporter.probe(
                activity, TAG, "no_uri",
                fields = mapOf(
                    "type" to data?.type,
                    "dataUri" to data?.data,
                    "clip" to data?.clipData?.itemCount,
                    "flags" to data?.flags?.let { "0x${Integer.toHexString(it)}" }
                ),
                level = AppErrorEntry.LEVEL_WARNING
            )
        }
        val outcome = FileChooserUriNormalizer.normalize(activity, uris?.toList())
        // 物理不可读的 URI：页面端只会静默不上传——当场提示 + 升级 WARNING
        if (outcome.degraded.isNotEmpty()) {
            ErrorReporter.probe(
                activity, TAG, "degraded",
                fields = mapOf("uris" to outcome.degraded),
                level = AppErrorEntry.LEVEL_WARNING
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
        // 取证探针：入口分发现场（INFO，正常路径也记——厂商问题需要对照现场）
        ErrorReporter.probe(
            activity, TAG, "launch",
            fields = mapOf(
                "path" to path,
                "accept" to params.acceptTypes.toList(),
                "multiple" to multiple,
                "pickerAvailable" to pickerAvailable
            )
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
            ErrorReporter.report(activity, TAG, "file chooser launch failed", e)
            settle(null)
            false
        }
    }

    /** 回执并清理挂起回调（现场已由 returned/picker_returned 探针记录） */
    private fun settle(value: Array<Uri>?) {
        val callback = pending
        pending = null
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
