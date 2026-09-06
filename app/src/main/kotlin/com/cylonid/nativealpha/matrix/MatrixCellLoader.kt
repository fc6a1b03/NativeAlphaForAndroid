package com.cylonid.nativealpha.matrix

import android.app.Activity
import android.webkit.WebView
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.FeatureMetrics
import com.cylonid.nativealpha.util.LocaleUtils
import com.cylonid.nativealpha.util.WebPerfBridge
import com.cylonid.nativealpha.util.WebShareBridge
import com.cylonid.nativealpha.util.WebViewSetup
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 矩阵格加载执行（主线程 WebView 操作 + Q8 错峰）：创建格 WebView、
 * 错峰 loadUrl、加载头拼装。状态机与释放纪律归引擎，此处只管「开」。
 */
internal class MatrixCellLoader(private val engine: MatrixEngine) {

    /**
     * 创建 WebView 并错峰 loadUrl（多窗同时就绪时 ~150ms 间隔串行，
     * 兼任闸门比对窗口；Q8 参数实测定参入口 [MatrixEngine.STAGGER_LOAD_MS]）。
     */
    fun loadCell(cellIndex: Int, webapp: WebApp) {
        // 错峰任务整体入池登记（释放/换位路径统一取消）
        engine.cellPool.setStaggeredJob(
            cellIndex,
            engine.mainScope.launch {
                // 错峰：忙格数 × 间隔（首窗立即；闸门比对已在此窗口内完成）
                delay(engine.countBusyCells() * MatrixEngine.STAGGER_LOAD_MS)
                if (engine.cellsInternal.value.getOrNull(cellIndex)?.state != MatrixCellUiState.LOADING) {
                    return@launch // 期间被关闭/重置：放弃本次加载
                }
                val webview = createCellWebView(cellIndex, webapp)
                engine.cellPool.install(cellIndex, webview)
                FeatureMetrics.count(FeatureMetrics.MODULE_MATRIX, "cell_load")
                webview.loadUrl(webapp.baseUrl, buildLoadHeaders(webapp))
            }
        )
    }

    private fun createCellWebView(cellIndex: Int, webapp: WebApp): WebView {
        val webview = WebView(engine.appContext)
        // 矩阵格关闭离屏预栅格化（preraster ×4 = 离屏栅格/合成压力放大，
        // 实测 4 窗满载滚动 92.6% 卡顿帧 vs 单窗 10.6%——总纲定参条款落地）；
        // 不写全局 Cookie 接受开关（矩阵 D1 只读共享，防污染宿主站点）
        WebViewSetup.applySiteSettings(
            webview, webapp,
            enablePreRaster = false, configureGlobalCookieAccept = false
        )
        // Q1 反转（用户实测格子视口小）：启用捏合缩放；字体按格子留存值
        // 相对站点 textZoom 缩放（默认 90%「小一级」）
        webview.settings.setSupportZoom(true)
        webview.settings.builtInZoomControls = true
        webview.settings.displayZoomControls = false
        val cell = engine.cellsInternal.value.getOrNull(cellIndex)
        if (cell != null) {
            webview.settings.textZoom =
                ((webapp.textZoom) * cell.textZoomPercent / 100f).toInt().coerceIn(50, 300)
        }
        webview.webViewClient = MatrixCellClient(MatrixCellContext(engine, cellIndex, webapp.ID))
        webview.webChromeClient = MatrixCellChromeClient(
            engine, cellIndex, engine.activityContext as MatrixActivity, (engine.activityContext as MatrixActivity).fileChooserDelegate
        )
        // navigator.share 桥（与宿主同源；activityContext 即 MatrixActivity）
        WebShareBridge.attach(webview, engine.activityContext as Activity)
        // Web Vitals 采集桥（矩阵格与宿主同源采集；applicationContext 防泄漏）
        WebPerfBridge.attach(webview, webapp.ID, engine.appContext.applicationContext)
        return webview
    }

    /** 窗格加载头（与宿主 initCustomHeaders 同源：DNT/UA 清标/语言/省流） */
    private fun buildLoadHeaders(webapp: WebApp): Map<String, String> {
        val headers = HashMap<String, String>()
        headers["DNT"] = "1"
        headers["X-REQUESTED-WITH"] = ""
        headers["Accept-Language"] = LocaleUtils.acceptLanguage
        if (webapp.isSendSavedataRequest) headers["Save-Data"] = "on"
        return headers
    }
}
