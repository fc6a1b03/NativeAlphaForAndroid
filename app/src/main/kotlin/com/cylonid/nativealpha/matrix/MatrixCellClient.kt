package com.cylonid.nativealpha.matrix

import android.webkit.WebView
import com.cylonid.nativealpha.SiteWebViewClient
import com.cylonid.nativealpha.WebViewSiteContext
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.ErrorType
import com.cylonid.nativealpha.util.FeatureMetrics
import com.cylonid.nativealpha.util.UrlUtils
import android.app.AlertDialog
import android.content.Context
import android.webkit.HttpAuthHandler

/**
 * 矩阵格的站点上下文：[WebViewSiteContext] 的 matrix 实现（QA 基类抽取
 * 的第二个实现方）。
 *
 * 与宿主实现的语义分歧（均为规格决策，非漂移）：
 * - 统计口径（QC）：不入 StatsRecorder，仅 FeatureMetrics 计数
 * - 加载 UI 钩子：格状态机替代动物动画/白屏检测
 * - 错误页：自定义 HTML 错误页不适用格内，主框架失败回错误态
 * - 深色模式：仅应用 WebView 侧强制暗化，不动 Activity 主题
 */
internal class MatrixCellContext(
    internal val engine: MatrixEngine,
    internal val cellIndex: Int,
    private val siteId: Int
) : WebViewSiteContext {

    override val siteContext: Context get() = engine.activityContext

    override val webappId: Int get() = siteId

    override val webapp get() = DataManager.getInstance().getWebApp(siteId)

    override var urlOnFirstPageload: String = ""

    override var retryUrl: String = ""

    override var pageLoadStartTime: Long = 0L

    override fun onPageLoadStarted() {
        engine.onCellLoadStarted(cellIndex)
    }

    override fun onPageLoadFinished() {
        engine.onCellPageFinished(cellIndex)
    }

    override fun showCustomErrorPage(code: String?, desc: String?) {
        // 格内不加载宿主 HTML 错误页：格状态机呈现错误态（规格五态）
        engine.onCellLoadFailed(cellIndex)
    }

    override fun onHttpAuthRequested(handler: HttpAuthHandler, authHost: String, realm: String) {
        MatrixDialogs.showHttpAuthDialog(siteContext, handler)
    }

    override fun applyPageZoom() {
        // Q1：v1 窗内禁缩放，页面缩放 zoomBy 不应用（textZoom 已随站点设置生效）
    }

    override fun recordCacheUsage() {
        // 缓存占用统计是宿主菜单语义（menuHelper），矩阵格不适用
    }

    override fun refreshDarkModeOnMainThread() {
        // 矩阵格深色在创建时一次性应用（applyCellDarkMode），导航内不刷新
    }

    override fun loadSiteUrl(view: WebView, url: String) {
        // HTTP 明文导航矩阵内默认放行（与 pickSite 同口径，用户定调不拦截）
        view.loadUrl(url)
    }

    override fun recordPageLoadDuration(durationMs: Long) {
        FeatureMetrics.count("matrix", "cell_load_ms")
    }

    override fun recordPageError(errorType: String, code: String, desc: String) {
        // 错误收集定位重构（用户定调）：网络/SSL/HTTP 类页面错误属环境
        // 观测非应用缺陷——降为计数（错误日志只收应用致命问题：崩溃/
        // OOM/内存压力/渲染崩溃）。观测计数见 feature_metrics_matrix
        FeatureMetrics.count("matrix", "PageError:$errorType")
    }
}

/**
 * 矩阵格 WebViewClient：站点行为全部继承基类（宿主/矩阵唯一同源实现），
 * 崩溃处置覆写为格错误态 + 批量恢复调度，宿主永不 finish（D3/A）。
 */
internal class MatrixCellClient(
    cellContext: MatrixCellContext
) : SiteWebViewClient(cellContext) {

    override fun onRenderCrashCleanup(view: WebView): Boolean {
        val context = site as MatrixCellContext
        context.engine.onRenderGone(context.cellIndex)
        return true // 已处理，阻止系统终止应用
    }
}

/** 矩阵格对话框集合（宿主对话框的格内轻量等价实现） */
internal object MatrixDialogs {

    /** HTTP Basic 认证：矩阵内暂不支持凭据输入，默认取消（不静默放行） */
    fun showHttpAuthDialog(context: Context, handler: HttpAuthHandler) {
        AlertDialog.Builder(context)
            .setTitle(android.R.string.dialog_alert_title)
            .setMessage(context.getString(com.cylonid.nativealpha.R.string.ssl_error_msg_line1))
            .setPositiveButton(android.R.string.cancel) { _, _ -> handler.cancel() }
            .setOnCancelListener { handler.cancel() }
            .show()
    }
}
