package com.cylonid.nativealpha

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.annotation.StringRes
import android.net.Uri
import android.os.Message
import androidx.core.graphics.createBitmap
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.content.Intent
import android.graphics.Canvas
import android.webkit.WebChromeClient
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.AppErrorEntry
import com.cylonid.nativealpha.model.ErrorType
import com.cylonid.nativealpha.util.ErrorReporter
import com.cylonid.nativealpha.util.StatsRecorder
import android.webkit.WebView
import com.cylonid.nativealpha.helper.WebPermissionCoordinator
import com.cylonid.nativealpha.util.NotificationUtils
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.widget.EditText
import android.widget.FrameLayout
import com.cylonid.nativealpha.R

/**
 * WebView 的 WebChromeClient：控制台日志/权限请求/文件选择/全屏视频/
 * 地理定位/HTTP 认证等浏览器外壳事件。
 *
 * 从 WebViewActivity.kt 拆出（R3 治理）：原 private inner class 独立化，
 * Activity 引用经 host 构造参数注入——行为零变更。
 */
internal class CustomWebChromeClient(
    private val host: WebViewActivity
) : WebChromeClient() {
    private var mCustomView: View? = null
    private var mCustomViewCallback: CustomViewCallback? = null
    private var mOriginalOrientation = 0

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        // 统计埋点：页面 JS 错误（未捕获异常/语法错误走 console.error 上报）
        if (consoleMessage != null
            && consoleMessage.messageLevel() ==
            ConsoleMessage.MessageLevel.ERROR
            && !isBrowserConsoleNoise(consoleMessage.message())
        ) {
            StatsRecorder.recordPageError(
                host.webappID, ErrorType.JS.name,
                ErrorType.JS.code,
                consoleMessage.message() ?: "JS error"
            )
            // 诊断日志同步留痕（WARNING）：页面刷新循环前 JS 报错
            // （断连/状态异常）就写在这里，「页面死前遗言」不被噪音淹没
            ErrorReporter.probe(
                host, "PageLoad", "js_console_error",
                fields = mapOf(
                    "msg" to (consoleMessage.message() ?: ""),
                    "src" to (consoleMessage.sourceId() ?: ""),
                    "line" to consoleMessage.lineNumber()
                ),
                level = AppErrorEntry.LEVEL_WARNING
            )
        }
        return false // 不阻断页面（仅采集）
    }

    override fun onShowFileChooser(
        webView: WebView?,
        pFilePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        // 文件选择统一委托（图片类走系统 Photo Picker 实时相册；矩阵同源）
        return host.fileChooserDelegate.onShowFileChooser(
            host, fileChooserParams, pFilePathCallback
        )
    }

    override fun getDefaultVideoPoster(): Bitmap {
        val bitmap = createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawARGB(0, 0, 0, 0)
        return bitmap
    }

    override fun onHideCustomView() {
        (host.window.decorView as FrameLayout).removeView(this.mCustomView)
        this.mCustomView = null
        host.setRequestedOrientation(this.mOriginalOrientation)
        this.mCustomViewCallback!!.onCustomViewHidden()
        this.mCustomViewCallback = null
        host.showSystemBars()
    }

    override fun onShowCustomView(pView: View, pViewCallback: CustomViewCallback) {
        if (this.mCustomView != null) {
            onHideCustomView()
            return
        }
        this.mCustomView = pView
        this.mOriginalOrientation = host.requestedOrientation
        this.mCustomViewCallback = pViewCallback
        (host.window.decorView as FrameLayout)
            .addView(this.mCustomView, FrameLayout.LayoutParams(-1, -1))
        host.hideSystemBars()
    }

    private val permissionCoordinator = WebPermissionCoordinator(
        activity = host,
        readMemory = {
            val w = host.webapp
            WebPermissionCoordinator.WebPermissionMemory(
                drm = w?.isDrmAllowed ?: false,
                camera = w?.isCameraPermission ?: false,
                microphone = w?.isMicrophonePermission ?: false,
                location = w?.isAllowLocationAccess ?: false
            )
        },
        writeMemory = { field, _ ->
            // 站点记忆写回（重构后授权在编排终结点完成，无需 reload 二次请求）
            val w = host.webapp!!
            w.isOverrideGlobalSettings = true
            when (field) {
                WebPermissionCoordinator.MemoryField.DRM -> w.isDrmAllowed = true
                WebPermissionCoordinator.MemoryField.CAMERA -> w.isCameraPermission = true
                WebPermissionCoordinator.MemoryField.MICROPHONE -> w.isMicrophonePermission = true
                WebPermissionCoordinator.MemoryField.LOCATION -> w.isAllowLocationAccess = true
            }
            DataManager.getInstance().replaceWebApp(w)
        },
        requestAndroidPermissions = { permissions, onResult ->
            host.requestRuntimePermissions(permissions, onResult)
        }
    )

    override fun onPermissionRequest(request: PermissionRequest) {
        permissionCoordinator.handleWebPermission(
            resources = request.resources.toList(),
            grant = { request.grant(it.toTypedArray()) },
            deny = { request.deny() }
        )
    }

    override fun onProgressChanged(view: WebView?, progress: Int) {
        // 白屏检测：记录进度推进（用于 20s 无推进超时判定）
        if (progress > host.lastProgress) {
            host.lastProgress = progress
            host.lastProgressTime = System.currentTimeMillis()
            // 进度有推进 → 重新计时（每次推进重置 20s）
            host.scheduleBlankScreenCheck()
        }

        // 主帧导航配对观测：进度在动而主帧导航窗口未开启 = onPageStarted
        // 缺席（2026-10-29 实机 4 会话实锤），对数级打点取证根因
        if (!host.pageChrome.isMainFrameNavActive()) {
            host.pageChrome.noteProgressWithoutNav(progress)
        }

        // 加载动画（唯一加载进度指示）：页面加载期短暂展示（站点 loader 接管前撤）：
        // - progress < 100（整段加载）
        // - 距加载开始 < animalMaxShowMs（避免长期盖住带自带 loader 的慢站）
        if (progress < 100 && host.loadingAnimal != null) {
            // 距加载开始时间；onPageStarted 未触发时（子帧驱动进度）以首个
            // 进度回调为窗口起点——否则 elapsed 恒 0，3 秒上限永久失效
            if (host.pageLoadStartTime2 == 0L) {
                host.pageLoadStartTime2 = System.currentTimeMillis()
            }
            val elapsed = System.currentTimeMillis() - host.pageLoadStartTime2
            if (elapsed < host.animalMaxShowMs) {
                host.startLoadingAnimal()
            } else {
                host.stopLoadingAnimal()
            }
        } else {
            host.stopLoadingAnimal()
        }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        permissionCoordinator.handleGeolocation(origin, callback)
    }

    companion object {
        /**
         * 浏览器自身预警噪音判定（纯函数可单测）：Chromium 把这三类页面侧
         * 代码坏味道以 ERROR 级灌进 console（2026-10-29 实机导出单日 22 条，
         * 全部来自 zcode.z.ai 自身），计入 statErrors 会把统计页错误数灌到虚高、
         * 挤掉 KEY_PAGE_ERRORS 里真错误的配额。它们不反映 WebView 壳的健康度，
         * 统计与诊断日志一并过滤（logcat 原样保留）：
         * - passive listener 里 preventDefault（站点代码问题，浏览器自动降级）
         * - ResizeObserver loop（浏览器良性提示，布局本身已收敛）
         * - aria-hidden 聚焦元素（无障碍警告，无功能影响）
         */
        internal fun isBrowserConsoleNoise(message: String?): Boolean {
            if (message == null) return false
            return message.startsWith("Unable to preventDefault inside passive event listener") ||
                message.startsWith("ResizeObserver loop") ||
                message.startsWith("Blocked aria-hidden on an element")
        }
    }
}
