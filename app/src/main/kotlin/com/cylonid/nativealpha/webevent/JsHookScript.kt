package com.cylonid.nativealpha.webevent

import com.google.gson.Gson

/**
 * 网页事件 JS hook 构建器（P5，规格 §5.2；P1 同范式：JS 字符串构建可单测）。
 *
 * 三触发器全事件驱动零轮询：
 * - T1 Notification：吞掉网页通知改走原生（P5-2）——覆写 window.Notification
 *   构造器 + requestPermission 直接授予（WebView 无 SW 通知，构造器即全覆盖）
 * - T3 标题变化：document.title setter 劫持（属性描述符重定义）
 * - T2 元素出现：MutationObserver 扫描站点规则的选择器列表（childList/subtree，
 *   重复出现由引擎跳变沿+冷却去重）
 *
 * 安全纪律：全段 try/catch 包裹（hook 失败绝不影响页面功能）；
 * 幂等标记防 SPA/多次注入重复挂载；emit 通道属性描述符锁定防页面篡改。
 */
internal object JsHookScript {

    /** 幂等标记名（JS 与 Kotlin 两侧共享常量） */
    const val IDEMPOTENCY_FLAG = "__WebNativeEventHooked__"

    /** 桥对象名（与 WebEventBridge.JAVASCRIPT_INTERFACE_NAME 一致） */
    const val BRIDGE_NAME = "WebNativeEvent"

    /** T2 观测列表挂载点（hook 内部约定） */
    private const val WATCH_KEY = "__WebNativeWatchSelectors__"

    private val gson = Gson()

    /**
     * 构建 hook 注入脚本（纯函数，无副作用）。
     *
     * @param selectors 该站 T2 规则的 CSS 选择器列表（JSON 转义后嵌入；
     * 空列表则跳过 observer 挂载，页面零开销）
     */
    fun build(selectors: List<String>): String {
        val selectorsJson = gson.toJson(selectors)
        return """
        (function(){
        try {
          if (window.$IDEMPOTENCY_FLAG) return;
          window.$IDEMPOTENCY_FLAG = true;
          function sendRaw(payload){
            try {
              if (window.$BRIDGE_NAME && window.$BRIDGE_NAME.emit) {
                window.$BRIDGE_NAME.emit(JSON.stringify(payload));
              }
            } catch (e) {}
          }
          try { Object.defineProperty(window, '$BRIDGE_NAME', {
            value: Object.create(null, { emit: { value: function(raw){
              try { if (window.__WebNativeBridge__) window.__WebNativeBridge__.emit(String(raw)); } catch (e) {}
            }, writable: false, configurable: false } }),
            writable: false, configurable: false }); } catch(e) {}
          try {
            var OrigNotification = window.Notification;
            function HookedNotification(title, options) {
              try {
                sendRaw({ type: 'notification', title: String(title || ''),
                          body: String((options && options.body) || '') });
              } catch (e) {}
              return { title: title, body: (options && options.body) || '',
                       close: function(){}, addEventListener: function(){},
                       onclick: null, onclose: null };
            }
            HookedNotification.prototype = (OrigNotification && OrigNotification.prototype) || {};
            HookedNotification.permission = 'granted';
            HookedNotification.requestPermission = function(cb) {
              try { if (cb) cb('granted'); } catch (e) {}
              return Promise.resolve('granted');
            };
            HookedNotification.maxActions = 2;
            window.Notification = HookedNotification;
          } catch (e) {}
          try {
            // 真实读 DOM（title 元素 textContent）——getter 若返回闭包缓存值，
            // SPA 绕过 setter 直接改元素后变化检测永远失效（Bing 实测踩坑）
            var readTitle = function(){ try {
              var el = document.querySelector('title');
              if (el) return String(el.textContent || '');
            } catch (e) {}
              return String(document.title || '');
            };
            var lastSeenTitle = (function(){ try { return readTitle(); } catch (e) { return ''; } })();
            var sendTitle = function(){ try {
              var t = readTitle();
              if (t !== lastSeenTitle) {
                lastSeenTitle = t;
                sendRaw({ type: 'title', title: t, body: '' });
              }
            } catch (e) {} };
            // 路径 1：document.title 赋值（属性 setter 劫持；getter 恒读真实 DOM）
            Object.defineProperty(document, 'title', {
              get: function(){ return readTitle(); },
              set: function(v) {
                try {
                  var t = String(v || '');
                  var el = document.querySelector('title');
                  if (el) { el.textContent = t; } // 元素更新触发 observer→统一 emit
                  else { sendRaw({ type: 'title', title: t, body: '' }); }
                } catch (e) {}
              },
              configurable: true
            });
          } catch (e) {}
          // 路径 2（独立 try）：SPA 直接改 <title> 元素 textContent / 换 head
          // 节点——挂文档级 subtree，回调仅做字符串比较
          try {
            var titleObserver = new MutationObserver(function(){ try { sendTitle(); } catch (e) {} });
            titleObserver.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
            sendTitle();
          } catch (e) {}
          var watchSelectors = $selectorsJson;
          if (watchSelectors.length > 0) {
            try {
              window.$WATCH_KEY = watchSelectors;
              var observer = new MutationObserver(function(){
                try {
                  for (var i = 0; i < watchSelectors.length; i++) {
                    var found = false;
                    try { found = document.querySelector(watchSelectors[i]) !== null; } catch (e) {}
                    if (found) sendRaw({ type: 'selector', title: watchSelectors[i], body: '' });
                  }
                } catch (e) {}
              });
              function startObserver(){
                try {
                  if (document.body) {
                    observer.observe(document.body, { childList: true, subtree: true });
                  } else {
                    setTimeout(startObserver, 200);
                  }
                } catch (e) {}
              }
              startObserver();
            } catch (e) {}
          }
        } catch (e) {}
        })()
        """.trimIndent().trim()
    }
}
