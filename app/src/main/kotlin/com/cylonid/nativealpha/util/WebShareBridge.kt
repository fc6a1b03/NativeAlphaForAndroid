package com.cylonid.nativealpha.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.WebView
import org.json.JSONException
import org.json.JSONObject

/**
 * navigator.share 桥（Web Share API → 系统分享面板）。
 *
 * Android WebView 不实现 Web Share API：站点「分享」按钮调用 navigator.share
 * 在 WebView 里会静默失败（undefined）。经 [WebBridgeKit] 统一安装器挂接——
 * - document-start 注入覆盖 navigator.share/canShare（早于页面任何脚本执行，
 *   无「站点缓存了原引用」逃逸窗口；内核原生注入通道，非轮询）；
 * - WebMessageListener 接 JS → 原生消息（Kit 层已过滤非主框架，防伪造分享）。
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
     * 新建实例，无重复挂载面）。旧内核特性探测不通过时整体跳过——
     * 站点保持原生行为（navigator.share undefined），不降级不崩溃。
     */
    fun attach(webView: WebView, activity: Activity) {
        WebBridgeKit.install(
            webView,
            documentStartJs = SHARE_OVERRIDE_JS,
            bridgeName = BRIDGE_NAME,
            onMessage = { view, payload ->
                view.post { launchShareAndSettle(activity, view, payload) }
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
            // 分享成功发起计数（统计页习惯卡数据源；观测通道不阻塞主流程）
            FeatureMetrics.count("share", "sent")
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
