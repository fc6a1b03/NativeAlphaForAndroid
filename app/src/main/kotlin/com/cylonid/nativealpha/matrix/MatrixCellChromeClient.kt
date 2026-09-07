package com.cylonid.nativealpha.matrix

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.cylonid.nativealpha.helper.WebPermissionCoordinator
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.util.FeatureMetrics
import com.cylonid.nativealpha.util.FileChooserDelegate

/**
 * 矩阵格 Chrome client（v2 能力对齐宿主，v3 权限/地理/全屏补齐）：
 * - onReceivedTitle：标题回调驱动工具条文案；
 * - onConsoleMessage：JS 错误观测（矩阵 QC 口径=FeatureMetrics 计数）；
 * - onShowFileChooser：文件/图片上传（FileChooserDelegate 与宿主同源）；
 * - onPermissionRequest/onGeolocationPermissionsShowPrompt：WebPermissionCoordinator
 *   与宿主同源编排（站点记忆→弹窗确认→系统权限，授权在链路终结点恰好一次，
 *   无 reload 补丁；写回落到同一 WebApp 实例的 replaceWebApp）；
 * - onShowCustomView/onHideCustomView：格内视频全屏（MatrixActivity 装饰层）；
 * - getDefaultVideoPoster：透明 1x1 占位（宿主同款，防视频封面黑块）；
 * - onJsAlert/onJsConfirm/onJsPrompt：AlertDialog 三件套。
 *
 * v1 曾定调「宿主能力不开放给矩阵格」——实机使用证伪后按需开放；v3 起
 * 权限编排与宿主共用同一实现（无双份逻辑）。
 */
internal class MatrixCellChromeClient(
    private val engine: MatrixEngine,
    private val cellIndex: Int,
    private val activity: MatrixActivity,
    private val fileChooser: FileChooserDelegate
) : WebChromeClient() {

    /** 站点权限记忆读写（与宿主同一 WebApp 字段，写入即 replaceWebApp 持久化） */
    private val permissionCoordinator = WebPermissionCoordinator(
        activity = activity,
        readMemory = {
            val webapp = DataManager.getInstance().getWebApp(boundWebappId)
            WebPermissionCoordinator.WebPermissionMemory(
                drm = webapp?.isDrmAllowed ?: false,
                camera = webapp?.isCameraPermission ?: false,
                microphone = webapp?.isMicrophonePermission ?: false,
                location = webapp?.isAllowLocationAccess ?: false
            )
        },
        writeMemory = { field, _ ->
            val webapp = DataManager.getInstance().getWebApp(boundWebappId) ?: return@WebPermissionCoordinator
            webapp.isOverrideGlobalSettings = true
            when (field) {
                WebPermissionCoordinator.MemoryField.DRM -> webapp.isDrmAllowed = true
                WebPermissionCoordinator.MemoryField.CAMERA -> webapp.isCameraPermission = true
                WebPermissionCoordinator.MemoryField.MICROPHONE -> webapp.isMicrophonePermission = true
                WebPermissionCoordinator.MemoryField.LOCATION -> webapp.isAllowLocationAccess = true
            }
            DataManager.getInstance().replaceWebApp(webapp)
        },
        requestAndroidPermissions = { permissions, onResult ->
            activity.requestRuntimePermissions(permissions, onResult)
        }
    )

    /**
     * 发起权限请求时绑定的站点 ID：权限弹窗为异步操作，期间用户可能关格/
     * 换站——实时读 cells 会把记忆写到错误站点，故在 ChromeClient 构造时
     * （loadCell 时 webappId 已绑定）固定捕获。
     */
    private val boundWebappId: Int =
        engine.cellsInternal.value.getOrNull(cellIndex)?.webappId ?: -1

    override fun onReceivedTitle(view: WebView, title: String) {
        engine.onCellTitle(cellIndex, title)
        super.onReceivedTitle(view, title)
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
        // 与宿主同语义的 JS 错误观测（矩阵 QC 口径=FeatureMetrics 计数）
        if (consoleMessage != null &&
            consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR
        ) {
            FeatureMetrics.count(FeatureMetrics.MODULE_MATRIX, "console_error")
        }
        return false // 不阻断页面（仅采集）
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean = fileChooser.onShowFileChooser(activity, fileChooserParams, filePathCallback)

    override fun onPermissionRequest(request: PermissionRequest) {
        permissionCoordinator.handleWebPermission(
            resources = request.resources.toList(),
            grant = { request.grant(it.toTypedArray()) },
            deny = { request.deny() }
        )
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        permissionCoordinator.handleGeolocation(origin, callback)
    }

    override fun getDefaultVideoPoster(): Bitmap {
        // 透明 1x1 占位（宿主同款）：视频首帧未就绪时避免灰块/黑块
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawARGB(0, 0, 0, 0)
        return bitmap
    }

    override fun onShowCustomView(pView: View, pViewCallback: WebChromeClient.CustomViewCallback) {
        activity.showCellCustomView(pView, pViewCallback)
    }

    override fun onHideCustomView() {
        activity.hideCellCustomView()
    }

    override fun onJsAlert(
        view: WebView,
        url: String,
        message: String,
        result: JsResult
    ): Boolean {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsConfirm(
        view: WebView,
        url: String,
        message: String,
        result: JsResult
    ): Boolean {
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsPrompt(
        view: WebView,
        url: String,
        message: String,
        defaultValue: String,
        result: JsPromptResult
    ): Boolean {
        val input = EditText(activity).apply {
            setText(defaultValue)
            setSingleLine(true)
        }
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }
}
