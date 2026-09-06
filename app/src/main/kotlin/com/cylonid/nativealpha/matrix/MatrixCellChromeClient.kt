package com.cylonid.nativealpha.matrix

import androidx.appcompat.app.AlertDialog
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.net.Uri
import android.webkit.WebView
import android.widget.EditText
import com.cylonid.nativealpha.util.FeatureMetrics
import com.cylonid.nativealpha.util.FileChooserDelegate

/**
 * 矩阵格 Chrome client（v2 能力对齐宿主）：
 * - onReceivedTitle：标题回调驱动工具条文案；
 * - onShowFileChooser：文件/图片上传（FileChooserDelegate 与宿主同源——
 *   图片类走系统 Photo Picker 实时相册）；
 * - onPermissionRequest：相机/麦克风（AI 语音对话），经 MatrixActivity
 *   系统权限请求，结果 grant/deny；
 * - onJsAlert/onJsConfirm/onJsPrompt：AlertDialog 三件套（v1 缺失时
 *   站点 JS 弹窗被静默吞掉）。
 *
 * v1 曾定调「宿主能力不开放给矩阵格」——实机使用证伪：AI 站上传/语音/
 * 弹窗在矩阵内为高频路径，v2 按需开放（能力实现与宿主共用，无双份逻辑）。
 */
internal class MatrixCellChromeClient(
    private val engine: MatrixEngine,
    private val cellIndex: Int,
    private val activity: MatrixActivity,
    private val fileChooser: FileChooserDelegate
) : WebChromeClient() {

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
        // 仅相机/麦克风（AI 语音/拍摄）；其余资源默认拒绝
        val wanted = request.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE ||
                it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }
        if (wanted.isEmpty()) {
            request.deny()
            return
        }
        activity.requestWebPermissions(wanted.toTypedArray(), request)
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
