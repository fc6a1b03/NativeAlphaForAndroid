package com.cylonid.nativealpha.util

import android.webkit.WebView
import org.json.JSONException
import org.json.JSONObject

/**
 * Web Vitals 采集桥（Phase 3，三档数据）：document-start 注入采集脚本，
 * 页面 load 后 2 秒（LCP 稳定窗口）回传 NavigationTiming 细分 +
 * FCP/LCP + DOM 节点数，经 WebBridgeKit 统一通道上行。
 *
 * 性能纪律：采集是一次性快照（单次导航一次 postMessage，无轮询/无持续
 * observer 回调风暴）；全本地存储零上报。采集失败静默（try 包裹），
 * 不影响页面功能。
 */
internal object WebPerfBridge {

    /** JS 侧桥对象名（window.webnativePerf.postMessage，与 [PERF_JS] 契约） */
    private const val BRIDGE_NAME = "webnativePerf"

    /** LCP 稳定等待（ms）——load 后再等这么久取最大内容绘制终值 */
    private const val LCP_SETTLE_MS = 2000

    /**
     * document-start 采集脚本。契约：一次性上行 JSON
     * {dns,tcp,ttfb,fcp,lcp,domNodes}（ms，Int）；__wnPerfSent 幂等防重。
     */
    internal val PERF_JS = """
        (function(){
        if(window.__wnPerfInit)return;window.__wnPerfInit=1;
        var lcp=0;
        try{new PerformanceObserver(function(l){
          var es=l.getEntries();if(es.length)lcp=Math.round(es[es.length-1].startTime);
        }).observe({type:'largest-contentful-paint',buffered:true})}catch(e){}
        window.addEventListener('load',function(){
          setTimeout(function(){
            if(window.__wnPerfSent)return;window.__wnPerfSent=1;
            try{
              var nav=performance.getEntriesByType('navigation')[0];
              if(!nav)return;
              var fcp=0;
              performance.getEntriesByType('paint').forEach(function(e){
                if(e.name==='first-contentful-paint')fcp=Math.round(e.startTime);
              });
              webnativePerf.postMessage(JSON.stringify({
                dns:Math.max(0,Math.round(nav.domainLookupEnd-nav.domainLookupStart)),
                tcp:Math.max(0,Math.round(nav.connectEnd-nav.connectStart)),
                ttfb:Math.max(0,Math.round(nav.responseStart-nav.requestStart)),
                fcp:fcp,lcp:lcp,
                domNodes:document.getElementsByTagName('*').length
              }));
            }catch(e){}
          },2000);
        });
        })();
    """.trimIndent()

    /**
     * 流量采集脚本（document-start 注入）：累计本页网络传输字节。
     *
     * 口径：Resource Timing 的 transferSize（含响应头 + body 的线上字节，
     * 不含被缓存命中回的 0）；跨域资源无 Timing-Allow-Origin 时该字段为 0，
     * 故取 encodedBodySize 兜底估算——两者皆 0 视为不可见跳过。真实值下界。
     *
     * 上报时机：pagehide/visibilitychange(hidden)/freeze 时上报一次增量
     * （__wnTrafficSent 累计已报量，故多次上报不重复计数）；无轮询、
     * 无 observer 风暴，页面关闭零成本。
     *
     * 桥名独立于 webnativePerf：性能桥是一次性快照，流量是多次增量，
     * 复用同一监听器会互相干扰各自的一次性守卫。
     */
    private const val TRAFFIC_BRIDGE_NAME = "webnativeTraffic"

    internal val TRAFFIC_JS = """
        (function(){
        if(window.__wnTrafficInit)return;window.__wnTrafficInit=1;
        var sent=0;
        function bytes(e){return (e.transferSize||0)||(e.encodedBodySize||0)||0;}
        function total(){
          var t=0;
          try{
            var nav=performance.getEntriesByType('navigation')[0];
            if(nav)t+=bytes(nav);
            performance.getEntriesByType('resource').forEach(function(e){t+=bytes(e);});
          }catch(e){}
          return t;
        }
        function flush(){
          try{
            var t=total();
            if(t<=sent)return;
            var delta=t-sent;sent=t;
            webnativeTraffic.postMessage(JSON.stringify({delta:delta}));
          }catch(e){}
        }
        window.addEventListener('pagehide',flush);
        window.addEventListener('freeze',flush);
        document.addEventListener('visibilitychange',function(){
          if(document.visibilityState==='hidden')flush();
        });
        window.__wnTrafficFlush=flush;
        })();
    """.trimIndent()

