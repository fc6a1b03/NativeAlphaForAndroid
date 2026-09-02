@file:Suppress("DEPRECATION", "UnusedMaterial3ApiOverride")

package com.cylonid.nativealpha

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.AnimationDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.ActionMode
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.window.OnBackInvokedDispatcher
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.ShareCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.core.view.size
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.cylonid.nativealpha.util.SystemBars
import com.cylonid.nativealpha.helper.WebViewGestureHelper
import com.cylonid.nativealpha.helper.WebViewTouchHandler
import com.cylonid.nativealpha.helper.WebViewShortcutInjectHelper
import com.cylonid.nativealpha.helper.WebViewMenuHelper
import com.cylonid.nativealpha.helper.WebViewPermissionHelper
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.util.SiteReconnectSupervisor
import com.cylonid.nativealpha.model.ErrorType
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.CookieSessionManager
import com.cylonid.nativealpha.util.DateUtils
import com.cylonid.nativealpha.util.ErrorReporter
import com.cylonid.nativealpha.util.EntryPointUtils
import com.cylonid.nativealpha.util.LocaleUtils
import com.cylonid.nativealpha.util.LoadFailureClassifier
import com.cylonid.nativealpha.util.NotificationUtils
import com.cylonid.nativealpha.util.StatsRecorder
import com.cylonid.nativealpha.util.WebViewSetup
import com.cylonid.nativealpha.util.ThemeUtils
import com.cylonid.nativealpha.util.Utility
import com.cylonid.nativealpha.ui.showShortcutMenuOverlay
import com.cylonid.nativealpha.ui.showWebViewMenuOverlay
import com.cylonid.nativealpha.util.WebViewLauncher
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.Calendar
import java.util.Collections
import java.util.Locale

/**
 * 渲染核心 Activity：WebView 配置/回调/手势/下载/权限/错误页（第四刀 Java→Kotlin 机械翻译）。
 * 翻译原则：零语义变更，行行可对照原 Java 版（git 历史 e5543ba 前）。
 */
class WebViewActivity : AppCompatActivity(), WebViewSiteContext, SystemBars.SelfManagedInsets {

    companion object {
        /**
         * 双击空白判定脚本（占位符 %1$f/%2$f 为双击坐标）。
         * P1 起脚本构建与返回值语义契约收口 WebViewGestureHelper——JS 返回语义
         * 与 Kotlin 消费端同源，五类返回值可穷举单测防漂移；此处仅实例化结果
         * （P3 手势拆分时随触摸监听整体迁移 helper）。
         */
        private val LONGPRESS_JS = WebViewGestureHelper.buildLongPressJs()

        /** 组合键解析结果 */
        data class ShortcutParseResult(
            val ctrl: Boolean,
            val shift: Boolean,
            val alt: Boolean,
            val key: String
        )

        /**
         * 解析组合键字符串（如 "Ctrl+Shift+S"）。
         *
         * 防御性处理：旧数据或异常调用可能传入单独 "+"、空段、未知主键等，
         * 这里按字面量 "+" 分割——避免把用户输入当正则 compile 导致 crash。
         * 只有识别到有效主键时才返回结果。
         */
        fun parseShortcut(shortcut: String): ShortcutParseResult? {
            var ctrl = false
            var shift = false
            var alt = false
            var key = ""
            for (part in shortcut.split("+")) {
                val p = part.trim()
                when (p) {
                    "Ctrl" -> ctrl = true
                    "Shift" -> shift = true
                    "Alt" -> alt = true
                    "" -> {} // 连续 + 或首尾 + 产生的空段，忽略
                    else -> key = p
                }
            }
            return if (key.isNotEmpty()) {
                ShortcutParseResult(ctrl, shift, alt, key)
            } else {
                null
            }
        }
    }

    var webappID = -1
    var webappTabIndex = 0

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

