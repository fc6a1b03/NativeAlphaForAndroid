package com.cylonid.nativealpha.util

import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.cylonid.nativealpha.model.WebApp

/**
 * WebView 按站设置的统一应用入口（QA 抽取，P4 第 0 步）。
 *
 * 历史问题：按站设置段落内联在 WebViewActivity.onCreate，矩阵窗格无法
 * 复用——矩阵里的站点行为会与单独打开不一致（QA 决策：行为必须同源）。
 *
 * 抽取为纯函数（WebView + WebApp → 设置应用），宿主与矩阵共用：
 * - [applyUserAgent]：UA 尾巴清理与自定义/桌面 UA
 * - [applySiteSettings]：安全加固/渲染优化/JS/Cookie/缩放全量按站设置
 *
 * 语句顺序与原 WebViewActivity 内联实现逐行对应（机械抽取零语义变更）。
 */
internal object WebViewSetup {

    /**
     * UA 处理：移除 WebView 字段名注入的 UA 尾巴（找不到字段时静默跳过，
     * 避免 NPE），再按站应用自定义 UA。
     *
     * @param webViewFieldName 调用方 WebView 字段名（反射结果由调用方缓存，
     * 宿主 WebViewActivity 启动热路径不重复遍历 declaredFields）
     */
    fun applyUserAgent(webview: WebView, app: WebApp, webViewFieldName: String) {
        if (webViewFieldName.isNotEmpty()) {
            val uaString = webview.settings.userAgentString
                .replace("; " + webViewFieldName, "")
            webview.settings.userAgentString = uaString
        }
        if (app.isUseCustomUserAgent) {
            val customUa = app.userAgent
            if (!customUa.isNullOrEmpty()) {
                webview.settings.userAgentString = customUa
                    .replace("\u0000", "").replace("\n", "").replace("\r", "")
            }
        }
    }

    /**
     * 按站全量设置：安全加固、滚动条、渲染进程优先级、文本流渲染优化、
     * JS/Cookie/图片、桌面 UA 与缩放。语句顺序与原内联实现一致；
     * 深色模式（涉及 Activity 主题）由调用方在设置完成后自行应用。
     */
    fun applySiteSettings(webview: WebView, app: WebApp) {
        // ===== 安全加固（WebApp 设置项，默认全开） =====
        // 恶意网站防护：默认关（AGENTS.md 既有设计：用户可添加非 HTTPS 站点，按需开启）
        webview.settings.safeBrowsingEnabled = app.isSafeBrowsing
        // 禁用文件访问：防止恶意站点读取本地文件
        webview.settings.allowFileAccess = !app.isFileAccessDisabled
        // 禁用内容提供器访问：防止站点访问系统 content:// 资源
        webview.settings.allowContentAccess = !app.isContentAccessDisabled
        // 混合内容拦截：HTTPS 页面禁止加载 HTTP 子资源
        webview.settings.mixedContentMode = if (app.isMixedContentBlocked)
            WebSettings.MIXED_CONTENT_NEVER_ALLOW
        else
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // JS 弹窗限制：禁止页面自动 window.open（用户手势触发的弹窗仍可用）
        webview.settings.javaScriptCanOpenWindowsAutomatically = !app.isJsPopupsRestricted
        webview.settings.domStorageEnabled = true
        webview.settings.databaseEnabled = true
        webview.settings.blockNetworkLoads = false

        // ===== 禁用浏览器自带滚动条（网页内容本身的自定义滚动条不受影响） =====
        webview.isVerticalScrollBarEnabled = false
        webview.isHorizontalScrollBarEnabled = false
        webview.overScrollMode = View.OVER_SCROLL_NEVER
        // 隐藏滚动条占位（WebView 默认 overlay 模式，但仍显式关闭占位）
        webview.scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY

        // ===== PWA 高频文本流渲染优化（流式输出/长文档滚动场景） =====
        // 渲染进程优先级（Chromium 官方推荐，替代废弃 setRenderPriority）：
        // 渲染进程不可见时 WAIVED（可被系统回收减压），可见时正常渲染——
        // 文本流场景前台渲染不受影响（minSdk 31 无版本分支）
        webview.setRendererPriorityPolicy(
            android.webkit.WebView.RENDERER_PRIORITY_WAIVED,
            true
        )
        // 硬件加速强制（避免软件层合成拖慢流式更新）。minSdk=31，直接启用无需判断。
        webview.setLayerType(View.LAYER_TYPE_NONE, null)
        // 文字缩放（用户可调：50~200%，默认 100）
        webview.settings.textZoom = app.textZoom
        // 页面缩放（用户可调：50~200%，默认 100）：onPageFinished 里 zoomBy 应用
        // 预栅格化：减少滚动时白块/抖动（流式长文本滚动流畅）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
            WebSettingsCompat.setOffscreenPreRaster(webview.settings, true)
        }
        // 缓存策略：默认模式，流式页面不强制离线/不缓存
        webview.settings.cacheMode = WebSettings.LOAD_DEFAULT
        // 布局算法：NORMAL 对文本流最稳（SINGLE_COLUMN 会触发整页重排，流式更新开销大）
        webview.settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        // 编码：UTF-8 显式声明（中文流式文本解析正确，避免编码重排）
        webview.settings.defaultTextEncodingName = "UTF-8"
        // 关闭边缘高亮减少合成开销
        webview.overScrollMode = View.OVER_SCROLL_NEVER
        // 滚动条优化（长文本流式滚动）
        webview.scrollBarStyle = WebView.SCROLLBARS_OUTSIDE_OVERLAY
        webview.isScrollbarFadingEnabled = true
        // ===== PWA 渲染优化结束 =====

        webview.settings.javaScriptEnabled = app.isAllowJs

        // Cookie 全局开关 + 按站第三方 Cookie（CookieManager 全局单例，
        // 矩阵多窗共享同一全局环境——D1 决策的既有事实基础）
        android.webkit.CookieManager.getInstance().setAcceptCookie(app.isAllowCookies)
        android.webkit.CookieManager.getInstance()
            .setAcceptThirdPartyCookies(webview, app.isAllowThirdPartyCookies)

        if (app.isBlockImages) {
            webview.settings.blockNetworkImage = true
        }

        if (app.isRequestDesktop) {
            webview.settings.userAgentString = Const.DESKTOP_USER_AGENT
            webview.settings.useWideViewPort = true
            webview.settings.loadWithOverviewMode = true

            webview.settings.setSupportZoom(true)
            webview.settings.builtInZoomControls = true
            webview.settings.displayZoomControls = false

            webview.scrollBarStyle = WebView.SCROLLBARS_OUTSIDE_OVERLAY
            webview.isScrollbarFadingEnabled = false
        }
        if (app.isEnableZooming) {
            webview.settings.setSupportZoom(true)
            webview.settings.builtInZoomControls = true
        }
    }
}