    /** 挂接流量采集桥（每 WebView 一次；与性能桥同一安装通道） */
    fun attachTraffic(webView: WebView, webappId: Int, context: android.content.Context) {
        WebBridgeKit.install(
            webView,
            documentStartJs = TRAFFIC_JS,
            bridgeName = TRAFFIC_BRIDGE_NAME,
            onMessage = { _, payload ->
                val delta = parseTrafficDelta(payload) ?: return@install
                StatsRecorder.recordTraffic(webappId, delta)
            }
        )
    }

    /**
     * 主动触发流量上报（宿主 onPause 调用）。
     *
     * 页面脚本只在 pagehide/visibilitychange(hidden)/freeze 上报——App 切后台
     * 只调 WebView.onPause()，不产生这些事件，会话中途产生的流量会一直挂着
     * 直到下次隐藏。此处补一发，保证「切后台即落账」。
     */
    fun flushTraffic(webView: WebView) {
        try {
            webView.evaluateJavascript("__wnTrafficFlush&&__wnTrafficFlush()", null)
        } catch (ignored: Exception) {
            // 页面未注入/已销毁：静默（下次隐藏或卸载时页面侧自行上报）
        }
    }

    /**
     * 流量增量载荷解析（纯函数，可单测）：{delta:bytes} → 非负 Long。
     * 非法/非 JSON/负数/超上限（单批 512MB，防异常值污染累计）→ null 丢弃。
     */
    internal fun parseTrafficDelta(payload: String?, maxBytes: Long = MAX_TRAFFIC_DELTA): Long? {
        if (payload.isNullOrBlank()) return null
        return try {
            val delta = JSONObject(payload).optLong("delta", 0L)
            if (delta in 1..maxBytes) delta else null
        } catch (e: JSONException) {
            null
        }
    }

    /** 单批流量增量上限（512MB）：超出视为异常值，丢弃保护累计不被污染 */
    internal const val MAX_TRAFFIC_DELTA = 512L * 1024 * 1024

    /** 挂接采集桥（每 WebView 一次；特性探测在 Kit 层，旧内核静默跳过） */
    fun attach(webView: WebView, webappId: Int, context: android.content.Context) {
        WebBridgeKit.install(
            webView,
            documentStartJs = PERF_JS,
            bridgeName = BRIDGE_NAME,
            onMessage = { _, payload ->
                val entry = buildVitalsEntry(payload) ?: return@install
                // 观测写盘走异步通道（R8：附属逻辑不阻塞主线程）
                StatsRecorder.recordSuspend { WebVitalsStore.append(context, webappId, entry) }
            }
        )
    }

    /**
     * 载荷 JSON → [WebVitalsEntry]（纯函数，可单测）。
     * 任意字段缺失/非 JSON → null 丢弃（观测数据宁可缺不脏）。
     * 4 小时窗口去重不需要：每导航一次一条，时序由 at 区分。
     */
    internal fun buildVitalsEntry(payload: String?, at: Long = System.currentTimeMillis()): WebVitalsEntry? {
        if (payload.isNullOrBlank()) return null
        return try {
            val o = JSONObject(payload)
            WebVitalsEntry(
                dns = clampMs(o.optDouble("dns", 0.0)),
                tcp = clampMs(o.optDouble("tcp", 0.0)),
                ttfb = clampMs(o.optDouble("ttfb", 0.0)),
                fcp = clampMs(o.optDouble("fcp", 0.0)),
                lcp = clampMs(o.optDouble("lcp", 0.0)),
                domNodes = o.optInt("domNodes", 0).coerceIn(0, 100_000),
                at = at
            )
        } catch (e: JSONException) {
            null
        }
    }

    /** 毫秒钳制：负值归零、上限 10 分钟（异常时钟差防护） */
    private fun clampMs(v: Double): Int = v.coerceIn(0.0, 600_000.0).toInt()
}
