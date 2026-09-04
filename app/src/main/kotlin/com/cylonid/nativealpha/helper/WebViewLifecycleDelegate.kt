
package com.cylonid.nativealpha.helper

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.window.OnBackInvokedDispatcher
import com.cylonid.nativealpha.BuildConfig
import com.cylonid.nativealpha.CustomBrowser
import com.cylonid.nativealpha.CustomWebChromeClient
import com.cylonid.nativealpha.MainActivity
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.WebViewActivity
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.ErrorType
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.CookieSessionManager
import com.cylonid.nativealpha.util.EntryPointUtils
import com.cylonid.nativealpha.util.FeatureMetrics
import com.cylonid.nativealpha.util.NotificationUtils
import com.cylonid.nativealpha.util.StatsRecorder
import com.cylonid.nativealpha.util.UrlUtils
import com.cylonid.nativealpha.util.WebPerfBridge
import com.cylonid.nativealpha.util.WebShareBridge
import com.cylonid.nativealpha.util.WebViewLauncher
import com.cylonid.nativealpha.util.WebViewSetup
import com.cylonid.nativealpha.util.WebviewRecycleRegistry
import com.cylonid.nativealpha.webevent.WebeventRuntime
import com.google.android.material.snackbar.Snackbar

/**
 * 装配与生命周期委托（重构刀 1，自 WebViewActivity 逐行迁移，零语义变更）。
 *
 * 职责：intent→WebApp 解析与 Cookie 会话恢复时序（token 防重入）、
 * WebView 装配链（proceedSetup→setupWebView）、生命周期体
 * （onStart/onStop/onResume/onPause/onTrimMemory/onDestroy）、
 * 系统返回键语义（网页后退→重载首页→退任务）、后台分级回收、自动刷新。
 *
 * 契约留守 Activity：生命周期 override 入口（系统回调）、onBackPressed
 * override（废弃 API 兼容注解）、ChromeClient 直读字段。本类只承接实现体。
 */
class WebViewLifecycleDelegate(private val activity: WebViewActivity) {

    /** WebView 字段名（UA 尾巴清理用）：反射结果进程内缓存——类字段布局不变，
     * 每次打开 WebApp 都遍历 declaredFields 是纯浪费（启动热路径） */
    private val uaFieldName: String by lazy {
        try {
            WebViewActivity::class.java.declaredFields
                .firstOrNull { it.type == WebView::class.java }?.name ?: ""
        } catch (ignored: Exception) {
            ""
        }
    }

    /** 装配令牌：onNewIntent 重入时递增，旧 cookie 回调因 token 不匹配被丢弃 */
    private var pendingSetupToken = 0
    private var currentIntentToken = 0

    private var quitOnNextBackpress = false
    private var reloadHandler: Handler? = null

    /** 后台回收标记：true=WebView 实例已被分级回收，onStart 需重建 */
    private var recycled = false

    // ===== 装配入口 =====

