package com.cylonid.nativealpha.util

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * WebView 桥统一安装器（WebShareBridge / WebPerfBridge 共性抽离，R3 单一实现）。
 *
 * 职责：origin 规则 + 双特性探测 + document-start 脚本注入 + WebMessageListener
 * 挂接的一次性编排。业务桥只提供脚本与消息回调，不重复接触 webkit 探测细节。
 *
 * 特性探测前置：两项 webkit API 均带 RequiresFeature 前置要求，旧内核
 * （WebView 版本不足）整体跳过并返回 false——站点保持原生行为，不降级不崩溃。
 * 每 WebView 实例仅调用一次（宿主/矩阵格 WebView 均为新建实例——矩阵池
 * 「重用一律新实例」，无重复挂载面）。
 */
internal object WebBridgeKit {

    /** 仅 http/https 全站启用（"*" 是 origin 规则语法里唯一的全站通配写法） */
    val ALL_HTTP_ORIGINS: Set<String> = setOf("*")

    /**
     * 挂接桥。
     *
     * @param documentStartJs document-start 注入脚本；null = 仅装消息通道
     * @param bridgeName JS 侧桥对象名；null = 仅注入脚本
     * @param onMessage 主框架字符串消息回调（非主框架/空消息已在此层过滤，
     * iframe 载荷不可信，业务桥无需重复防御）
     * @return 是否至少安装了一项能力（特性探测全不支持 = false，调用方零分支）
     */
    fun install(
        webView: WebView,
        originRules: Set<String> = ALL_HTTP_ORIGINS,
        documentStartJs: String? = null,
        bridgeName: String? = null,
        onMessage: ((view: WebView, payload: String) -> Unit)? = null
    ): Boolean {
        var installed = false
        if (documentStartJs != null &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            WebViewCompat.addDocumentStartJavaScript(webView, documentStartJs, originRules)
            installed = true
        }
        if (bridgeName != null && onMessage != null &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        ) {
            WebViewCompat.addWebMessageListener(
                webView, bridgeName, originRules,
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        // iframe 内消息一律忽略：载荷不可信（防伪造分享/注入虚数）
                        val payload = message.data ?: return
                        if (!isMainFrame) return
                        onMessage(view, payload)
                    }
                }
            )
            installed = true
        }
        return installed
    }
}
