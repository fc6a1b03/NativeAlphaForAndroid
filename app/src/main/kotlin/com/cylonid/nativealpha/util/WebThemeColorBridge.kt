package com.cylonid.nativealpha.util

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 站点主题色桥（2026-11-07 全量重构——判定下沉 [WebPageColorPolicy]）。
 *
 * 背景（2026-10-29 真机定谳）：站点自带 `<meta name="theme-color">` +
 * 深色渲染，但 WebView 不把页面颜色传给宿主，系统浅色下「页面黑、栏白」。
 * 这是 WebView 壳的标准缺失（Chrome/TWA 内部有 theme color 实现
 * `AwContents.getThemeColor`，未暴露公开 API——androidx.webkit 1.18 无
 * 对应方法，javap 核实），壳应用只能注入 JS 采集信号自行判定。
 *
 * 职责划分（重构后）：
 * - **本桥（采集层）**：document-start 注入 [THEME_JS]，把页面原始信号
 *   （theme-color meta 数组原文 / colorScheme / html+body 背景色）搬出来，
 *   触发器=事件驱动（t1 首读/t2 observer 同步直读/t3 系统深浅切换/t5
 *   load/t6 回前台/t7 宿主 [WebViewActivity.kickSiteColorReread] 踢），
 *   零轮询零渲染税（观察点收窄 html+body 自身属性与 head 子树）；
 * - **[WebPageColorPolicy]（判定层）**：Kotlin 纯函数，三级判据
 *   S1 声明匹配 → S2 背景亮度 → S3 不干预，全形态可 JVM 单测穷举。
 *
 * 内核怪癖（勿回退）：WebView 131 上 observer callback 内注册的
 * setTimeout 永不执行（CDP 实证 cb:1/inner:0）——callback 必须同步直读；
 * v2.4.13 的 80ms 防抖恰是 observer 路径死因。
 */
internal object WebThemeColorBridge {

    private const val BRIDGE_NAME = "webnativeTheme"

    /**
     * 采集脚本。契约：载荷
     * `{metas:[{c,m}...],cs:"...",bg:"...",bg2:"...",src:"tN"}`，
     * 仅信号变化或 t7 强制上报时 postMessage。**采集层不做任何判定**
     * （归一化/选条/亮度全部在 [WebPageColorPolicy]，可 JVM 单测）。
     *
     * 观察器三观察点（渲染零税）：html/body 自身 class+style（主题级信号
     * ——Discourse 切深浅改 body class，漏看会栏色滞留）+ head 子树
     * （meta 增删改）。body 内元素零监听，滚动/动画/流式输出零触发。
     * 挂载失败重试兜底（document-start 时机未建出的教训，v2.4.6/2.4.7
     * 裸 if(document.head) 恒 false）。
     */
    internal val THEME_JS = """
        (function(){
        if(window.__wnThemeInit)return;window.__wnThemeInit=1;
        var last=null;
        function read(src){
          try{
            var metas=[];
            var els=document.querySelectorAll('meta[name="theme-color"]');
            for(var i=0;i<els.length;i++){
              metas.push({c:els[i].getAttribute('content'),m:els[i].getAttribute('media')});
            }
            // 样式表 load 监听（2026-11-07 真机定谳的窗口期补口）：Discourse
            // 类站点切配色=替换/追加 link[rel=stylesheet]，新 CSS 经网络下载
            // 应用——link 替换是 DOM 变化（obHead 触发 read，但此刻新样式
            // 尚在下载，读到旧值），「下载完成并应用」不是 DOM 变化，
            // MutationObserver 对其全盲 → 栏色滞留旧值直至零散 head 操作。
            // 为每个未挂监听的 stylesheet 挂 load：CSS 应用完成那一刻正中
            // 盲区。read 自身会重扫（新追加的 link 在下一次 read 时挂上）。
            var links=document.querySelectorAll('link[rel~="stylesheet"]');
            for(var j=0;j<links.length;j++){
              if(!links[j].__wnBound){
                links[j].__wnBound=1;
                links[j].addEventListener('load',function(){read('t2');});
              }
            }
            var de=document.documentElement;
            var cs='';
            try{cs=de?getComputedStyle(de).colorScheme||'':'';}catch(e){}
            function bg(el){
              try{
                if(!el)return '';
                return getComputedStyle(el).backgroundColor||'';
              }catch(e){return '';}
            }
            var sig={metas:metas,cs:cs,bg:bg(de),bg2:bg(document.body),src:src||'t1'};
            var key=JSON.stringify({m:sig.metas,c:sig.cs,b:sig.bg,b2:sig.bg2});
            if(key===last&&src!=='t7')return;
            last=key;
            webnativeTheme.postMessage(JSON.stringify(sig));
          }catch(e){}
        }
        window.__wnThemeReread=function(){read('t7');};
        if(document.readyState==='loading'){
          document.addEventListener('DOMContentLoaded',function(){read('t1');});
        }else{read('t1');}
        window.addEventListener('load',function(){read('t5');});
        try{
          var mq=window.matchMedia('(prefers-color-scheme: dark)');
          if(mq.addEventListener){mq.addEventListener('change',function(){read('t3');});}
          else if(mq.addListener){mq.addListener(function(){read('t3');});}
        }catch(e){}
        try{
          document.addEventListener('visibilitychange',function(){
            if(!document.hidden)read('t6');
          });
        }catch(e){}
        try{
          var obHtml=new MutationObserver(function(){read('t2');});
          var obBody=new MutationObserver(function(){read('t2');});
          var obHead=new MutationObserver(function(){read('t2');});
          var retries=0;
          function startObserver(){
            var ok=true;
            try{
              obHtml.observe(document.documentElement,{attributes:true,subtree:false,
                attributeFilter:['class','style']});
            }catch(e){ok=false;}
            try{
              if(document.body){obBody.observe(document.body,{attributes:true,subtree:false,
                attributeFilter:['class','style']});}
              else ok=false;
            }catch(e){ok=false;}
            try{
              obHead.observe(document.head,{childList:true,subtree:true,attributes:true,
                attributeFilter:['content']});
            }catch(e){ok=false;}
            if(ok||++retries>=50)return;
            setTimeout(startObserver,200);
          }
          startObserver();
        }catch(e){}
        })();
    """.trimIndent()

