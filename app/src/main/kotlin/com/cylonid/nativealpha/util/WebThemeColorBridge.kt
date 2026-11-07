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
     * 采集脚本。契约：{color:"#rrggbb",src:"tN"}，值变化时 postMessage
     * （t7 宿主踢除外——强制上报作活性确认）。兼容 3/6 位十六进制与 rgb()。
     *
     * 判据演进（linux.do 白栏三轮定谳）：
     * - v2.4.12：media 按系统 matchMedia 求值——**前提错误**，Discourse 深浅
     *   由站点账号主题决定、与系统无关（系统浅+页面深实测依旧），命中错误
     *   分支白栏。
     * - v2.4.13：S1=`getComputedStyle(html).colorScheme` 明确时按声明匹配，
     *   未声明回落系统求值——模拟器 6 组合全过，**但实机 WebView 131 的
     *   matchMedia/observer change 不触发且系统求值路径本身语义错误**，仍白栏。
     * - 本版（2026-11-07）：三级判定——**S1** colorScheme 明确 → 按声明匹配
     *   对应分支；**S2** S1 无信号 → 按页面背景亮度（0.299/0.587/0.114，与
     *   宿主同口径）在 dark/light 条目间选条（系统求值彻底移除——其语义
     *   与页面实际态无关）；**S3** 透明背景/亮度中间带 → 不干预回落主题色。
     *   无 meta 站点恒不干预（坑位 16 口径不变）。触发层全部事件驱动零轮询：
     *   t1 首读/t2 收窄双观察点（html 自身属性+head 子树，body 渲染树零监听）/
     *   t3 matchMedia change/t5 load 补读/t6 回前台/t7 宿主 evaluateJavascript
     *   踢（不走注入/桥/媒体查询任何可能失效的通道，WebView 131 死亡路径的
     *   宿主侧兜底）。
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
        function actualScheme(){
          try{
            var de=document.documentElement;
            if(!de)return null;
            var cs=getComputedStyle(de).colorScheme||'';
            var hasDark=/(^|[^a-z-])dark([^a-z-]|${'$'})/i.test(cs);
            var hasLight=/(^|[^a-z-])light([^a-z-]|${'$'})/i.test(cs);
            if(hasDark&&!hasLight)return 'dark';
            if(hasLight&&!hasDark)return 'light';
          }catch(e){}
          return null;
        }
        function read(src){
          try{
            var els=document.querySelectorAll('meta[name="theme-color"]');
            var scheme=actualScheme();
            var mediaHit=null,fallback=null,darkVal=null,lightVal=null,hasMedia=false;
            for(var i=0;i<els.length;i++){
              var cc=normalize(els[i].getAttribute('content'));
              if(!cc)continue;
              var md=els[i].getAttribute('media');
              var hasDark=/prefers-color-scheme\s*:\s*dark/i.test(md||'');
              var hasLight=/prefers-color-scheme\s*:\s*light/i.test(md||'');
              if(hasDark&&darkVal===null)darkVal=cc;
              if(hasLight&&lightVal===null)lightVal=cc;
              if(!md){if(!fallback)fallback=cc;}
              else{
                hasMedia=true;
                // 判据（2026-11-07 二次定谳）：仅 S1 明确时按声明匹配；
                // scheme=null 时不按系统 matchMedia 求值——Discourse 类站点
                // 深浅由站点账号主题决定、与系统无关（系统浅+页面深实测
                // 依旧存在），系统求值会命中错误分支（v2.4.12 白栏根因）
                if(scheme==='dark'&&hasDark&&!hasLight)mediaHit=cc;
                else if(scheme==='light'&&hasLight&&!hasDark)mediaHit=cc;
              }
            }
            var c=mediaHit||fallback;
            // S2 背景色兜底（2026-11-07 最终形态）：S1 无信号时按页面背景
            // 亮度（0.299/0.587/0.114 加权，与宿主同口径）着色——
            // a) 有 media 双条但未命中：按亮度在 darkVal/lightVal 间选条；
            // b) 页面完全没有可用声明（v2.4.14 实测定谳：linux.do 登录用户
            //    深色页 theme-color meta 一条都没有——theme_signal color=none
            //    src=t7 x5 实锤）：背景极暗(<0.3)/极亮(>0.7)时直接按背景色
    //        上栏（向 Chrome 自身行为对齐），0.3~0.7 中间带仅在有对应
            //    声明条目时跟随；透明背景放弃。原「无 meta 站点恒不干预」
            //    口径就此废弃——它使无声明深色页无解（v2.4.14 白栏根因）。
            if(!c){
              try{
                var de=document.documentElement;
                var col=getComputedStyle(de).backgroundColor;
                if(!col||col==='rgba(0, 0, 0, 0)'||col==='transparent'){
                  if(document.body)col=getComputedStyle(document.body).backgroundColor;
                }
                var m=/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([\d.]+)\s*)?\)/i.exec(col||'');
                if(m){
                  var a=m[4]===undefined?1:parseFloat(m[4]);
                  if(a>=0.99){
                    var lum=(0.299*parseInt(m[1],10)+0.587*parseInt(m[2],10)+0.114*parseInt(m[3],10))/255;
                    if(lum<0.3)c=darkVal||normalize(col);
                    else if(lum<0.45&&darkVal!==null)c=darkVal;
                    else if(lum>=0.7)c=lightVal||normalize(col);
                    else if(lum>=0.55&&lightVal!==null)c=lightVal;
                  }
                }
              }catch(e){}
            }
            if(src==='t7'||c!==last){
              last=c;
              webnativeTheme.postMessage(JSON.stringify({color:c||'',src:src||'t1'}));
            }
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
          // 直读、无防抖（2026-11-07 真机定谳）：WebView 131 上 observer
          // callback 内注册的 setTimeout 永不执行（CDP 实证 cb:1/inner:0），
          // v2.4.13 的 80ms 防抖恰好是 t2 死因。观察点收窄为三个属性级低频面：
          // html/body 自身 class+style（主题级信号——Discourse 等站点切深浅
          // 改 body class，漏看会导致站点内切主题时栏色滞留）+ head 子树
          // （meta 增删改）。body 内元素仍零监听，滚动/动画/流式输出零触发，
          // callback 内同步 read 的 getComputedStyle 开销可接受
          // （v2.4.8~v2.4.11 同形态工作先例）。
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
              if(document.body)obBody.observe(document.body,{attributes:true,subtree:false,
                attributeFilter:['class','style']});
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

    /**
     * 挂接桥（每 WebView 一次；与其它桥同一安装通道）。
     * onColor 第二参数 src 为 JS 侧触发器来源（t1 首读/t2 observer/t3 系统
     * 深浅切换/t5 load/t6 回前台/t7 宿主 evaluateJavascript 踢）——宿主
     * 侧诊断探针据此区分判定路径（实机 WebView 内核差异定谳用）。
     */
    fun attach(
        webView: WebView,
        onColor: (argb: Int?, src: String) -> Unit
    ) {
        WebBridgeKit.install(
            webView,
            documentStartJs = THEME_JS,
            bridgeName = BRIDGE_NAME,
            onMessage = { _, payload ->
                onColor(parseThemeColor(payload), payloadSrc(payload))
            }
        )
    }

    /** 载荷 src 字段（触发器来源，缺省/非法回落 t1） */
    internal fun payloadSrc(payload: String?): String = try {
        JSONObject(payload ?: "").optString("src", "t1").ifEmpty { "t1" }
    } catch (e: JSONException) {
        "t1"
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
