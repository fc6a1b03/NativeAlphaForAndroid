package com.cylonid.nativealpha

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.annotation.StringRes
import android.net.Uri
import android.os.Message
import androidx.core.graphics.createBitmap
import androidx.core.view.isGone
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
import com.google.android.material.snackbar.Snackbar
import com.cylonid.nativealpha.model.ErrorType
import com.cylonid.nativealpha.util.StatsRecorder
import android.webkit.WebView
import com.cylonid.nativealpha.helper.WebPermissionCoordinator
import com.cylonid.nativealpha.util.NotificationUtils
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import com.cylonid.nativealpha.R

/**
 * WebView 的 WebChromeClient：控制台日志/权限请求/文件选择/全屏视频/
 * 进度条/地理定位/HTTP 认证等浏览器外壳事件。
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
        ) {
            StatsRecorder.recordPageError(
                host.webappID, ErrorType.JS.name,
                ErrorType.JS.code,
                consoleMessage.message() ?: "JS error"
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

        // 加载动画：独立于进度条显示开关——页面加载期短暂展示（站点 loader 接管前撤）：
        // - progress < 100（整段加载）
        // - 距加载开始 < ANIMAL_MAX_SHOW_MS（避免长期盖住带自带 loader 的慢站）
        // 注意：放进度条判断外，用户未开启进度条时动画依然可见（此前默认关闭进度条导致动画不显示）
        if (progress < 100 && host.loadingAnimal != null) {
            // 距加载开始时间（首次 onPageStarted 前 pageLoadStartTime2=0 → 视为 0 刚从窗口内开始）
            val elapsed = if (host.pageLoadStartTime2 == 0L) 0L
            else System.currentTimeMillis() - host.pageLoadStartTime2
            if (elapsed < host.animalMaxShowMs) {
                host.startLoadingAnimal()
            } else {
                host.stopLoadingAnimal()
            }
        } else {
            host.stopLoadingAnimal()
        }

        if (DataManager.getInstance().settings.isShowProgressbar || host.currentlyReloading) {
            if (host.progressBar!!.isGone && progress < 100) {
                host.progressBar!!.visibility = ProgressBar.VISIBLE
            }

            // 平滑过渡（150ms），避免进度跳变
            host.progressBar!!.setProgress(progress, true)

            if (progress == 100) {
                host.progressBar!!.visibility = ProgressBar.GONE
                host.currentlyReloading = false
                host.stopLoadingAnimal()
            }
        }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        permissionCoordinator.handleGeolocation(origin, callback)
    }
}