    /** 采集信号快照（parseSignals 产物，供 Policy 判定与诊断探针） */
    data class SampledSignals(
        val policy: WebPageColorPolicy.ColorSignals,
        val metasRaw: List<Pair<String?, String?>>
    )

    /** 最近一次采样的信号快照（诊断探针 color=none 时引用——页面形态一次定谳） */
    @Volatile
    internal var lastSampledSignals: SampledSignals = SampledSignals(
        policy = WebPageColorPolicy.ColorSignals(emptyList(), null, null, null),
        metasRaw = emptyList()
    )
        private set

    /** 挂接桥（每 WebView 一次；与其它桥同一安装通道）。 */
    fun attach(
        webView: WebView,
        onColor: (argb: Int?, src: String) -> Unit
    ) {
        WebBridgeKit.install(
            webView,
            documentStartJs = THEME_JS,
            bridgeName = BRIDGE_NAME,
            onMessage = { _, payload ->
                val src = payloadSrc(payload)
                val sampled = parseSignals(payload)
                lastSampledSignals = sampled
                onColor(WebPageColorPolicy.resolve(sampled.policy), src)
            }
        )
    }

    /**
     * 载荷解析（纯函数，可单测）：采集脚本 JSON → 信号快照。
     * 非法载荷 → 空 metas 信号（Policy 判 null 不干预，回落主题色）。
     */
    internal fun parseSignals(payload: String?): SampledSignals {
        if (payload.isNullOrBlank()) return emptySignals()
        return try {
            val obj = JSONObject(payload)
            val metasRaw = ArrayList<Pair<String?, String?>>()
            val arr = obj.optJSONArray("metas") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                metasRaw.add(m.optString("c", "").ifEmpty { null } to
                    m.optString("m", "").ifEmpty { null })
            }
            SampledSignals(
                policy = WebPageColorPolicy.ColorSignals(
                    metas = metasRaw.map { WebPageColorPolicy.MetaEntry(it.first, it.second) },
                    colorScheme = obj.optString("cs", "").ifEmpty { null },
                    htmlBg = obj.optString("bg", "").ifEmpty { null },
                    bodyBg = obj.optString("bg2", "").ifEmpty { null }
                ),
                metasRaw = metasRaw
            )
        } catch (e: JSONException) {
            emptySignals()
        }
    }

    private fun emptySignals() = SampledSignals(
        policy = WebPageColorPolicy.ColorSignals(emptyList(), null, null, null),
        metasRaw = emptyList()
    )

    /** 载荷 src 字段（触发器来源，缺省/非法回落 t1） */
    internal fun payloadSrc(payload: String?): String = try {
        JSONObject(payload ?: "").optString("src", "t1").ifEmpty { "t1" }
    } catch (e: JSONException) {
        "t1"
    }
}