    /** WebView 特性探测缓存：内核能力进程生命周期内不变，避免每次打开重复探测 */
    private val featForceDark: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)
    }
    private val featForceDarkStrategy: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)
    }
    private val featAlgorithmicDarkening: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
    }
    internal var wv: WebView? = null
    internal var progressBar: ProgressBar? = null
    internal var loadingAnimal: ImageView? = null
    internal var loadingBg: View? = null

    /** 加载页动物动画最长显示时间（ms）：站点自带 loader/慢加载站点不长期盖住 */
    internal val animalMaxShowMs = 3000L

    /** 动画显示起止计时（onProgressChanged 里判定短暂显示窗口用） */
    internal var pageLoadStartTime2 = 0L
    internal var currentlyReloading = true
    internal var mGeoPermissionRequestCallback: GeolocationPermissions.Callback? = null
    internal var mGeoPermissionRequestOrigin: String? = null
    internal var customHeaders: Map<String, String>? = null
    var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** 文件选择器 Activity Result Launcher（替换废弃的 startActivityForResult/onActivityResult）。 */
    val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_CANCELED) {
            this.filePathCallback?.onReceiveValue(null)
        } else if (result.resultCode == RESULT_OK) {
            filePathCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }
        filePathCallback = null
    }

    private var quitOnNextBackpress = false
    private var reloadHandler: Handler? = null
    override var webapp: WebApp? = null

    // ===== WebViewSiteContext 实现（QA 基类抽取：站点行为经接口访问宿主） =====
    override val siteContext: Context get() = this
    override val webappId: Int get() = webappID

    override var urlOnFirstPageload: String = ""

    // 错误页重试目标（onReceivedError 主框架失败时记录，webnative://retry 用它重新加载）
    override var retryUrl: String = ""

    // 长按动态分流：系统是否已启动文本/链接选择 ActionMode（区分「有内容」vs「空白处」）
    private var actionModeActive = false

    // 当前系统文本选择 ActionMode（空白长按时 finish 取消）
    private var currentActionMode: ActionMode? = null

    // 权限审计：记录已发起过系统请求的权限（区分「首次请求」vs「永久拒绝」）
    internal val requestedPermissions = HashSet<String>()

    // 白屏检测：当前加载最后进度 + 进度推进时间戳（无推进超时判定白屏）
    internal var lastProgress = 0
    internal var lastProgressTime = 0L
    private val blankScreenHandler = Handler()

    /** 长按检测（媒体保存菜单）：按下 600ms 无移动触发 */
    /** 触摸手势处理器（P3 第一刀迁移 helper/WebViewTouchHandler） */
    internal val touchHandler = WebViewTouchHandler(this)

    /** 组合键注入处理器（P3 第三刀迁移 helper/WebViewShortcutInjectHelper） */
    internal val shortcutHelper = WebViewShortcutInjectHelper(this)

    /** 菜单浮层处理器（P3 第四刀迁移 helper/WebViewMenuHelper） */
    internal val menuHelper = WebViewMenuHelper(this)

    /** 权限分流处理器（P3 第五刀迁移 helper/WebViewPermissionHelper） */
    internal val permissionHelper = WebViewPermissionHelper(this)

    /** 委托：WebViewTouchHandler 双击菜单调用点保持零改动 */
    internal fun showWebViewMenuSheet() = menuHelper.showWebViewMenuSheet()

    /** 委托：页面加载完成回调的缩放生效点保持零改动 */
    override fun applyPageZoom() = menuHelper.applyPageZoom()

    /** 委托：onPageFinished 缓存统计入口保持零改动 */
    override fun recordCacheUsage() = menuHelper.recordCacheUsage()

    // ===== WebViewSiteContext 加载生命周期钩子（宿主侧：白屏检测/动物动画） =====

    override fun onPageLoadStarted() {
        // 新页面加载：重置白屏检测（进度从 0 重新计时）
        pageLoadFinished = false
        // 主帧失败标记清除（新导航开始——错误页 finished 不再误判成功）
        lastMainFrameFailed = false
        lastProgress = 0
        lastProgressTime = System.currentTimeMillis()
        // 加载动画计时起点（短暂显示窗口判定）
        pageLoadStartTime2 = System.currentTimeMillis()
        scheduleBlankScreenCheck()
        // 网页事件 hook 注入（P5：仅配规则站，幂等脚本；未配站零开销）
        wv?.let { webview ->
            com.cylonid.nativealpha.webevent.WebeventRuntime.hookScriptFor(webappID)?.let { script ->
                webview.evaluateJavascript(script, null)
            }
        }
    }

    override fun onPageLoadFinished() {
        // 加载完成：取消白屏检测（避免误判）
        pageLoadFinished = true
        cancelBlankScreenCheck()
        // 页面加载完成：隐藏加载页动物动画
        stopLoadingAnimal()
        // 成功加载即停止断线探测（恢复闭环完成）；失败页的 finished
        // 不算成功（lastMainFrameFailed），监视继续等探测通过
        if (!lastMainFrameFailed) stopReconnectWatch()
        // hook 存活探针：规则失效显式提示的数据源——注入过的幂等标记在
        // SPA 换文档/站点改版后是否仍在（站点改版致 hook 挂载失败时，
        // 规则入口卡显示「可能失效」而非静默无效）
        if (!lastMainFrameFailed &&
            com.cylonid.nativealpha.webevent.EventRuleStore.hasActiveRules(webappID)
        ) {
            wv?.evaluateJavascript(
                "String(window." +
                    com.cylonid.nativealpha.webevent.JsHookScript.IDEMPOTENCY_FLAG +
                    " === true)"
            ) { result ->
                com.cylonid.nativealpha.webevent.WebeventRuntime.onHookProbe(
                    webappID, result?.contains("true") == true
                )
            }
        }
    }

    override fun showCustomErrorPage(code: String?, desc: String?) {
        loadCustomErrorPage(code, desc)
    }

    override fun onHttpAuthRequested(handler: HttpAuthHandler, authHost: String, realm: String) {
        showHttpAuthDialog(handler, authHost, realm)
    }

    override fun refreshDarkModeOnMainThread() {
        runOnUiThread { setDarkModeIfNeeded() }
    }

    override fun loadSiteUrl(view: WebView, url: String) {
        loadURL(view, url)
    }

    override fun recordPageLoadDuration(durationMs: Long) {
        StatsRecorder.recordPageLoaded(webappID, durationMs)
    }

    override fun recordPageError(errorType: String, code: String, desc: String) {
        StatsRecorder.recordPageError(webappID, errorType, code, desc)
    }

    /**
     * 断线自动恢复（连接治理下半场）：加载失败后原生探测站点可达性，
     * 恢复后自动 reload retryUrl（与错误页 Try again 同目标同语义）。
     * 探测目标用站点 baseUrl——子路径可能 404 但站点本身活着。
     */
    private val reconnectSupervisor by lazy {
        SiteReconnectSupervisor(applicationContext, lifecycleScope)
    }

    override fun startReconnectWatch() {
        val baseUrl = DataManager.getInstance().getWebApp(webappID)?.baseUrl ?: return
        reconnectSupervisor.start(baseUrl) { _ ->
            runOnUiThread {
                val target = retryUrl.ifBlank { baseUrl }
                val wv = wv ?: return@runOnUiThread
                if (isFinishing || isDestroyed) return@runOnUiThread
                loadURL(wv, target)
            }
        }
    }

    override fun stopReconnectWatch() {
        reconnectSupervisor.stop()
    }

    /** 菜单「后退」入口：onBackPressed 为 protected，helper 经此转发（语义不变） */
    internal fun triggerBack() = onBackPressed()
    private val blankScreenCheck = Runnable { handleBlankScreen() }
    internal var pageLoadFinished = false

    /** 上次主帧是否失败（错误页 finished 与真成功的区分依据） */
    private var lastMainFrameFailed = false

    // 统计埋点：页面加载开始时间（onPageStarted 到 onPageFinished 计算耗时）
    override var pageLoadStartTime: Long = 0L

    /** 菜单中页面缩放预览值（保存时写回 webapp） */
    private var mMenuPageZoom = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题前置（规范 R「Activity 规范」）：App.onCreate 已全局 applyUiMode，
        // 此处再应用保证单测/多进程路径下主题一致；setTheme 必须先于 super
        // （super 里 AppCompat 锁定主题后不可换）
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)
        // 虚拟按键/手势返回（Android 13+ OnBackInvokedDispatcher）：
        // Manifest enableOnBackInvokedCallback=true 时 onBackPressed 不再被系统调用，
        // **必须注册回调**——否则按返回直接退出 Activity 而不触发网页后退（用户反馈的核心 bug）。
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT
                ) { onBackPressed() }
            } catch (ignored: Exception) {
                // 注册失败退回系统默认（老路径仍可用）
            }
        }
        handleIntent(intent)
    }

    /**
     * 复用实例时（documentLaunchMode=intoExisting 或任务栈复用）：
     * 更新 webappID 并重新加载对应 WebApp——防止打开新应用时仍显示旧错误页。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 初始化/重载：按 intent 的 webappID 加载 WebApp（复用实例时先清理旧 WebView）。
     * 扫码临时浏览（INTENT_RAW_URL）例外：构建负 ID 瞬态站点——不注册、
     * 统计零写入（StatsRecorder/DataManager 对负 id 静默跳过），标题取 host。 */
    private fun handleIntent(intent: Intent) {
        val rawUrl = intent.getStringExtra(Const.INTENT_RAW_URL)
        if (rawUrl != null) {
            webappID = -1
            webapp = WebApp(rawUrl, -1, -1).apply {
                title = com.cylonid.nativealpha.util.UrlUtils.displayHost(rawUrl)
            }
        } else {
            webappID = intent.getIntExtra(Const.INTENT_WEBAPPID, -1)
            webapp = DataManager.getInstance().getWebApp(webappID)
        }
        webappTabIndex = intent.getIntExtra(Const.INTENT_TAB_INDEX, 0)
        EntryPointUtils.entryPointReached(this)
        // 重置错误页重试目标（新应用加载，避免残留旧地址）
        retryUrl = ""
        // 登录态隔离：开启隔离的 WebApp 恢复自己的 Cookie 会话（异步，多标签按 tabIndex）。
        // onRestored 后才装配 WebView/loadUrl——cookie 就绪先于页面首批请求，
        // 消除「清空后未恢复」窗口期的登录态偶发丢失（loadUrl 与恢复的时序竞争）
        currentIntentToken++
        val token = currentIntentToken
        pendingSetupToken = token
        CookieSessionManager.restoreSnapshot(this, webappID, webappTabIndex) {
            proceedSetup()
        }
    }

    /** 装配令牌：onNewIntent 重入时递增，旧 cookie 回调因 token 不匹配被丢弃 */
    private var pendingSetupToken = 0
    private var currentIntentToken = 0

    /** cookie 恢复放行后的装配（原 handleIntent 尾段抽出）。
     * 防护：回调经主线程派发，IO 延迟期间 Activity 可能已销毁/换站——
     * isFinishing 或 id 变化时丢弃本次装配（onNewIntent 已触发新一轮） */
    private fun proceedSetup() {
        if (isFinishing || isDestroyed || currentIntentToken != pendingSetupToken) {
            return
        }
        if (webapp == null) {
            // Toast is shown in getWebApp method
            finish()
        } else {
            // 复用实例（onNewIntent）时旧 WebView 还在：先销毁释放，再新建
            if (wv != null) {
                cancelBlankScreenCheck()
                (wv!!.parent as? ViewGroup)?.removeView(wv)
                wv!!.removeAllViews()
                wv!!.destroy()
                wv = null
            }
            // 统计埋点：记录打开次数
            StatsRecorder.recordLaunch(webappID)
            try {
                setupWebView()
            } catch (e: Exception) {
                // WebView inflate 失败兜底（内核态损坏/崩溃恢复后 WebView 不可用——
                // "AwContents must be created if we are not posting" 等）：
                // 不崩溃，提示用户稍后重试（Activity 正常 finish）
                Log.e("WebViewActivity", "setupWebView failed", e)
                StatsRecorder.recordPageError(
                    webappID, ErrorType.RENDER.name,
                    ErrorType.RENDER.code, "WebView init failed: " + (e.message ?: "")
                )
                runOnUiThread {
                    // 提示后回主界面（finish 前跳转——用户可重新打开重试）
                    NotificationUtils.showInfoSnackbar(
                        this@WebViewActivity,
                        getString(R.string.webview_init_failed),
                        Snackbar.LENGTH_LONG
                    )
                    val backHome = Intent(this@WebViewActivity, MainActivity::class.java)
                    backHome.addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    startActivity(backHome)
                    finish()
                }
            }
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun setupWebView() {
        // 局部非空快照：webapp 由 handleIntent 的非空分支保证、wv 下方刚赋值——
        // 快照后整函数不再依赖可空字段（消除 onNewIntent 极端时序下的竞态窗口）
        val app = webapp ?: return
        setContentView(R.layout.full_webview)

        if (app.isKeepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val url = app.baseUrl

        progressBar = findViewById(R.id.progressBar)
        loadingAnimal = findViewById(R.id.loadingAnimal)
        loadingBg = findViewById(R.id.loadingBg)

        // 必须在 setContentView 之后 findViewById——视图未建立时返回 null（886b145 回归修复）
        val webview = findViewById<WebView>(R.id.webview).also { wv = it }
        // 网页事件桥注册（P5：仅配规则站；JS 侧经 hook 代理转发）
        com.cylonid.nativealpha.webevent.WebeventRuntime.attachBridge(webview, webappID)

        // 仅 debug 包开启 WebView 远程调试（chrome://inspect + CDP 自动化验证）；
        // release 永不开启——远程调试是安全敏感面，不得泄漏到生产
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // UA 尾巴清理 + 按站自定义 UA（QA 抽取：WebViewSetup 统一入口，
        // 反射结果进程内缓存 uaFieldName lazy）
        WebViewSetup.applyUserAgent(webview, app, uaFieldName)

        if (app.isShowFullscreen) {
            this.hideSystemBars()
        } else if (DataManager.getInstance().settings.alwaysShowSoftwareButtons) {
            this.showSystemBars()
        }

        // 异形屏自适应（targetSdk 35+ 强制 edge-to-edge，setDecorFitsSystemWindows 已失效）：
        // 非全屏模式 WebView 内容避开系统栏（顶部状态栏/挖孔、底部导航栏/手势条），
        // 全屏沉浸模式保持铺满（用户显式选择）。
        // insets 挂根布局而非 WebView：WebView 的 insets 分发可能被父容器消费，
        // 且三键导航/手势条切换时根布局 insets 更可靠。
        if (!app.isShowFullscreen) {
            val root = findViewById<View>(R.id.webviewActivity)
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
                val bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
                )
                // 键盘弹出时避开（防御：部分输入法/机型 adjustResize 不生效）
                val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
                val bottom = maxOf(bars.bottom, ime.bottom)
                v.setPadding(0, bars.top, 0, bottom)
                windowInsets
            }
        }
        webview.webViewClient = CustomBrowser(this)
        // 按站全量设置（安全加固/渲染优化/JS/Cookie/桌面 UA，QA 抽取统一入口；
        // 语句顺序与原内联实现一致，深色模式在设置完成后应用）
        WebViewSetup.applySiteSettings(webview, app)

        this.setDarkModeIfNeeded()

        customHeaders = initCustomHeaders(app.isSendSavedataRequest)
        loadURL(webview, url)
        webview.webChromeClient = CustomWebChromeClient(this)
        webview.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            if (mimeType == "application/pdf") {
                val i = Intent(Intent.ACTION_VIEW)
                i.data = Uri.parse(dlUrl)
                startActivity(i)
            } else {
                if (dlUrl.isNotEmpty()) {
                    var target = dlUrl
                    if (target.startsWith("blob:")) {
                        target = target.replace("blob:", "")
                        try {
                            target = URLDecoder.decode(target, "UTF-8")
                        } catch (e: UnsupportedEncodingException) {
                            e.printStackTrace()
                        }
                    }
                    val request = try {
                        DownloadManager.Request(Uri.parse(target))
                    } catch (e: Exception) {
                        NotificationUtils.showInfoSnackbar(
                            this, getString(R.string.file_download), Snackbar.LENGTH_SHORT
                        )
                        null
                    }
                    if (request != null) {
                        val fileName =
                            Utility.getFileNameFromDownload(target, contentDisposition, mimeType)
                        request.setMimeType(mimeType)
                        request.addRequestHeader(
                            "cookie", CookieManager.getInstance().getCookie(target)
                        )
                        request.addRequestHeader("User-Agent", userAgent)
                        request.setTitle(fileName)
                        request.allowScanningByMediaScanner()
                        request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, fileName
                        )
                        // minSdk=31，下载到公共目录无需存储权限，直接入队
                        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager?
                        if (dm != null) {
                            dm.enqueue(request)
                            NotificationUtils.showInfoSnackbar(
                                this, getString(R.string.file_download),
                                Snackbar.LENGTH_SHORT
                            )
                        }
                    }
                }
            }
        }
        touchHandler.attach()
    }

    @SuppressLint("RequiresFeature")
    internal fun setDarkModeIfNeeded() {
        if (webapp == null || wv == null) {
            return
        }
        val needsForcedDarkMode = webapp!!.isUseTimespanDarkMode &&
            DateUtils.isInInterval(
                DateUtils.convertStringToCalendar(webapp!!.timespanDarkModeBegin)!!,
                Calendar.getInstance(),
                DateUtils.convertStringToCalendar(webapp!!.timespanDarkModeEnd)!!
            )
            || (!webapp!!.isUseTimespanDarkMode && webapp!!.isForceDarkMode)

        // minSdk=31，强制深色能力始终可用；内部仍按 API 33 分界线处理废弃 API
        // 特性探测缓存（featXxx lazy）：内核能力进程内不变
        val isAlgorithmicDarkeningSupported = featAlgorithmicDarkening

        if (needsForcedDarkMode) {
            wv!!.setBackgroundColor(Color.BLACK)
            delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+: setForceDark / setForceDarkStrategy / setForceDarkAllowed 已废弃且为 no-op
                if (isAlgorithmicDarkeningSupported) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv!!.settings, true)
                }
            } else {
                val isForceDarkSupported = featForceDark
                val isForceDarkStrategySupported = featForceDarkStrategy
                wv!!.setForceDarkAllowed(true)
                if (isForceDarkSupported) {
                    WebSettingsCompat.setForceDark(
                        wv!!.settings, WebSettingsCompat.FORCE_DARK_ON
                    )
                }
                if (isForceDarkStrategySupported) {
                    WebSettingsCompat.setForceDarkStrategy(
                        wv!!.settings,
                        WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
                    )
                }
                if (isAlgorithmicDarkeningSupported) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv!!.settings, true)
                }
            }
        } else {
            delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            // 加载页背景跟随应用主题（不固定白底）：深色主题下避免加载白屏闪瞎
            // 读当前主题 colorBackground（浅色 #FBF8FF / 深色 #131318）
            var themeBg = Color.WHITE
            try {
                val tv = TypedValue()
                if (theme.resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                    themeBg = tv.data
                }
            } catch (ignored: Exception) {
            }
            wv!!.setBackgroundColor(themeBg)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // API 33+: setForceDark / setForceDarkStrategy / setForceDarkAllowed 已废弃且为 no-op
                if (isAlgorithmicDarkeningSupported) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv!!.settings, false)
                }
            } else {
                val isForceDarkSupported = featForceDark
                val isForceDarkStrategySupported = featForceDarkStrategy
                if (isForceDarkSupported) {
                    WebSettingsCompat.setForceDark(
                        wv!!.settings, WebSettingsCompat.FORCE_DARK_OFF
                    )
                }
                if (isForceDarkStrategySupported) {
                    WebSettingsCompat.setForceDarkStrategy(
                        wv!!.settings,
                        WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY
                    )
                }
                if (isAlgorithmicDarkeningSupported) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv!!.settings, false)
                }
            }
        }
    }

    /** 安全前进（WebView 状态异常时不崩溃——手势误触/内核态异常兜底） */
    internal fun safeGoForward() {
        try {
            if (wv != null && wv!!.canGoForward()) wv!!.goForward()
        } catch (e: Exception) {
            Log.w("WebViewActivity", "safeGoForward failed", e)
        }
    }

    /** 安全后退（WebView 状态异常时回落到系统返回） */
    internal fun safeBackPressed() {
        try {
            if (wv != null && wv!!.canGoBack()) {
                wv!!.goBack()
            } else {
                onBackPressed()
            }
        } catch (e: Exception) {
            Log.w("WebViewActivity", "safeBackPressed failed", e)
        }
    }

    /** 安全刷新（reload 异常兜底） */
    internal fun safeReload() {
        try {
            wv?.reload()
        } catch (e: Exception) {
            Log.w("WebViewActivity", "safeReload failed", e)
        }
    }

    /** 启动加载页动物走路动画（ImageView + AnimationDrawable）+ 主题背景 */
    internal fun startLoadingAnimal() {
        try {
            if (loadingAnimal == null) return
            // 主题背景同步显示：加载期 WebView 内容未渲染，铺主题色防深色白屏
            if (loadingBg != null && loadingBg!!.visibility != View.VISIBLE) {
                loadingBg!!.visibility = View.VISIBLE
            }
            if (loadingAnimal!!.visibility != View.VISIBLE) {
                loadingAnimal!!.visibility = View.VISIBLE
            }
            val anim = loadingAnimal!!.drawable as? AnimationDrawable
            if (anim != null && !anim.isRunning) {
                anim.start()
            }
        } catch (ignored: Exception) {
            // 动画启动失败不影响主功能
        }
    }

    /** 停止并隐藏加载页动物动画 + 主题背景 */
    internal fun stopLoadingAnimal() {
        try {
            if (loadingBg != null && loadingBg!!.visibility != View.GONE) {
                loadingBg!!.visibility = View.GONE
            }
            if (loadingAnimal == null) return
            val anim = loadingAnimal!!.drawable as? AnimationDrawable
            if (anim != null && anim.isRunning) {
                anim.stop()
            }
            loadingAnimal!!.visibility = View.GONE
        } catch (ignored: Exception) {
        }
    }

    /**
     * 发送组合键到当前页面。
     *
     * 优先：注入真实 KeyEvent（wv.dispatchKeyEvent）——WebView 将其转为
     * `isTrusted=true` 的 DOM 事件，严格校验可信度的页面（kimi code 等）也能收到。
     * 兜底：JS 合成 KeyboardEvent（isTrusted=false，部分页面忽略）。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        this.setDarkModeIfNeeded()
    }

    // 渲染核心的有意实现：WebView 后退优先 + 再按退出，不走 super（避免双重处理）
    // 注：Manifest enableOnBackInvokedCallback=true 下系统手势仍会回调本方法（legacy 兼容）
    @SuppressLint("MissingSuperCall", "GestureBackNavigation")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val webapp = DataManager.getInstance().getWebApp(webappID)
        if (webapp == null) {
            finish()
            return
        }

        if (wv!!.canGoBack()) {
            wv!!.goBack()
            return
        }

        if (quitOnNextBackpress) {
            quitOnNextBackpress = false
            moveTaskToBack(true)
            return
        }

        loadURL(wv!!, webapp.baseUrl)
        quitOnNextBackpress = true
    }

    override fun onResume() {
        super.onResume()
        val newId = intent.getIntExtra(Const.INTENT_WEBAPPID, -1)

        if (newId != webappID) {
            val newWebapp = DataManager.getInstance().getWebApp(newId)
            if (newWebapp != null) {
                WebViewLauncher.startWebView(newWebapp, this)
            }
        }

        if (wv != null) {
            wv!!.onResume()
            wv!!.resumeTimers()
        }
        this.setDarkModeIfNeeded()

        if (webapp != null && webapp!!.isAutoreload) {
            reloadHandler = Handler()
            reload()
        }
    }

    override fun onPause() {
        super.onPause()

        if (wv != null) {
            wv!!.evaluateJavascript(
                "document.querySelectorAll('audio').forEach(x => x.pause());" +
                    "document.querySelectorAll('video').forEach(x => x.pause());", null
            )
            wv!!.onPause()
            wv!!.pauseTimers()
        }
        // 统计埋点：落盘兜底（内存统计写入持久化）
        StatsRecorder.flush()

        if (webapp != null &&
            (webapp!!.isClearCache || DataManager.getInstance().settings.isClearCache) &&
            wv != null
        ) {
            wv!!.clearCache(true)
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
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (wv == null) return

        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // 页面不可见：仅暂停计时器（轻量、线程安全）。
            // 豁免（P5-1）：配了生效事件规则的站不暂停——JS 停摆会让
            // 「切走等通知」核心场景失效；代价=该站后台略耗电（入口行已告知）
            if (!com.cylonid.nativealpha.webevent.WebeventRuntime.shouldKeepTimersRunning(webappID)) {
                wv!!.pauseTimers()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 组合快捷键：已绑定组合键拦截发送（不触发浏览器默认），管理在设置页点选录入
        if (event.action == KeyEvent.ACTION_DOWN) {
            val ctrl = event.isCtrlPressed
            val shift = event.isShiftPressed
            val alt = event.isAltPressed
            val keyCode = event.keyCode
            // 仅捕获组合键（Ctrl/Shift/Alt 单独按下不处理）
            if (ctrl || shift || alt) {
                val key = shortcutHelper.keyCodeToChar(keyCode, shift)
                if (key != null) {
                    val shortcut = shortcutHelper.buildShortcutString(ctrl, shift, alt, key)
                    // 已绑定快捷键：拦截发送（不触发浏览器默认）
                    if (shortcutHelper.isBoundShortcut(shortcut)) {
                        shortcutHelper.sendShortcutToPage(shortcut)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 文本/链接选择 ActionMode 启动（系统长按选择时回调）：
     * 标记 actionModeActive，供长按延迟检测区分「有内容可操作」vs「空白处」。
     */
    override fun onActionModeStarted(mode: ActionMode) {
        actionModeActive = true
        currentActionMode = mode
        super.onActionModeStarted(mode)
    }

    override fun onActionModeFinished(mode: ActionMode) {
        actionModeActive = false
        if (currentActionMode == mode) currentActionMode = null
        super.onActionModeFinished(mode)
    }

    override fun onDestroy() {
        // 登录态隔离：开启隔离的 WebApp 保存 Cookie 快照（异步，多标签按 tabIndex）
        CookieSessionManager.saveSnapshot(this, webappID, webappTabIndex)
        // 显式销毁 WebView，释放渲染进程与内存（低损耗目标）
        cancelBlankScreenCheck()
        stopReconnectWatch()
        if (wv != null) {
            (wv!!.parent as? ViewGroup)?.removeView(wv)
            wv!!.removeAllViews()
            wv!!.destroy()
            wv = null
        }
        reloadHandler?.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun reload() {
        reloadHandler!!.postDelayed({
            currentlyReloading = true
            wv?.reload()
            reload()
        }, webapp!!.timeAutoreload * 1000L)
    }

    fun getWebView(): WebView? {
        return wv
    }

    private fun initCustomHeaders(saveData: Boolean): Map<String, String> {
        val extraHeaders = HashMap<String, String>()
        extraHeaders["DNT"] = "1"
        extraHeaders["X-REQUESTED-WITH"] = ""
        extraHeaders["Accept-Language"] = LocaleUtils.acceptLanguage
        if (saveData) {
            extraHeaders["Save-Data"] = "on"
        }
        return Collections.unmodifiableMap(extraHeaders)
    }

    internal fun loadURL(view: WebView, url: String) {
        val webApp = DataManager.getInstance().getWebApp(webappID)
        if (webApp == null) {
            finish()
            return
        }
        if (url.contains("http://") && !webApp.isAllowHttp) {
            val builder = AlertDialog.Builder(this@WebViewActivity)

            builder.setTitle(getString(R.string.no_https_dialog_title))
            builder.setMessage(getString(R.string.no_https_dialog_msg))
            builder.setIcon(android.R.drawable.ic_dialog_alert)
            builder.setPositiveButton(getString(R.string.no_https_dialog_accept)) { _, _ ->
                // 必须写回列表内的存储实例（ignoreOverride=true 取活对象）——
                // 上方 webApp 在 override=false 时是合并副本，改副本保存会静默丢失
                val stored =
                    DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true)
                if (stored != null) {
                    stored.isAllowHttp = true
                    stored.isOverrideGlobalSettings = true
                    // 统一写收口：发射 flow（设置页返回后列表/设置即时反映）+ 触发持久化
                    DataManager.getInstance().commitChanges()
                }
                view.loadUrl(url, customHeaders!!)
            }
            builder.setNegativeButton(getString(android.R.string.cancel)) { _, _ -> finish() }
            val dialog = builder.create()
            dialog.show()
        } else {
            view.loadUrl(url, customHeaders!!)
        }
    }

    internal fun hideSystemBars() {
        // 全屏能力收编 SystemBars（C-系统栏）：行为与原私有实现等价
        SystemBars.enterImmersive(this)
    }

    internal fun showSystemBars() {
        if (webapp!!.isShowFullscreen) return
        // 全屏能力收编 SystemBars（C-系统栏）：行为与原私有实现等价
        SystemBars.exitImmersive(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    internal fun enablePermissionBoolOnWebApp(successCallback: PermissionGrantedCallback) {
        webapp!!.isOverrideGlobalSettings = true
        successCallback.execute()
        DataManager.getInstance().replaceWebApp(webapp!!)
        wv!!.reload()
    }

    internal fun handleGeoPermissionCallback(allow: Boolean) {
        if (mGeoPermissionRequestCallback != null) {
            mGeoPermissionRequestCallback!!
                .invoke(mGeoPermissionRequestOrigin, allow, false)
            mGeoPermissionRequestCallback = null
        }
    }


    /** 权限授予回调（函数式接口，对齐原 Java @FunctionalInterface） */
    fun interface PermissionGrantedCallback {
        fun execute()
    }

    /**
     * 白屏检测：进度在 20s 内无推进 → 判定加载卡死，加载错误页并提示重试。
     * 只在新页面加载开始后计时，进度推进即重置；加载完成即取消。
     * AI 流式页进度持续推进（onProgressChanged 持续回调），不会误判。
     */
    internal fun scheduleBlankScreenCheck() {
        blankScreenHandler.removeCallbacks(blankScreenCheck)
        if (!pageLoadFinished) {
            blankScreenHandler.postDelayed(blankScreenCheck, Const.BLANK_SCREEN_TIMEOUT_MS.toLong())
        }
    }

    internal fun cancelBlankScreenCheck() {
        blankScreenHandler.removeCallbacks(blankScreenCheck)
    }

    private fun handleBlankScreen() {
        if (pageLoadFinished || wv == null) return
        val idle = System.currentTimeMillis() - lastProgressTime
        if (idle >= Const.BLANK_SCREEN_TIMEOUT_MS && lastProgress < 100) {
            // 加载卡死：加载本地错误页（带重试），避免白屏挂起
            runOnUiThread {
                NotificationUtils.showInfoSnackbar(
                    this@WebViewActivity,
                    getString(R.string.blank_screen_detected),
                    Snackbar.LENGTH_LONG
                )
                // 加载中断：重置计时起点，避免错误页误计为页面加载耗时
                pageLoadStartTime = 0
                wv!!.stopLoading()
                loadCustomErrorPage("timeout", getString(R.string.blank_screen_detected))
            }
        }
    }

    /**
     * 加载自定义错误页（M3 靛蓝统一风格，替代系统默认白屏）。
     * 带错误码/描述参数（query 传入，页面显示开发者向信息）。
     * 语言：跟随 LocaleUtils（zh/en）。
     * 字体/页面缩放：跟随当前生效配置（与页面同一套，不再有独立缩放）。
     */
    internal fun loadCustomErrorPage(code: String?, desc: String?) {
        if (wv == null) return
        // 主帧失败标记：错误页自身也会回调 onPageFinished——finished 时
        // 据此区分「真成功」与「失败页完成」，避免误停断线监视
        lastMainFrameFailed = true
        try {
            // 缩放跟随生效配置（原固定 130 独立配置已废弃）
            if (webapp != null) {
                wv!!.settings.textZoom = webapp!!.textZoom
                applyPageZoom()
            }
            val lang = LocaleUtils.fileEnding
            val safeCode = code ?: ""
            val safeDesc = desc ?: ""
            // 本地化原因行（分类驱动）：证书/地址类终态给出可行动提示，
            // 瞬态失败给「会自动恢复」预期
            val reasonRes = when (LoadFailureClassifier.classify(safeCode, safeDesc)) {
                LoadFailureClassifier.Kind.SECURITY -> R.string.load_failure_hint_security
                LoadFailureClassifier.Kind.BAD_ADDRESS -> R.string.load_failure_hint_bad_address
                LoadFailureClassifier.Kind.RETRYABLE -> R.string.load_failure_hint_retryable
            }
            val encodedReason = java.net.URLEncoder.encode(getString(reasonRes), "UTF-8")
            // URL 编码 desc（含空格/特殊字符安全）
            val encodedDesc = java.net.URLEncoder.encode(safeDesc, "UTF-8")
            wv!!.loadUrl(
                "file:///android_asset/errorSite/error_" + lang
                    + ".html?code=" + safeCode + "&desc=" + encodedDesc
                    + "&reason=" + encodedReason
            )
        } catch (ignored: Exception) {
            // 错误页加载失败静默（保持现状）
        }
    }

    internal fun showHttpAuthDialog(handler: HttpAuthHandler, host: String, realm: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_http_auth, null)
        AlertDialog.Builder(this)
            .setView(view)
            .setTitle(getString(R.string.http_auth_title))
            .setMessage(getString(R.string.enter_http_auth_credentials, realm, host))
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val username = view.findViewById<EditText>(R.id.username)
                    .text.toString()
                val password = view.findViewById<EditText>(R.id.password)
                    .text.toString()
                handler.proceed(username, password)
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> handler.cancel() }
            .show()
    }

// ChromeClient/BrowserClient 已拆出（R3）：
// WebViewChromeClient.kt / WebViewBrowserClient.kt（host 注入，行为零变更）
}
