package com.cylonid.nativealpha.webevent

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * JS↔原生事件桥（P5，规格 §5.2「L4 纯抽取 ≤10 行」）。
 *
 * 注册名 `__WebNativeBridge__`（别名）：hook 把页面侧 WebNativeEvent 锁定
 * 为代理并转发至此——防页面篡改真桥、避免 addJavascriptInterface 注入对象
 * 与 hook 的 defineProperty 相互覆盖（注入先于 hook 脚本执行）。
 *
 * 纪律：@JavascriptInterface 回调在 JS bridge 线程——解析后 post 主线程
 * （引擎可变状态只在主线程触碰，P5-7）；JSON 解析失败静默（页面数据不可信）。
 * webappId 由构造器绑定（每个 WebView 一个实例），不信任页面负载。
 */
internal class WebEventBridge(
    private val webappId: Int,
    private val onEvent: (event: WebEvent) -> Unit
) {

    companion object {
        /** addJavascriptInterface 注册名（JS 侧经 hook 代理转发至此） */
        const val JAVASCRIPT_INTERFACE_NAME = "__WebNativeBridge__"

        private val mainHandler = Handler(Looper.getMainLooper())
    }

    /** hook 唯一调用入口：payload 为 JSON 字符串 {type,title,body} */
    @JavascriptInterface
    fun emit(raw: String) {
        val event = parse(raw) ?: return
        mainHandler.post { onEvent(event) }
    }

    private fun parse(raw: String): WebEvent? = try {
        val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
        WebEvent(
            webappId = webappId,
            type = obj.get("type")?.asString ?: return null,
            title = obj.get("title")?.asString ?: "",
            body = obj.get("body")?.asString ?: ""
        )
    } catch (ignored: Exception) {
        null
    }
}