    /**
     * 虚拟按键/手势返回（Android 13+ OnBackInvokedDispatcher）：
     * Manifest enableOnBackInvokedCallback=true 时 onBackPressed 不再被系统调用，
     * **必须注册回调**——否则按返回直接退出 Activity 而不触发网页后退（用户反馈的核心 bug）。
     */
    fun registerBackCallback() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT
                ) { activity.onBackPressed() }
            } catch (ignored: Exception) {
                // 注册失败退回系统默认（老路径仍可用）
            }
        }
    }

    /** 初始化/重载：按 intent 的 webappID 加载 WebApp（复用实例时先清理旧 WebView）。
     * 扫码临时浏览（INTENT_RAW_URL）例外：构建负 ID 瞬态站点——不注册、
     * 统计零写入（StatsRecorder/DataManager 对负 id 静默跳过），标题取 host。 */
    fun handleIntent(intent: Intent) {
        val rawUrl = intent.getStringExtra(Const.INTENT_RAW_URL)
        if (rawUrl != null) {
            activity.webappID = -1
            activity.webapp = WebApp(rawUrl, -1, -1).apply {
                title = UrlUtils.displayHost(rawUrl)
            }
        } else {
            activity.webappID = intent.getIntExtra(Const.INTENT_WEBAPPID, -1)
            activity.webapp = DataManager.getInstance().getWebApp(activity.webappID)
        }
        activity.webappTabIndex = intent.getIntExtra(Const.INTENT_TAB_INDEX, 0)
        EntryPointUtils.entryPointReached(activity)
        // 重置错误页重试目标（新应用加载，避免残留旧地址）
        activity.retryUrl = ""
        // 登录态隔离：开启隔离的 WebApp 恢复自己的 Cookie 会话（异步，多标签按 tabIndex）。
        // onRestored 后才装配 WebView/loadUrl——cookie 就绪先于页面首批请求，
        // 消除「清空后未恢复」窗口期的登录态偶发丢失（loadUrl 与恢复的时序竞争）
        currentIntentToken++
        val token = currentIntentToken
        pendingSetupToken = token
        CookieSessionManager.restoreSnapshot(activity, activity.webappID, activity.webappTabIndex) {
            proceedSetup()
        }
    }

    /** cookie 恢复放行后的装配（原 handleIntent 尾段抽出）。
     * 防护：回调经主线程派发，IO 延迟期间 Activity 可能已销毁/换站——
     * isFinishing 或 id 变化时丢弃本次装配（onNewIntent 已触发新一轮） */
    private fun proceedSetup() {
        if (activity.isFinishing || activity.isDestroyed ||
            currentIntentToken != pendingSetupToken
        ) {
            return
        }
        if (activity.webapp == null) {
            // Toast is shown in getWebApp method
            activity.finish()
        } else {
            // 复用实例（onNewIntent）时旧 WebView 还在：先销毁释放，再新建
            if (activity.wv != null) {
                activity.pageChrome.cancelBlankScreenCheck()
                (activity.wv!!.parent as? ViewGroup)?.removeView(activity.wv)
                activity.wv!!.removeAllViews()
                activity.wv!!.destroy()
                activity.wv = null
            }
            // 统计埋点：记录打开次数
            StatsRecorder.recordLaunch(activity.webappID)
            try {
                setupWebView()
            } catch (e: Exception) {
                // WebView inflate 失败兜底（内核态损坏/崩溃恢复后 WebView 不可用——
                // "AwContents must be created if we are not posting" 等）：
                // 不崩溃，提示用户稍后重试（Activity 正常 finish）
                Log.e("WebViewActivity", "setupWebView failed", e)
                StatsRecorder.recordPageError(
                    activity.webappID, ErrorType.RENDER.name,
                    ErrorType.RENDER.code, "WebView init failed: " + (e.message ?: "")
                )
                activity.runOnUiThread {
                    // 提示后回主界面（finish 前跳转——用户可重新打开重试）
                    NotificationUtils.showInfoSnackbar(
                        activity,
                        activity.getString(R.string.webview_init_failed),
                        Snackbar.LENGTH_LONG
                    )
                    val backHome = Intent(activity, MainActivity::class.java)
                    backHome.addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    activity.startActivity(backHome)
                    activity.finish()
                }
            }
        }
    }

    private fun setupWebView() {
        // 局部非空快照：webapp 由 handleIntent 的非空分支保证、wv 下方刚赋值——
        // 快照后整函数不再依赖可空字段（消除 onNewIntent 极端时序下的竞态窗口）
        val app = activity.webapp ?: return
        activity.setContentView(R.layout.full_webview)

        if (app.isKeepAwake) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val url = app.baseUrl

        activity.progressBar = activity.findViewById(R.id.progressBar)
        activity.loadingAnimal = activity.findViewById(R.id.loadingAnimal)
        activity.loadingBg = activity.findViewById(R.id.loadingBg)

        // 必须在 setContentView 之后 findViewById——视图未建立时返回 null（886b145 回归修复）
        val webview = activity.findViewById<WebView>(R.id.webview).also { activity.wv = it }
        // 网页事件桥注册（P5：仅配规则站；JS 侧经 hook 代理转发）
        WebeventRuntime.attachBridge(webview, activity.webappID)

        // navigator.share 桥：站点分享按钮 → 系统分享面板
        WebShareBridge.attach(webview, activity)
        // Web Vitals 采集桥：页面加载细分（DNS/TCP/TTFB/FCP/LCP）落统计页
        WebPerfBridge.attach(webview, activity.webappID, activity.applicationContext)

        // 仅 debug 包开启 WebView 远程调试（chrome://inspect + CDP 自动化验证）；
        // release 永不开启——远程调试是安全敏感面，不得泄漏到生产
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // UA 尾巴清理 + 按站自定义 UA（QA 抽取：WebViewSetup 统一入口，
        // 反射结果进程内缓存 uaFieldName lazy）
        WebViewSetup.applyUserAgent(webview, app, uaFieldName)

        val darkMode = activity.darkMode
        if (app.isShowFullscreen) {
            darkMode.hideSystemBars()
        } else if (DataManager.getInstance().settings.alwaysShowSoftwareButtons) {
            darkMode.showSystemBars()
        }

        // 非全屏模式：内容避开系统栏/挖孔/键盘（全屏沉浸保持铺满，用户显式选择）
        if (!app.isShowFullscreen) {
            darkMode.applyContentInsets(activity.findViewById(R.id.webviewActivity))
        }
        webview.webViewClient = CustomBrowser(activity)
        // 按站全量设置（安全加固/渲染优化/JS/Cookie/桌面 UA，QA 抽取统一入口；
        // 语句顺序与原内联实现一致，深色模式在设置完成后应用）
        WebViewSetup.applySiteSettings(webview, app)

        darkMode.setDarkModeIfNeeded()

        activity.pageChrome.initCustomHeaders(app.isSendSavedataRequest)
        activity.pageChrome.loadURL(webview, url)
        webview.webChromeClient = CustomWebChromeClient(activity)
        activity.pageChrome.installDownloadListener(webview)
        activity.touchHandler.attach()
    }

    // ===== 生命周期实现体（Activity override 一行转发） =====

    fun onHostStart() {
        // 回前台：注销后台登记；被回收过则重建 WebView 并重载站点
        // （AI 会话数据在服务端，重载无损——用户拍板）
        WebviewRecycleRegistry.unregister(activity)
        if (recycled) {
            recycled = false
            handleIntent(activity.intent)
        }
    }

    fun onHostStop() {
        // 进后台：登记 LRU，等待系统内存信号分级回收（用时舒适/不用时安静）
        WebviewRecycleRegistry.register(activity)
    }

    /**
     * 后台分级回收入口（WebviewRecycleRegistry 调用）：官方销毁范式
     * （removeView→stopLoading→onPause→destroy，与矩阵 releaseCell 同规），
     * 保留 Activity 骨架——多任务卡片不消失，切回时 onStart 重建重载。
     */
    fun recycleWebView() {
        if (recycled || activity.isFinishing) return
        val webview = activity.wv ?: return
        (webview.parent as? ViewGroup)?.removeView(webview)
        webview.stopLoading()
        webview.onPause()
        webview.destroy()
        activity.wv = null
        recycled = true
        FeatureMetrics.count(FeatureMetrics.MODULE_WEBVIEW, "background_recycled")
    }

    fun onHostResume() {
        val newId = activity.intent.getIntExtra(Const.INTENT_WEBAPPID, -1)

        if (newId != activity.webappID) {
            val newWebapp = DataManager.getInstance().getWebApp(newId)
            if (newWebapp != null) {
                WebViewLauncher.startWebView(newWebapp, activity)
            }
        }

        if (activity.wv != null) {
            activity.wv!!.onResume()
            activity.wv!!.resumeTimers()
        }
        activity.darkMode.setDarkModeIfNeeded()

        val webapp = activity.webapp
        if (webapp != null && webapp.isAutoreload) {
            reloadHandler = Handler(Looper.getMainLooper())
            reload()
        }
    }

    fun onHostPause() {
        if (activity.wv != null) {
            activity.wv!!.evaluateJavascript(
                "document.querySelectorAll('audio').forEach(x => x.pause());" +
                    "document.querySelectorAll('video').forEach(x => x.pause());", null
            )
            activity.wv!!.onPause()
            activity.wv!!.pauseTimers()
        }
        // 统计埋点：落盘兜底（内存统计写入持久化）
        StatsRecorder.flush()

        val webapp = activity.webapp
        if (webapp != null &&
            (webapp.isClearCache || DataManager.getInstance().settings.isClearCache) &&
            activity.wv != null
        ) {
            activity.wv!!.clearCache(true)
        }

        if (reloadHandler != null) {
            reloadHandler!!.removeCallbacksAndMessages(null)
            Log.d("CLEANUP", "Stopped reload handler")
        }
    }

    /**
     * 内存压力回调：只做轻量操作。
     *
     * 注意：不可在此调用 WebView 重型方法（clearCache/freeMemory）——
     * 系统 dispatchTrimMemory 时 WebView 内部也在处理同一回调（WV.qi1.onTrimMemory），
     * 并发操作原生层会导致 SIGILL 崩溃（libwebviewchromium.so，模拟器实测复现）。
     * WebView 内存回收交给 onPause/onDestroy 的既有逻辑处理。
     */
    fun onHostTrimMemory(level: Int) {
        if (activity.wv == null) return

        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // 页面不可见：仅暂停计时器（轻量、线程安全）。
            // 豁免（P5-1）：配了生效事件规则的站不暂停——JS 停摆会让
            // 「切走等通知」核心场景失效；代价=该站后台略耗电（入口行已告知）
            if (!WebeventRuntime.shouldKeepTimersRunning(activity.webappID)) {
                activity.wv!!.pauseTimers()
            }
        }
    }

    fun onHostDestroy() {
        // 登录态隔离：开启隔离的 WebApp 保存 Cookie 快照（异步，多标签按 tabIndex）
        CookieSessionManager.saveSnapshot(activity, activity.webappID, activity.webappTabIndex)
        // 显式销毁 WebView，释放渲染进程与内存（低损耗目标）
        activity.pageChrome.cancelBlankScreenCheck()
        activity.pageChrome.stopReconnectWatch()
        if (activity.wv != null) {
            (activity.wv!!.parent as? ViewGroup)?.removeView(activity.wv)
            activity.wv!!.removeAllViews()
            activity.wv!!.destroy()
            activity.wv = null
        }
        reloadHandler?.removeCallbacksAndMessages(null)
    }

    // ===== 返回键语义（网页后退 → 重载首页 → 退任务） =====

    fun handleBackPress() {
        val webapp = DataManager.getInstance().getWebApp(activity.webappID)
        if (webapp == null) {
            activity.finish()
            return
        }

        if (activity.wv!!.canGoBack()) {
            activity.wv!!.goBack()
            return
        }

        if (quitOnNextBackpress) {
            quitOnNextBackpress = false
            activity.moveTaskToBack(true)
            return
        }

        activity.pageChrome.loadURL(activity.wv!!, webapp.baseUrl)
        quitOnNextBackpress = true
    }

    // ===== 自动刷新 =====

    private fun reload() {
        reloadHandler!!.postDelayed({
            activity.currentlyReloading = true
            activity.wv?.reload()
            reload()
        }, activity.webapp!!.timeAutoreload * 1000L)
    }
}
