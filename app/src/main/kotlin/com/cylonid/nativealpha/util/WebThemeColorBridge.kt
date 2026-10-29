package com.cylonid.nativealpha.util

import android.webkit.WebView
import org.json.JSONException
import org.json.JSONObject

/**
 * 站点主题色桥：把页面的 `meta[name=theme-color]` 同步到系统栏。
 *
 * 背景（2026-10-29 真机定谳）：zcode.z.ai 等站点自带
 * `<meta name="theme-color" content="#161616">` + `color-scheme: dark`，
 * 页面渲染成深色，但 WebView 不会把 theme-color 应用到宿主系统栏——
 * 系统浅色下出现「页面黑、状态栏/虚拟按键白」的割裂。这是 WebView 壳的
 * 标准缺失（Chrome/TWA/Custom Tabs 都会读该 meta 并着色系统栏）。
 *
 * 口径：站点声明优先于应用主题（用户定调「直接取站点消息改变 UI 渲染」）；
 * 未声明/非法值/透明则不干预，系统栏回落到应用主题色。
 *
 * 采集时机：document-start 注入 + DOMContentLoaded 首次读取 + 监听后续变化
 * （SPA 切路由常改 theme-color）；仅在值变化时上报，无轮询。
 */
internal object WebThemeColorBridge {

    private const val BRIDGE_NAME = "webnativeTheme"

    /**
     * 采集脚本。契约：{color:"#rrggbb"}，仅在值真变化时 postMessage。
     * 兼容 3/6 位十六进制与 rgb()；取第一个匹配的 meta（与浏览器行为一致）。
     */
    internal val THEME_JS = """
        (function(){
        if(window.__wnThemeInit)return;window.__wnThemeInit=1;
        var last=null;
        function normalize(v){
          if(!v)return null;
          v=String(v).trim();
          var m=/^#([0-9a-f]{3}|[0-9a-f]{6})${'$'}/i.exec(v);
          if(m){
            var h=m[1];
            if(h.length===3)h=h[0]+h[0]+h[1]+h[1]+h[2]+h[2];
            return '#'+h.toLowerCase();
          }
          m=/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/i.exec(v);
          if(m){
            var to=function(n){n=Math.max(0,Math.min(255,parseInt(n,10)));return ('0'+n.toString(16)).slice(-2);};
            return '#'+to(m[1])+to(m[2])+to(m[3]);
          }
          return null;
        }
        function read(){
          try{
            var el=document.querySelector('meta[name="theme-color"]');
            var c=el?normalize(el.getAttribute('content')):null;
            if(c===last)return;
            last=c;
            webnativeTheme.postMessage(JSON.stringify({color:c||''}));
          }catch(e){}
        }
        if(document.readyState==='loading'){
          document.addEventListener('DOMContentLoaded',read);
        }else{read();}
        try{
          var ob=new MutationObserver(function(){
            if(document.body)read();
          });
          if(document.head)ob.observe(document.head,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});
        }catch(e){}
        })();
    """.trimIndent()

    /** 挂接桥（每 WebView 一次；与其它桥同一安装通道） */
    fun attach(
        webView: WebView,
        onColor: (argb: Int?) -> Unit
    ) {
        WebBridgeKit.install(
            webView,
            documentStartJs = THEME_JS,
            bridgeName = BRIDGE_NAME,
            onMessage = { _, payload -> onColor(parseThemeColor(payload)) }
        )
    }

    /**
     * 载荷解析（纯函数，可单测）：{color:"#rrggbb"} → ARGB。
     * 空串/非法/透明 → null（表示不干预，回落应用主题色）。
     */
    internal fun parseThemeColor(payload: String?): Int? {
        if (payload.isNullOrBlank()) return null
        return try {
            val raw = JSONObject(payload).optString("color", "")
            parseHexColor(raw)
        } catch (e: JSONException) {
            null
        }
    }

    /** "#rrggbb" / "#rgb" → 不透明 ARGB；非法返回 null */
    internal fun parseHexColor(raw: String?): Int? {
        val v = raw?.trim()?.removePrefix("#") ?: return null
        val hex = when (v.length) {
            6 -> v
            3 -> "${v[0]}${v[0]}${v[1]}${v[1]}${v[2]}${v[2]}"
            else -> return null
        }
        val rgb = hex.toLongOrNull(16) ?: return null
        return (0xFF000000 or rgb).toInt()
    }

    /**
     * 该色是否应接管系统栏：用户已开启站点主题色跟随，且颜色有效。
     * 极暗/极亮都照用——站点自己的选择，不做审美干预。
     */
    internal fun shouldApply(argb: Int?): Boolean = argb != null
}
