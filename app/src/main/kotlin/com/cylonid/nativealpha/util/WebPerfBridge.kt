package com.cylonid.nativealpha.util

import android.webkit.WebView

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
            val o = org.json.JSONObject(payload)
            WebVitalsEntry(
                dns = clampMs(o.optDouble("dns", 0.0)),
                tcp = clampMs(o.optDouble("tcp", 0.0)),
                ttfb = clampMs(o.optDouble("ttfb", 0.0)),
                fcp = clampMs(o.optDouble("fcp", 0.0)),
                lcp = clampMs(o.optDouble("lcp", 0.0)),
                domNodes = o.optInt("domNodes", 0).coerceIn(0, 100_000),
                at = at
            )
        } catch (e: org.json.JSONException) {
            null
        }
    }

    /** 毫秒钳制：负值归零、上限 10 分钟（异常时钟差防护） */
    private fun clampMs(v: Double): Int = v.coerceIn(0.0, 600_000.0).toInt()
}
