package com.cylonid.nativealpha

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.StatsRecorder
import com.cylonid.nativealpha.model.ErrorType
import android.Manifest
import android.app.DownloadManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.util.NotificationUtils
import com.google.android.material.snackbar.Snackbar
import java.net.URLDecoder

/**
 * WebView 的 WebViewClient：页面开始/结束/错误回调、SSL 拦截、
 * 渲染进程崩溃恢复、请求拦截与 URL 分流。
 *
 * 从 WebViewActivity.kt 拆出（R3 治理）：原 private inner class 独立化，
 * Activity 引用经 host 构造参数注入——行为零变更。
 */
@SuppressLint("MissingOnRenderProcessGone")
internal class CustomBrowser(
    private val host: WebViewActivity
) : WebViewClient() {
    // 注：onRenderProcessGone 已在下方 override 并实现崩溃恢复；lint 在此类定义处误报，
    // 因方法签名包含 API 26+ 的 RenderProcessGoneDetail，minSdk=31 已完全支持。

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        authHost: String,
        realm: String
    ) {
        host.showHttpAuthDialog(handler, authHost, realm)
    }

    override fun onPageFinished(view: WebView, url: String) {
        // 加载完成：取消白屏检测（避免误判）
        host.pageLoadFinished = true
        host.cancelBlankScreenCheck()
        // 页面加载完成：隐藏加载页动物动画
        host.stopLoadingAnimal()
        // 统计埋点：主体加载耗时（started 到 finished）
        if (host.pageLoadStartTime > 0) {
            StatsRecorder.recordPageLoaded(
                host.webappID, System.currentTimeMillis() - host.pageLoadStartTime
            )
            host.pageLoadStartTime = 0
        }
        // 统计埋点：缓存占用（cacheDir + WebStorage，异步不阻塞）
        host.recordCacheUsage()
        if (url == "about:blank") {
            host.loadCustomErrorPage("blank", "")
        }
        host.wv!!.evaluateJavascript(
            "document.addEventListener(\"visibilitychange\"," +
                "function (event) {event.stopImmediatePropagation();},true);", null
        )
        // 移除图片 title/alt 属性（防止 WebView 查看图片时显示图片名浮层遮挡）。
        // MutationObserver 持续清除（SPA 动态图片）；busy 标志防递归
        // （clean 修改属性会再触发 observer）
        host.wv!!.evaluateJavascript(
            "(function(){"
                + "var busy=false;"
                + "var clean=function(){"
                + "if(busy)return;busy=true;"
                + "document.querySelectorAll('img').forEach(function(i){i.removeAttribute('title');i.removeAttribute('alt');});"
                + "busy=false;"
                + "};"
                + "clean();"
                + "var mo=new MutationObserver(function(){clean();});"
                + "mo.observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['title','alt']});"
                + "})()",
            null
        )
        // 页面缩放：zoomBy 模拟捏合（对移动版自适应页面可靠）
        host.applyPageZoom()
        super.onPageFinished(view, url)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        // 新页面加载：重置白屏检测（进度从 0 重新计时）
        host.pageLoadFinished = false
        host.lastProgress = 0
        host.lastProgressTime = System.currentTimeMillis()
        // 加载动画计时起点（短暂显示窗口判定）
        host.pageLoadStartTime2 = System.currentTimeMillis()
        host.scheduleBlankScreenCheck()
        // 统计埋点：记录加载开始时间
        host.pageLoadStartTime = System.currentTimeMillis()
        super.onPageStarted(view, url, favicon)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        // 仅主框架错误处理（子资源错误不统计防噪音）
        if (request.isForMainFrame) {
            val code = error?.errorCode?.toString() ?: "unknown"
            val desc = error?.description?.toString() ?: ""
            // 统计埋点：记录页面错误
            StatsRecorder.recordPageError(host.webappID, ErrorType.NETWORK.name, code, desc)
            // 记录重试目标 + 加载自定义错误页（不显示系统默认白屏）
            host.retryUrl = request.url?.toString() ?: host.urlOnFirstPageload
            host.loadCustomErrorPage(code, desc)
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        // 统计埋点：HTTP 状态码错误（主框架）
        if (request.isForMainFrame) {
            StatsRecorder.recordPageError(
                host.webappID, ErrorType.HTTP.name,
                errorResponse.statusCode.toString(),
                "HTTP error"
            )
        }
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail
    ): Boolean {
        // 渲染进程崩溃/OOM：避免整个应用崩溃，提示用户并关闭页面
        StatsRecorder.recordPageError(
            host.webappID, ErrorType.RENDER.name,
            ErrorType.RENDER.code, "Render process gone"
        )
        host.runOnUiThread {
            NotificationUtils.showInfoSnackbar(
                host,
                host.getString(R.string.render_process_gone),
                Snackbar.LENGTH_LONG
            )
            host.finish()
        }
        return true // 已处理，阻止系统终止应用
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (host.urlOnFirstPageload == "") host.urlOnFirstPageload = request.url.toString()

        if (host.webapp!!.isBlockThirdPartyRequests) {
            val uri = request.url
            val webappUri = host.webapp!!.baseUrl.toUri()

            if (uri.host != null) {
                if (!uri.host!!.endsWith(webappUri.host!!)) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }
            }
        }
        return super.shouldInterceptRequest(view, request)
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError
    ) {
        // 业务设计：专家设置中可开启"忽略 SSL 错误"；默认情况下弹出对话框让用户自主选择
        // 是否继续访问（常用于自签名证书站点）。此行为由用户配置驱动，非默认放行。
        // This option is hidden in "expert settings"
        if (host.webapp!!.isIgnoreSslErrors) {
            handler.proceed()
            return
        }

        // 统计埋点：SSL 错误
        StatsRecorder.recordPageError(
            host.webappID, ErrorType.SSL.name,
            error.primaryError.toString(), "SSL error"
        )

        val builder = AlertDialog.Builder(host)

        var message = host.getString(R.string.ssl_error_msg_line1) + " "
        when (error.primaryError) {
            SslError.SSL_UNTRUSTED ->
                message += host.getString(R.string.ssl_error_unknown_authority) + "\n"
            SslError.SSL_EXPIRED ->
                message += host.getString(R.string.ssl_error_expired) + "\n"
            SslError.SSL_IDMISMATCH ->
                message += host.getString(R.string.ssl_error_id_mismatch) + "\n"
            SslError.SSL_NOTYETVALID ->
                message += host.getString(R.string.ssl_error_notyetvalid) + "\n"
        }
        message += host.getString(R.string.ssl_error_msg_line2) + "\n"

        builder.setTitle(host.getString(R.string.ssl_error_title))
        builder.setMessage(message)
        builder.setIcon(android.R.drawable.ic_dialog_alert)
        builder.setPositiveButton(host.getString(android.R.string.cancel)) { _, _ -> handler.cancel() }
        builder.setNegativeButton(host.getString(R.string.load_anyway)) { _, _ -> handler.proceed() }
        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            .setTextColor(
                ContextCompat.getColor(
                    host, android.R.color.holo_red_dark
                )
            )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(
                ContextCompat.getColor(
                    host, android.R.color.holo_green_dark
                )
            )
    }

    override fun onLoadResource(view: WebView, url: String) {
        super.onLoadResource(view, url)

        val webapp = DataManager.getInstance().getWebApp(host.webappID)
        if (host.webapp != null && host.webapp!!.isRequestDesktop) {
            view.evaluateJavascript(
                """
                var needsForcedWidth = document.documentElement.clientWidth < 1200;
                if(needsForcedWidth) {
                  document.querySelector('meta[name="viewport"]').setAttribute('content', 'width=1200px, initial-scale=' + (document.documentElement.clientWidth / 1200));
                }
                """.trimIndent(),
                null
            )
        }
        view.evaluateJavascript(
            "document.addEventListener(    \"visibilitychange\"    , (event) => {         event.stopImmediatePropagation();    }  );",
            null
        )
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        host.runOnUiThread { host.setDarkModeIfNeeded() }
        val url = request.url.toString()
        val webapp = DataManager.getInstance().getWebApp(host.webappID)

        if (url.startsWith("tel:")) {
            val intent = Intent(Intent.ACTION_DIAL, url.toUri())
            host.startActivity(intent)
            return true
        }
        if (url.startsWith("mailto:")) {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            host.startActivity(intent)
            return true
        }

        // 错误页重试：重新加载失败时的原地址（恢复用户 textZoom）
        if (url.startsWith("webnative://retry")) {
            if (host.retryUrl.isNotEmpty() && host.wv != null) {
                host.wv!!.settings.textZoom = host.webapp!!.textZoom
                host.wv!!.loadUrl(host.retryUrl)
            }
            return true
        }

        // 非 http/https 协议（tbopen://、weixin:// 等 App 唤起协议）：
        // 交给系统处理（可唤起对应 App），避免 ERR_UNKNOWN_URL_SCHEME 错误页
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                host.startActivity(intent)
            } catch (e: Exception) {
                // 无对应 App：留在当前页不崩溃
            }
            return true
        }

        if (host.webapp == null) {
            return false
        }

        if (host.webapp!!.isOpenUrlExternal) {
            val baseUrl = host.webapp!!.baseUrl
            val uri = baseUrl.toUri()
            val host = uri.host
            if (!url.contains(host!!)) {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                return true
            }
        }
        host.loadURL(view, url)
        return true
    }
}
