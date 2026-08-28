package com.cylonid.nativealpha

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.ErrorType
import com.cylonid.nativealpha.util.FeatureMetrics
import com.cylonid.nativealpha.util.NotificationUtils
import com.google.android.material.snackbar.Snackbar

/**
 * WebViewClient 基类：站点行为的唯一同源实现——页面开始/结束/错误回调、
 * SSL 拦截、请求拦截与 URL 分流、统计埋点。
 *
 * QA 基类抽取（P4 第 0 步）：原 CustomBrowser 直连 WebViewActivity 的触点
 * 全部改为 [WebViewSiteContext] 注入，宿主 Activity 与矩阵格各自提供实现。
 * 行为迁移为逐行对应（机械重构零语义变更），宿主回归由既有单测 + release
 * 冒烟保障。
 *
 * 渲染进程崩溃处置是唯一 open 分歧点：宿主子类 [CustomBrowser] 提示并
 * finish；矩阵子类回错误态并调度批量恢复（共享渲染进程全窗同死，D3/A）。
 */
@SuppressLint("MissingOnRenderProcessGone")
internal abstract class SiteWebViewClient(
    protected val site: WebViewSiteContext
) : WebViewClient() {
    // 注：onRenderProcessGone 已在下方 override 并实现崩溃恢复；lint 在此类定义处误报，
    // 因方法签名包含 API 26+ 的 RenderProcessGoneDetail，minSdk=31 已完全支持。

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        authHost: String,
        realm: String
    ) {
        site.onHttpAuthRequested(handler, authHost, realm)
    }

    override fun onPageFinished(view: WebView, url: String) {
        // 加载完成：宿主收尾加载 UI（取消白屏检测/停加载动画）
        site.onPageLoadFinished()
        // 统计埋点：主体加载耗时（started 到 finished）
        if (site.pageLoadStartTime > 0) {
            site.recordPageLoadDuration(System.currentTimeMillis() - site.pageLoadStartTime)
            site.pageLoadStartTime = 0
        }
        // 统计埋点：缓存占用（cacheDir + WebStorage，异步不阻塞）
        site.recordCacheUsage()
        if (url == "about:blank") {
            site.showCustomErrorPage("blank", "")
        }
        view.evaluateJavascript(
            "document.addEventListener(\"visibilitychange\"," +
                "function (event) {event.stopImmediatePropagation();},true);", null
        )
        // 移除图片 title/alt 属性（防止 WebView 查看图片时显示图片名浮层遮挡）。
        // MutationObserver 持续清除（SPA 动态图片）；busy 标志防递归
        // （clean 修改属性会再触发 observer）
        view.evaluateJavascript(
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
        site.applyPageZoom()
        super.onPageFinished(view, url)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        // 新页面加载：宿主重置加载 UI 状态（白屏检测/进度计时/动画起点）
        site.onPageLoadStarted()
        // 统计埋点：记录加载开始时间
        site.pageLoadStartTime = System.currentTimeMillis()
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
            site.recordPageError(ErrorType.NETWORK.name, code, desc)
            // 记录重试目标 + 加载自定义错误页（不显示系统默认白屏）
            site.retryUrl = request.url?.toString() ?: site.urlOnFirstPageload
            site.showCustomErrorPage(code, desc)
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
            site.recordPageError(
                ErrorType.HTTP.name,
                errorResponse.statusCode.toString(),
                "HTTP error"
            )
        }
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail
    ): Boolean {
        // 渲染进程崩溃/OOM：统计埋点后交由子类清理（宿主 finish / 矩阵格错误态）
        site.recordPageError(ErrorType.RENDER.name, ErrorType.RENDER.code, "Render process gone")
        // 观测门面接线（P6）：功能级渲染崩溃计数，matrix/webevent 同范式复用
        FeatureMetrics.reportError("webview", "RenderGone", "render process gone")
        return onRenderCrashCleanup(view)
    }

    /**
     * 渲染崩溃清理——宿主/矩阵唯一行为分歧点。
     * 返回 true 阻止系统终止应用（崩溃已被处置）。
     */
    protected abstract fun onRenderCrashCleanup(view: WebView): Boolean

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        if (site.urlOnFirstPageload == "") site.urlOnFirstPageload = request.url.toString()

        val webapp = site.webapp
        if (webapp != null && webapp.isBlockThirdPartyRequests) {
            val uri = request.url
            val webappUri = webapp.baseUrl.toUri()

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
        val webapp = site.webapp
        if (webapp != null && webapp.isIgnoreSslErrors) {
            handler.proceed()
            return
        }

        // 统计埋点：SSL 错误
        site.recordPageError(
            ErrorType.SSL.name, error.primaryError.toString(), "SSL error"
        )

        val context = site.siteContext
        val builder = AlertDialog.Builder(context)

        var message = context.getString(R.string.ssl_error_msg_line1) + " "
        when (error.primaryError) {
            SslError.SSL_UNTRUSTED ->
                message += context.getString(R.string.ssl_error_unknown_authority) + "\n"
            SslError.SSL_EXPIRED ->
                message += context.getString(R.string.ssl_error_expired) + "\n"
            SslError.SSL_IDMISMATCH ->
                message += context.getString(R.string.ssl_error_id_mismatch) + "\n"
            SslError.SSL_NOTYETVALID ->
                message += context.getString(R.string.ssl_error_notyetvalid) + "\n"
        }
        message += context.getString(R.string.ssl_error_msg_line2) + "\n"

        builder.setTitle(context.getString(R.string.ssl_error_title))
        builder.setMessage(message)
        builder.setIcon(android.R.drawable.ic_dialog_alert)
        builder.setPositiveButton(context.getString(android.R.string.cancel)) { _, _ ->
            handler.cancel()
        }
        builder.setNegativeButton(context.getString(R.string.load_anyway)) { _, _ ->
            handler.proceed()
        }
        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            .setTextColor(
                ContextCompat.getColor(
                    context, android.R.color.holo_red_dark
                )
            )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setTextColor(
                ContextCompat.getColor(
                    context, android.R.color.holo_green_dark
                )
            )
    }

    override fun onLoadResource(view: WebView, url: String) {
        super.onLoadResource(view, url)

        @Suppress("UNUSED_VARIABLE")
        val webapp = DataManager.getInstance().getWebApp(site.webappId)
        val siteConfig = site.webapp
        if (siteConfig != null && siteConfig.isRequestDesktop) {
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
        site.refreshDarkModeOnMainThread()
        val url = request.url.toString()

        if (url.startsWith("tel:")) {
            val intent = Intent(Intent.ACTION_DIAL, url.toUri())
            site.siteContext.startActivity(intent)
            return true
        }
        if (url.startsWith("mailto:")) {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            site.siteContext.startActivity(intent)
            return true
        }

        // 错误页重试：重新加载失败时的原地址（恢复用户 textZoom）
        if (url.startsWith("webnative://retry")) {
            if (site.retryUrl.isNotEmpty()) {
                val webapp = site.webapp
                if (webapp != null) view.settings.textZoom = webapp.textZoom
                view.loadUrl(site.retryUrl)
            }
            return true
        }

        // 非 http/https 协议（tbopen://、weixin:// 等 App 唤起协议）：
        // 交给系统处理（可唤起对应 App），避免 ERR_UNKNOWN_URL_SCHEME 错误页
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                site.siteContext.startActivity(intent)
            } catch (e: Exception) {
                // 无对应 App：留在当前页不崩溃
            }
            return true
        }

        val webapp = site.webapp ?: return false

        if (webapp.isOpenUrlExternal) {
            val baseUrl = webapp.baseUrl
            val uri = baseUrl.toUri()
            val baseHost = uri.host
            if (!url.contains(baseHost!!)) {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                return true
            }
        }
        site.loadSiteUrl(view, url)
        return true
    }
}

/**
 * 宿主 WebViewActivity 的站点客户端：崩溃处置=提示用户并关闭当前页面。
 * 行为与抽取前 CustomBrowser 逐行一致（QA 基类抽取唯一子类分歧点）。
 */
internal class CustomBrowser(
    private val host: WebViewActivity
) : SiteWebViewClient(host) {

    override fun onRenderCrashCleanup(view: WebView): Boolean {
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
}
