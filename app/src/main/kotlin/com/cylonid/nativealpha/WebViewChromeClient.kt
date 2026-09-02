package com.cylonid.nativealpha

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.annotation.StringRes
import android.net.Uri
import android.os.Message
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
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
import android.provider.Settings
import android.webkit.WebChromeClient
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.model.DataManager
import com.google.android.material.snackbar.Snackbar
import com.cylonid.nativealpha.model.ErrorType
import com.cylonid.nativealpha.util.StatsRecorder
import android.webkit.WebView
import com.cylonid.nativealpha.util.NotificationUtils
import android.Manifest
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

    private fun handlePermissionRequest(
        resId: String,
        currentState: Boolean,
        androidPermissions: Array<String>,
        requestCode: Int,
        permissionsToGrant: MutableList<String>,
        webkitPermission: Array<String>,
        successCallback: WebViewActivity.PermissionGrantedCallback
    ) {
        val androidPermissionsMissing = areAndroidPermissionsMissing(androidPermissions)
        if (currentState && androidPermissionsMissing) {
            // 权限审计：区分「首次请求」vs「永久拒绝」（勾选"不再询问"）
            // 全部权限都已请求过 + shouldShowRequestPermissionRationale=false → 永久拒绝，
            // 不再重复弹系统框，引导用户去系统设置手动开启
            val allRequested = androidPermissions.all { it in host.requestedPermissions }
            if (allRequested) {
                var permanentlyDenied = false
                for (perm in androidPermissions) {
                    if (ContextCompat.checkSelfPermission(
                            host, perm
                        ) != PackageManager.PERMISSION_GRANTED
                        && !ActivityCompat.shouldShowRequestPermissionRationale(
                            host, perm
                        )
                    ) {
                        permanentlyDenied = true
                        break
                    }
                }
                if (permanentlyDenied) {
                    host.handleGeoPermissionCallback(false)
                    showPermissionPermanentlyDeniedDialog(resId)
                    return
                }
            }
            androidPermissions.forEach { host.requestedPermissions.add(it) }
            ActivityCompat.requestPermissions(
                host, androidPermissions, requestCode
            )
            return
        }
        if (currentState && !androidPermissionsMissing) {
            permissionsToGrant.addAll(webkitPermission)
            host.handleGeoPermissionCallback(true)
            return
        }

        AlertDialog.Builder(host)
            .setTitle(host.getString(permissionTitleRes(resId)))
            .setMessage(host.getString(permissionDescRes(resId)))
            .setPositiveButton(R.string.yes) { _, _ ->
                host.enablePermissionBoolOnWebApp(successCallback)
                host.handleGeoPermissionCallback(true)
                permissionsToGrant.addAll(webkitPermission)
                if (androidPermissionsMissing) {
                    ActivityCompat.requestPermissions(
                        host, androidPermissions, requestCode
                    )
                }
            }
            .setNegativeButton(R.string.no) { _, _ -> host.handleGeoPermissionCallback(false) }
            .create()
            .show()
    }

    @StringRes
    private fun permissionTitleRes(resId: String): Int = when (resId) {
        "drm" -> R.string.allow_drm_content
        "camera" -> R.string.allow_camera_access
        "microphone" -> R.string.allow_microphone_access
        "location" -> R.string.allow_location_access
        else -> 0
    }

    @StringRes
    private fun permissionDescRes(resId: String): Int = when (resId) {
        "drm" -> R.string.desc_allow_drm
        "camera" -> R.string.desc_allow_camera
        "microphone" -> R.string.desc_allow_microphone
        "location" -> R.string.desc_allow_location
        else -> 0
    }

    private fun areAndroidPermissionsMissing(androidPermissions: Array<String>): Boolean {
        for (perm in androidPermissions) {
            if (ContextCompat.checkSelfPermission(
                    host, perm
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return true
            }
        }
        return false
    }

    /**
     * 权限被永久拒绝：不再重复弹系统框，提示用户去系统设置手动开启。
     */
    private fun showPermissionPermanentlyDeniedDialog(resId: String) {
        val title = host.getString(permissionTitleRes(resId))
        AlertDialog.Builder(host)
            .setTitle(title)
            .setMessage(host.getString(R.string.permission_permanently_denied_msg, title))
            .setPositiveButton(host.getString(R.string.permission_go_to_settings)) { _, _ ->
                try {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        ("package:" + host.packageName).toUri()
                    )
                    host.startActivity(intent)
                } catch (ignored: Exception) {
                    // 无设置页可跳时静默（不影响主功能）
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .show()
    }

    override fun onShowFileChooser(
        webView: WebView?,
        pFilePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        host.filePathCallback = pFilePathCallback
        return try {
            val intent = fileChooserParams.createIntent()
            host.fileChooserLauncher.launch(intent)
            true
        } catch (e: Exception) {
            NotificationUtils.showInfoSnackbar(
                host, host.getString(R.string.no_filemanager),
                Snackbar.LENGTH_LONG
            )
            e.printStackTrace()
            true
        }
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

    override fun onPermissionRequest(request: PermissionRequest) {
        val permissionsToGrant = ArrayList<String>()

        val containsDrmRequest = request.resources
            .contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
        val containsCameraRequest = request.resources
            .contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val containsMicrophoneRequest = request.resources
            .contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

        if (containsDrmRequest) {
            this.handlePermissionRequest(
                "drm", host.webapp!!.isDrmAllowed, arrayOf(), -1, permissionsToGrant,
                arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
            ) { host.webapp!!.isDrmAllowed = true }
        }
        if (containsCameraRequest) {
            this.handlePermissionRequest(
                "camera", host.webapp!!.isCameraPermission,
                arrayOf(Manifest.permission.CAMERA), Const.PERMISSION_CAMERA,
                permissionsToGrant, arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            ) { host.webapp!!.isCameraPermission = true }
        }

        if (containsMicrophoneRequest) {
            this.handlePermissionRequest(
                "microphone", host.webapp!!.isMicrophonePermission,
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.MODIFY_AUDIO_SETTINGS
                ),
                Const.PERMISSION_AUDIO, permissionsToGrant,
                arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            ) { host.webapp!!.isMicrophonePermission = true }
        }

        request.grant(permissionsToGrant.toTypedArray())
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
        host.mGeoPermissionRequestCallback = callback
        host.mGeoPermissionRequestOrigin = origin
        this.handlePermissionRequest(
            "location", host.webapp!!.isAllowLocationAccess,
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            Const.PERMISSION_RC_LOCATION, ArrayList(),
            arrayOf()
        ) { host.webapp!!.isAllowLocationAccess = true }
    }
}
