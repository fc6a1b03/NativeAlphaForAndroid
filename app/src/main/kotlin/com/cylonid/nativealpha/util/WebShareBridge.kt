package com.cylonid.nativealpha.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONException
import org.json.JSONObject

/**
 * navigator.share 桥（Web Share API → 系统分享面板）。
 *
 * Android WebView 不实现 Web Share API：站点「分享」按钮调用 navigator.share
 * 在 WebView 里会静默失败（undefined）。此处用 webkit 官方双 API 组桥——
 * - [WebViewCompat.addDocumentStartJavaScript]：每次导航在 document-start
 *   覆盖 navigator.share/canShare（早于页面任何脚本执行，无「站点缓存了
 *   原引用」逃逸窗口；内核原生注入通道，非 evaluateJavascript 轮询）；
 * - [WebViewCompat.addWebMessageListener]：JS → 原生消息通道（仅主框架
 *   消息被接受，防 iframe 伪造分享）。
 *
 * 性能纪律：脚本为纯字符串常量（无网络/无 IO/无 DOM 操作），单次导航仅由
 * 内核注入一次，运行时零轮询零监听——对页面加载无可测损耗。
 * 原生侧收到消息后回执 resolve：系统分享 chooser 无取消结果回调
 * （Chrome 在用户选完目标才 resolve，此处立即 resolve 保证站点 Promise
 * 不悬挂，属浏览器壳通行语义）。
 */
internal object WebShareBridge {

    /** JS 侧桥对象名（window.webnativeShare.postMessage，与 [SHARE_OVERRIDE_JS] 契约） */
    private const val BRIDGE_NAME = "webnativeShare"

    /** 分享正文 MIME（系统分享面板通用类型，纯文本分享唯一合法值） */
    private const val MIME_TEXT_PLAIN = "text/plain"

    /** 分享回执：resolve 挂起的 navigator.share Promise 并清理句柄 */
    internal const val RESOLVE_JS =
        "window.__wnShareDone&&(__wnShareDone(),delete window.__wnShareDone)"

    /**
     * 全站启用："*" 是 origin 规则语法里唯一的全站通配写法（协议级通配
     * 「https:// 加星号」不是合法规则）。Web Share 本就是通用 Web 能力，
     * 且本桥无敏感数据回流（只上行分享载荷），isMainFrame 过滤已闭合伪造面。
     */
    private val ALLOWED_ORIGIN_RULES = setOf("*")

    /**
     * document-start 注入脚本：幂等覆盖 navigator.share/canShare。
     * 数据契约——分享载荷 JSON {title,text,url} 经 webnativeShare.postMessage
     * 上行；原生处理完执行 [RESOLVE_JS] 回执。
     * 标准对齐：files 分享诚实拒答（canShare=false，壳不支持文件分享）；
     * 进行中再 share 返回 InvalidStateError；桥不可用 reject 时同步清理
     * 挂起句柄（防状态残留把后续分享误判为进行中）。
     */
    internal val SHARE_OVERRIDE_JS = """
        (function(){
        if(window.__wnShareInit)return;window.__wnShareInit=1;
        var can=function(d){d=d||{};
          if(d.files&&d.files.length)return false;
          return!!(d.text||d.url||d.title)};
        navigator.share=function(d){
          if(!can(d))return Promise.reject(new TypeError('Invalid share data'));
          if(window.__wnShareDone)
            return Promise.reject(new DOMException('share in progress','InvalidStateError'));
          return new Promise(function(res,rej){
            window.__wnShareDone=res;
            var p={title:d.title==null?'':String(d.title),
                   text:d.text==null?'':String(d.text),
                   url:d.url==null?'':String(d.url)};
            try{webnativeShare.postMessage(JSON.stringify(p))}
            catch(e){delete window.__wnShareDone;
                     rej(new DOMException('bridge unavailable','AbortError'))}
          });
        };
        navigator.canShare=can;
        })();
    """.trimIndent()

    /**
     * 挂接分享桥。每 WebView 实例仅调用一次即可（宿主/矩阵格 WebView 均为
     * 新建实例——矩阵池「重用一律新实例」，无重复挂载面）。
     *
     * 特性探测前置：两项 API 均带 RequiresFeature 前置要求，旧内核
     * （WebView 版本不足）直接整体跳过——站点保持原生行为（navigator.share
     * undefined），不降级不崩溃。
     */
    fun attach(webView: WebView, activity: Activity) {
        val documentStartSupported =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        val messageListenerSupported =
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (!documentStartSupported || !messageListenerSupported) {
            return
        }
        WebViewCompat.addDocumentStartJavaScript(
            webView, SHARE_OVERRIDE_JS, ALLOWED_ORIGIN_RULES
        )
        WebViewCompat.addWebMessageListener(
            webView, BRIDGE_NAME, ALLOWED_ORIGIN_RULES,
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                    // iframe 内分享按钮一律忽略：载荷 url 不可信
                    val payload = message.data ?: return
                    if (!isMainFrame) return
                    view.post { launchShareAndSettle(activity, view, payload) }
                }
            }
        )
    }

    /**
     * 发起系统分享并回执 resolve。与消息回调分离：摊平回调嵌套层级
     * （规范 R4），也便于独立审视「无论分享成败都必须 settle」的语义。
     */
    private fun launchShareAndSettle(activity: Activity, view: WebView, payload: String) {
        val intent = buildShareIntent(payload)
        if (intent != null) {
            try {
                activity.startActivity(Intent.createChooser(intent, null))
            } catch (ignored: ActivityNotFoundException) {
                // 无任何可接收应用：照常回执，不使站点 Promise 悬挂
            }
        }
        view.evaluateJavascript(RESOLVE_JS, null)
    }

    /**
     * 分享载荷 JSON → ACTION_SEND Intent（纯函数，可单测）。
     *
     * EXTRA_TEXT 组装：text 与 url 拼接（Chrome 同语义，换行分隔），
     * 两者相同则去重（站点普遍 text=url，避免重复粘贴两遍）；
     * title 走 EXTRA_SUBJECT。返回 null 表示载荷无效（非法 JSON/无正文）。
     */
    internal fun buildShareIntent(payload: String?): Intent? {
        if (payload.isNullOrBlank()) return null
        val obj = try {
            JSONObject(payload)
        } catch (e: JSONException) {
            return null
        }
        val title = obj.optString("title").trim()
        val text = obj.optString("text").trim()
        val url = obj.optString("url").trim()
        val body = buildList {
            if (text.isNotEmpty()) add(text)
            if (url.isNotEmpty() && url != text) add(url)
        }.joinToString("\n")
        if (body.isEmpty()) return null
        return Intent(Intent.ACTION_SEND).apply {
            type = MIME_TEXT_PLAIN
            if (title.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, body)
        }
    }
}
