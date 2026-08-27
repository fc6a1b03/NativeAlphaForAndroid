@file:Suppress("DEPRECATION", "UnusedMaterial3ApiOverride")

package com.cylonid.nativealpha

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
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
import android.webkit.RenderProcessGoneDetail
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
import com.cylonid.nativealpha.helper.IconPopupMenuHelper
import com.cylonid.nativealpha.helper.WebViewGestureHelper
import com.cylonid.nativealpha.helper.WebViewTouchHandler
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.ErrorType
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.CookieSessionManager
import com.cylonid.nativealpha.util.DateUtils
import com.cylonid.nativealpha.util.ErrorReporter
import com.cylonid.nativealpha.util.EntryPointUtils
import com.cylonid.nativealpha.util.LocaleUtils
import com.cylonid.nativealpha.util.NotificationUtils
import com.cylonid.nativealpha.util.StatsRecorder
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
class WebViewActivity : AppCompatActivity() {

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
    internal var webapp: WebApp? = null
    internal var urlOnFirstPageload = ""

    // 错误页重试目标（onReceivedError 主框架失败时记录，webnative://retry 用它重新加载）
    internal var retryUrl = ""
    private var fallbackToDefaultLongClickBehaviour = false
    private var mPopupMenu: PopupMenu? = null

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
    private val blankScreenCheck = Runnable { handleBlankScreen() }
    internal var pageLoadFinished = false

    // 统计埋点：页面加载开始时间（onPageStarted 到 onPageFinished 计算耗时）
    internal var pageLoadStartTime = 0L

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

    /** 初始化/重载：按 intent 的 webappID 加载 WebApp（复用实例时先清理旧 WebView） */
    private fun handleIntent(intent: Intent) {
        webappID = intent.getIntExtra(Const.INTENT_WEBAPPID, -1)
        webappTabIndex = intent.getIntExtra(Const.INTENT_TAB_INDEX, 0)
        EntryPointUtils.entryPointReached(this)
        // 重置错误页重试目标（新应用加载，避免残留旧地址）
        retryUrl = ""
        webapp = DataManager.getInstance().getWebApp(webappID)
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

        // 仅 debug 包开启 WebView 远程调试（chrome://inspect + CDP 自动化验证）；
        // release 永不开启——远程调试是安全敏感面，不得泄漏到生产
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // 移除 WebView 字段名注入的 UA 尾巴（找不到字段时静默跳过，避免 NPE）；
        // 反射结果进程内缓存（uaFieldName lazy）
        val fieldName = uaFieldName
        if (fieldName.isNotEmpty()) {
            val uaString = webview.settings.userAgentString.replace("; " + fieldName, "")
            webview.settings.userAgentString = uaString
        }
        if (app.isUseCustomUserAgent) {
            val customUa = app.userAgent
            if (!customUa.isNullOrEmpty()) {
                webview.settings.userAgentString = customUa
                    .replace("\u0000", "").replace("\n", "").replace("\r", "")
            }
        }

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
        // ===== 安全加固（WebApp 设置项，默认全开） =====
        // 恶意网站防护：默认关（AGENTS.md 既有设计：用户可添加非 HTTPS 站点，按需开启）
        webview.settings.safeBrowsingEnabled = app.isSafeBrowsing
        // 禁用文件访问：防止恶意站点读取本地文件
        webview.settings.allowFileAccess = !app.isFileAccessDisabled
        // 禁用内容提供器访问：防止站点访问系统 content:// 资源
        webview.settings.allowContentAccess = !app.isContentAccessDisabled
        // 混合内容拦截：HTTPS 页面禁止加载 HTTP 子资源
        webview.settings.mixedContentMode = if (app.isMixedContentBlocked)
            WebSettings.MIXED_CONTENT_NEVER_ALLOW
        else
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // JS 弹窗限制：禁止页面自动 window.open（用户手势触发的弹窗仍可用）
        webview.settings.javaScriptCanOpenWindowsAutomatically = !app.isJsPopupsRestricted
        webview.settings.domStorageEnabled = true
        webview.settings.databaseEnabled = true
        webview.settings.blockNetworkLoads = false

        // ===== 禁用浏览器自带滚动条（网页内容本身的自定义滚动条不受影响） =====
        webview.isVerticalScrollBarEnabled = false
        webview.isHorizontalScrollBarEnabled = false
        webview.overScrollMode = View.OVER_SCROLL_NEVER
        // 隐藏滚动条占位（WebView 默认 overlay 模式，但仍显式关闭占位）
        webview.scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY

        // ===== PWA 高频文本流渲染优化（流式输出/长文档滚动场景） =====
        // 渲染优先级拉满（文本流/长文档滚动核心）
        webview.settings.setRenderPriority(WebSettings.RenderPriority.HIGH)
        // 硬件加速强制（避免软件层合成拖慢流式更新）。minSdk=31，直接启用无需判断。
        webview.setLayerType(View.LAYER_TYPE_NONE, null)
        // 文字缩放（用户可调：50~200%，默认 100）
        webview.settings.textZoom = app.textZoom
        // 页面缩放（用户可调：50~200%，默认 100）：onPageFinished 里 zoomBy 应用
        // 预栅格化：减少滚动时白块/抖动（流式长文本滚动流畅）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
            WebSettingsCompat.setOffscreenPreRaster(webview.settings, true)
        }
        // 缓存策略：默认模式，流式页面不强制离线/不缓存
        webview.settings.cacheMode = WebSettings.LOAD_DEFAULT
        // 布局算法：NORMAL 对文本流最稳（SINGLE_COLUMN 会触发整页重排，流式更新开销大）
        webview.settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        // 编码：UTF-8 显式声明（中文流式文本解析正确，避免编码重排）
        webview.settings.defaultTextEncodingName = "UTF-8"
        // 关闭边缘高亮减少合成开销
        webview.overScrollMode = View.OVER_SCROLL_NEVER
        // 滚动条优化（长文本流式滚动）
        webview.scrollBarStyle = WebView.SCROLLBARS_OUTSIDE_OVERLAY
        webview.isScrollbarFadingEnabled = true
        // ===== PWA 渲染优化结束 =====

        this.setDarkModeIfNeeded()

        webview.settings.javaScriptEnabled = app.isAllowJs

        CookieManager.getInstance().setAcceptCookie(app.isAllowCookies)
        CookieManager.getInstance()
            .setAcceptThirdPartyCookies(wv, app.isAllowThirdPartyCookies)

        if (app.isBlockImages) {
            webview.settings.blockNetworkImage = true
        }

        if (app.isRequestDesktop) {
            webview.settings.userAgentString = Const.DESKTOP_USER_AGENT
            webview.settings.useWideViewPort = true
            webview.settings.loadWithOverviewMode = true

            webview.settings.setSupportZoom(true)
            webview.settings.builtInZoomControls = true
            webview.settings.displayZoomControls = false

            webview.scrollBarStyle = WebView.SCROLLBARS_OUTSIDE_OVERLAY
            webview.isScrollbarFadingEnabled = false
        }
        if (app.isEnableZooming) {
            webview.settings.setSupportZoom(true)
            webview.settings.builtInZoomControls = true
        }

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

    /** 显示 Compose 底部菜单（当前页叠加，WebView 保留在后面；滑杆实时预览，关闭即保存） */
    internal fun showWebViewMenuSheet() {
        val currentUrl = wv?.url ?: ""
        // 初始化页面缩放待保存值（防只调字体时把已保存的 pageZoom 覆盖成 100）
        mMenuPageZoom = webapp!!.pageZoom
        this.showWebViewMenuOverlay(
            currentUrl,
            wv!!.canGoBack(),
            wv!!.canGoForward(),
            webapp!!.textZoom,
            webapp!!.pageZoom,
            { action -> handleMenuAction(action); Unit },
            { zoom ->
                // 实时预览字体缩放
                wv?.settings?.textZoom = zoom
                Unit
            },
            { zoom ->
                // 实时预览页面缩放 + 记录待保存值（zoomBy 模拟捏合）
                mMenuPageZoom = zoom
                if (wv != null) {
                    webapp!!.pageZoom = zoom
                    applyPageZoom()
                }
                Unit
            },
            { saveZoomSettings(); Unit }
        )
    }

    /** 保存字体/缩放设置到 WebApp 原对象（菜单关闭时触发），不污染合并对象。
     *  菜单调整 = 应用设置（单一事实源，不存在优先级困惑）：
     *  跟随全局（override=false）时，先把当前生效配置整体继承为应用自身配置，
     *  再开 override——除本次缩放外，其他设置不因 override 切换而跳变。 */
    private fun saveZoomSettings() {
        if (wv == null || webapp == null) return
        val original =
            DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true) ?: return
        if (!original.isOverrideGlobalSettings) {
            original.copySettings(webapp!!) // webapp = 当前生效的合并对象
        }
        original.textZoom = wv!!.settings.textZoom
        original.pageZoom = mMenuPageZoom
        original.isOverrideGlobalSettings = true
        DataManager.getInstance().replaceWebApp(original)
    }

    /**
     * 页面缩放：setInitialScale（内容缩放，不改 viewport 布局模式）。
     * 必须在页面加载完成后调用才稳定（加载前设置对移动自适应页面无效）。
     * 不用 zoomBy：模拟捏合会触发缩放状态机，破坏 viewport 导致页面空白/布局错乱。
     */
    internal fun applyPageZoom() {
        if (wv == null || webapp == null) return
        val zoom = webapp!!.pageZoom
        wv!!.setInitialScale(zoom)
    }

    /** 菜单动作处理（异常进错误日志——实机排查唯一入口是导出日志） */
    private fun handleMenuAction(action: String) {
        try {
            handleMenuActionInner(action)
        } catch (e: Exception) {
            ErrorReporter.report(this, "MenuAction", "menu action failed: $action", e)
        }
    }

    private fun handleMenuActionInner(action: String) {
        when (action) {
            "back" -> onBackPressed()
            "forward" -> if (wv != null && wv!!.canGoForward()) wv!!.goForward()
            "reload" -> wv?.reload()
            "copy" -> {
                if (wv != null && wv!!.url != null) {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("URL", wv!!.url))
                }
            }
            "share" -> {
                if (wv != null && wv!!.url != null) {
                    ShareCompat.IntentBuilder(this@WebViewActivity)
                        .setType("text/plain")
                        .setChooserTitle("Share URL")
                        .setText(wv!!.url)
                        .startChooser()
                }
            }
            "home" -> {
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
            }
            "close" -> finishAndRemoveTask()
            "new_tab" -> {
                // 新增会话：sessionTabCount+1，跳到新标签（销毁当前，重建）
                addNewSessionTab()
            }
            "switch_tab" -> {
                // 切换会话：弹标签选择（单实例，选后销毁重建）
                showSessionSwitchDialog()
            }
            "delete_tab" -> {
                // 删除会话：会话数-1，重建回第一个标签
                deleteCurrentSessionTab()
            }
            "shortcuts" -> {
                // 组合快捷键面板（录制/发送，页面独有快捷键）
                showShortcutMenuSheet()
            }
            "settings" -> {
                // 跳转 WebApp 设置页（小菜单直达管理，与快捷键面板「管理」一致）
                val settingsIntent = Intent(this, WebAppSettingsActivity::class.java)
                settingsIntent.putExtra(Const.INTENT_WEBAPPID, webappID)
                startActivity(settingsIntent)
            }
        }
    }

    /** 新增会话：sessionTabCount+1（隔离模式下），保存当前快照后销毁重建到新标签 */
    private fun addNewSessionTab() {
        if (webapp == null) return
        val original =
            DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true) ?: return
        // 会话数+1（上限10，防内存）
        if (original.sessionTabCount < 10) {
            original.sessionTabCount = original.sessionTabCount + 1
            DataManager.getInstance().replaceWebApp(original)
        }
        val newTab = original.sessionTabCount - 1
        // 保存当前快照（异步）→ CLEAR_TOP 复用实例重载到新标签（不销毁，单实例）
        CookieSessionManager.saveSnapshot(this, webappID, webappTabIndex)
        WebViewLauncher.startWebViewById(webappID, newTab, this)
    }

    /** 切换会话：弹对话框列出所有会话标签，选一个销毁重建 */
    private fun showSessionSwitchDialog() {
        if (webapp == null) return
        val count = maxOf(1, webapp!!.sessionTabCount)
        // 二级会话菜单（简约）：列表切换 + "新增"；多会话才显示"删除"。
        // 禁用 setMessage：AlertDialog 的 message 与 items 互斥——message 存在时
        // 列表行不渲染（用户实测「看不到会话行只见按钮」的根因）；
        // 会话行文案已含「（当前）」标记，提示语并入 title 行
        val items = Array(count) { i ->
            if (i == webappTabIndex) getString(R.string.session_item_current, i + 1)
            else getString(R.string.session_item, i + 1)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_session))
            .setItems(items) { _, which ->
                if (which != webappTabIndex) {
                    ErrorReporter.runCatchingReport(
                        this, "SessionSwitch",
                        getString(R.string.session_switch_failed)
                    ) {
                        CookieSessionManager.saveSnapshot(this, webappID, webappTabIndex)
                        WebViewLauncher.startWebViewById(webappID, which, this)
                    }
                }
            }
            .setPositiveButton(R.string.session_add) { _, _ -> addNewSessionTab() }
        // 单会话不显示删除（至少保留一个）
        if (count > 1) {
            b.setNegativeButton(R.string.delete) { _, _ -> deleteCurrentSessionTab() }
        }
        b.show()
    }

    /** 删除会话：会话数-1，销毁重建到第一个会话（保留目标快照） */
    private fun deleteCurrentSessionTab() {
        if (webapp == null) return
        val original =
            DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true) ?: return
        val count = maxOf(1, original.sessionTabCount)
        if (count == 1) {
            // 单会话：不删除（至少保留一个），提示
            Toast.makeText(this, getString(R.string.session_at_least_one), Toast.LENGTH_SHORT)
                .show()
            return
        }
        original.sessionTabCount = count - 1
        DataManager.getInstance().replaceWebApp(original)
        // 保存快照 → CLEAR_TOP 复用重载到第一个会话（不销毁）
        CookieSessionManager.saveSnapshot(this, webappID, webappTabIndex)
        WebViewLauncher.startWebViewById(webappID, 0, this)
    }

    /** 显示组合快捷键面板（ModalBottomSheet，纯发送；管理在设置页） */
    private fun showShortcutMenuSheet() {
        this.showShortcutMenuOverlay(
            webappID
        ) { shortcut ->
            // 发送组合键到当前页面（JS 合成 KeyboardEvent）
            sendShortcutToPage(shortcut)
            Unit
        }
    }

    /** 保存快捷键到 WebApp 原对象 */
    private fun saveShortcutSettings() {
        val original =
            DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true) ?: return
        DataManager.getInstance().replaceWebApp(original)
    }

    /**
     * 统计缓存占用（异步，不阻塞主线程）：
     * - HTTP 缓存：cacheDir 递归求和（WebView 缓存目录，含 app_webview）
     * - 站点存储：WebStorage.getUsageForOrigin（localStorage/IndexedDB 等，回调异步补写）
     * 调用点：页面加载完成（onPageFinished）后，WebView 缓存已就绪。
     */
    internal fun recordCacheUsage() {
        if (wv == null) return
        try {
            // HTTP 缓存：cacheDir 递归求和（IO 操作，放 StatsRecorder 线程避免主线程卡顿）
            // 不依赖 getOrigins 回调：HTTP 缓存立即统计，站点存储回调补写（两者独立）
            StatsRecorder.record {
                try {
                    val httpBytes = dirSize(cacheDir)
                    updateStatsCache(httpBytes, -1L) // -1 表示站点存储待补
                } catch (e: Exception) {
                    // 缓存统计失败静默（不影响主功能）
                }
            }
            // 站点存储：异步查询（WebStorage 回调），回调后单独补写
            WebStorage.getInstance().getOrigins { originsMap ->
                var storeBytes = 0L
                if (originsMap != null) {
                    // getOrigins 回调为原始 Map：values 需强转 WebStorage.Origin
                    for (o in originsMap.values) {
                        if (o is WebStorage.Origin) {
                            storeBytes += if (o.quota > 0) o.usage else 0L
                        }
                    }
                }
                val finalStoreBytes = storeBytes
                StatsRecorder.record {
                    updateStatsCache(-1L, finalStoreBytes) // -1 表示 HTTP 缓存已统计
                }
            }
        } catch (e: Exception) {
            // 缓存统计失败静默（不影响主功能）
        }
    }

    /** 递归计算目录大小（字节） */
    private fun dirSize(dir: java.io.File): Long {
        var size = 0L
        try {
            val files = dir.listFiles()
            if (files != null) {
                for (f in files) {
                    if (f.isDirectory) {
                        size += dirSize(f)
                    } else {
                        size += f.length()
                    }
                }
            }
        } catch (ignored: Exception) {
            // 目录不可读/损坏：跳过（统计尽力而为）
        }
        return size
    }

    /** 更新 WebApp 缓存统计字段（原对象，防合并副本覆盖；-1 表示该值待补/已统计，跳过） */
    internal fun updateStatsCache(httpBytes: Long, storeBytes: Long) {
        val original =
            DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true) ?: return
        if (httpBytes >= 0) original.statCacheHttpBytes = httpBytes
        if (storeBytes >= 0) original.statCacheStoreBytes = storeBytes
        DataManager.getInstance().replaceWebApp(original)
    }

    /**
     * 发送组合键到当前页面。
     *
     * 优先：注入真实 KeyEvent（wv.dispatchKeyEvent）——WebView 将其转为
     * `isTrusted=true` 的 DOM 事件，严格校验可信度的页面（kimi code 等）也能收到。
     * 兜底：JS 合成 KeyboardEvent（isTrusted=false，部分页面忽略）。
     */
    internal fun sendShortcutToPage(shortcut: String?) {
        if (wv == null || shortcut.isNullOrEmpty()) return
        // 统计：记录发送次数（面板/统计页反馈）
        StatsRecorder.recordShortcutSent(webappID, shortcut)
        // 解析组合键 → keyCode + metaState（按字面量 "+" 分割，避免正则 crash）
        val parsed = parseShortcut(shortcut) ?: return
        val keyCode = keyCodeOf(parsed.key)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return
        val metaState = (if (parsed.ctrl) KeyEvent.META_CTRL_ON else 0) or
            (if (parsed.shift) KeyEvent.META_SHIFT_ON else 0) or
            (if (parsed.alt) KeyEvent.META_ALT_ON else 0)

        // 方案一：JS 合成 KeyboardEvent（主方案——kimi code 源码确认不校验 isTrusted）
        // 带 code 字段（CodeMirror 类编辑器按 e.code 匹配）+ 聚焦输入框（target 正确）
        injectJsWithFocus(parsed.ctrl, parsed.shift, parsed.alt, parsed.key)
        // 方案二：注入真实 KeyEvent（补充——对校验 isTrusted 的站点生效）
        // 保持当前页面焦点（不强行聚焦输入框——兼容多种网页）
        try {
            wv!!.requestFocus()
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
            val up = KeyEvent(now, now + 50, KeyEvent.ACTION_UP, keyCode, 0, metaState)
            wv!!.dispatchKeyEvent(down)
            wv!!.dispatchKeyEvent(up)
        } catch (ignored: Exception) {
            // KeyEvent 注入失败静默（JS 合成已发）
        }
    }

    /**
     * JS 合成 + 聚焦输入框：先聚焦页面输入框（kimi code 的 inject 需输入框有焦点），
     * 再向 activeElement 派发带 code 的 KeyboardEvent（CodeMirror 按 e.code 匹配）。
     */
    private fun injectJsWithFocus(ctrl: Boolean, shift: Boolean, alt: Boolean, key: String) {
        // 聚焦脚本：当前焦点是 body 时聚焦第一个输入框（textarea/contenteditable/input）
        val focusJs = "(function(){var t=document.activeElement;" +
            "if(!t||t===document.body){" +
            "var els=document.querySelectorAll('textarea,[contenteditable=true],input[type=text],input:not([type])');" +
            "if(els.length>0)els[0].focus();" +
            "}return true;})()"
        try {
            wv!!.evaluateJavascript(focusJs) { _ ->
                injectJsFallback(ctrl, shift, alt, key)
            }
        } catch (ignored: Exception) {
            injectJsFallback(ctrl, shift, alt, key)
        }
    }

    /** JS 合成 KeyboardEvent（kimi code 等不校验 isTrusted，合成事件可收到） */
    private fun injectJsFallback(ctrl: Boolean, shift: Boolean, alt: Boolean, key: String) {
        val jsKey = if (shift) key.uppercase(Locale.US) else key.lowercase(Locale.US)
        // code 字段：CodeMirror 类编辑器按 e.code（KeyS）匹配，必须带上
        val jsCode = keyCodeToJsCode(key)
        val js = "var t=document.activeElement||document.body;" +
            "t.dispatchEvent(new KeyboardEvent('keydown',{key:'" + jsKey + "',code:'" + jsCode +
            "',ctrlKey:" + ctrl + ",shiftKey:" + shift + ",altKey:" + alt +
            ",bubbles:true,cancelable:true}));" +
            "t.dispatchEvent(new KeyboardEvent('keyup',{key:'" + jsKey + "',code:'" + jsCode +
            "',ctrlKey:" + ctrl + ",shiftKey:" + shift + ",altKey:" + alt +
            ",bubbles:true,cancelable:true}));"
        try {
            wv?.evaluateJavascript(js, null)
        } catch (ignored: Exception) {
            // JS 注入失败静默
        }
    }

    /** 主键 → JS KeyboardEvent.code（KeyA..KeyZ / Digit0..9 / F1..F12 / Enter / Space / Tab / Backspace） */
    private fun keyCodeToJsCode(key: String): String {
        if (key.length == 1) {
            val c = key[0]
            if (c in 'A'..'Z') return "Key" + c
            if (c in 'a'..'z') return "Key" + c.uppercaseChar()
            if (c in '0'..'9') return "Digit" + c
        }
        return when (key) {
            "Enter" -> "Enter"
            "Space" -> "Space"
            "Tab" -> "Tab"
            "Backspace" -> "Backspace"
            "F1", "F2", "F3", "F4", "F5", "F6",
            "F7", "F8", "F9", "F10", "F11", "F12" -> key
            else -> ""
        }
    }

    /** 组合键主键字符串 → Android KeyCode（A-Z / 0-9 / F1-F12 / Enter / Space / Tab / Backspace） */
    private fun keyCodeOf(key: String): Int {
        if (key.length == 1) {
            val c = key[0]
            if (c in 'A'..'Z') return KeyEvent.KEYCODE_A + (c - 'A')
            if (c in 'a'..'z') return KeyEvent.KEYCODE_A + (c - 'a')
            if (c in '0'..'9') return KeyEvent.KEYCODE_0 + (c - '0')
        }
        return when (key) {
            "Enter" -> KeyEvent.KEYCODE_ENTER
            "Space" -> KeyEvent.KEYCODE_SPACE
            "Tab" -> KeyEvent.KEYCODE_TAB
            "Backspace" -> KeyEvent.KEYCODE_DEL
            "F1" -> KeyEvent.KEYCODE_F1
            "F2" -> KeyEvent.KEYCODE_F2
            "F3" -> KeyEvent.KEYCODE_F3
            "F4" -> KeyEvent.KEYCODE_F4
            "F5" -> KeyEvent.KEYCODE_F5
            "F6" -> KeyEvent.KEYCODE_F6
            "F7" -> KeyEvent.KEYCODE_F7
            "F8" -> KeyEvent.KEYCODE_F8
            "F9" -> KeyEvent.KEYCODE_F9
            "F10" -> KeyEvent.KEYCODE_F10
            "F11" -> KeyEvent.KEYCODE_F11
            "F12" -> KeyEvent.KEYCODE_F12
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    @SuppressLint("NonConstantResourceId")
    private fun showWebViewPopupMenu() {
        val center = findViewById<View>(R.id.anchorCenterScreen)
        mPopupMenu = IconPopupMenuHelper.getMenu(center, R.menu.wv_context_menu, this)

        val currentUrl = wv!!.url
        var title = ""
        if (currentUrl != null) {
            title = if (currentUrl.length < 32) currentUrl
            else currentUrl.substring(0, 32) + "…"
        }
        val spanStringWebAppTitle = SpannableString(title)

        // The item is disabled because it has no click action, but we want to override the disabled style (text color)
        val colorOnSurface = MaterialColors.getColor(
            center, com.google.android.material.R.attr.colorOnSurface, Color.BLACK
        )
        val foregroundColorSpan = ForegroundColorSpan(colorOnSurface)
        spanStringWebAppTitle.setSpan(
            foregroundColorSpan, 0, spanStringWebAppTitle.length, 0
        )

        spanStringWebAppTitle.setSpan(
            StyleSpan(Typeface.BOLD), 0, spanStringWebAppTitle.length, 0
        )
        mPopupMenu!!.menu[0].title = spanStringWebAppTitle

        for (i in 0 until mPopupMenu!!.menu.size) {
            val item = mPopupMenu!!.menu[i]
            val spanString = SpannableString(item.title)
            spanString.setSpan(foregroundColorSpan, 0, spanString.length, 0)
            item.title = spanString
        }
        if (wv!!.canGoForward()) {
            val forwardItem = mPopupMenu!!.menu.findItem(R.id.cmItemForward)
            forwardItem?.isVisible = true
        }
        if (BuildConfig.DEBUG) {
            val debugItem = mPopupMenu!!.menu.findItem(R.id.cmFallbackContextmenuTemp)
            debugItem?.isVisible = true
        }
        mPopupMenu!!.setOnMenuItemClickListener { menuItem ->
            val id = menuItem.itemId
            if (id == R.id.cmItemForward) {
                wv!!.goForward()
                return@setOnMenuItemClickListener true
            }
            if (id == R.id.cmItemBack) {
                onBackPressed()
                return@setOnMenuItemClickListener true
            }
            if (id == R.id.cmItemReload) {
                wv!!.reload()
                return@setOnMenuItemClickListener true
            }
            if (id == R.id.cmItemCopyUrl) {
                val clipboard = getSystemService(ClipboardManager::class.java)
                val clip = ClipData.newPlainText("URL", wv!!.url)
                clipboard.setPrimaryClip(clip)
                return@setOnMenuItemClickListener true
            }
            if (id == R.id.cmItemShareUrl) {
                ShareCompat.IntentBuilder(this@WebViewActivity)
                    .setType("text/plain")
                    .setChooserTitle("Share URL")
                    .setText(wv!!.url)
                    .startChooser()
                return@setOnMenuItemClickListener true
            }
            if (id == R.id.cmItemCloseWebApp) {
                finishAndRemoveTask()
                return@setOnMenuItemClickListener true
            }
            if (id == R.id.cmFallbackContextmenuTemp) {
                fallbackToDefaultLongClickBehaviour = true
                return@setOnMenuItemClickListener true
            }
            if (id == R.id.cmMainMenu) {
                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
                return@setOnMenuItemClickListener true
            }
            false
        }

        mPopupMenu!!.show()
    }

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
        mPopupMenu?.dismiss()

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
            // 页面不可见：仅暂停计时器（轻量、线程安全）
            wv!!.pauseTimers()
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
                val key = keyCodeToChar(keyCode, shift)
                if (key != null) {
                    val shortcut = buildShortcutString(ctrl, shift, alt, key)
                    // 已绑定快捷键：拦截发送（不触发浏览器默认）
                    if (isBoundShortcut(shortcut)) {
                        sendShortcutToPage(shortcut)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 是否已绑定的组合键 */
    internal fun isBoundShortcut(shortcut: String): Boolean {
        val w = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true)
        return w != null && w.keyShortcuts.contains(shortcut)
    }

    /** 构建组合键字符串（Ctrl+S / Ctrl+Shift+S） */
    internal fun buildShortcutString(ctrl: Boolean, shift: Boolean, alt: Boolean, key: String): String {
        val sb = StringBuilder()
        if (ctrl) sb.append("Ctrl+")
        if (shift) sb.append("Shift+")
        if (alt) sb.append("Alt+")
        sb.append(key)
        return sb.toString()
    }

    /** keyCode → 字符（字母/数字/功能键） */
    private fun keyCodeToChar(keyCode: Int, shift: Boolean): String? {
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            val c = 'a' + (keyCode - KeyEvent.KEYCODE_A)
            return if (shift) c.uppercaseChar().toString() else c.toString()
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            return ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_F1 -> "F1"
            KeyEvent.KEYCODE_F2 -> "F2"
            KeyEvent.KEYCODE_F3 -> "F3"
            KeyEvent.KEYCODE_F4 -> "F4"
            KeyEvent.KEYCODE_F5 -> "F5"
            KeyEvent.KEYCODE_F6 -> "F6"
            KeyEvent.KEYCODE_F7 -> "F7"
            KeyEvent.KEYCODE_F8 -> "F8"
            KeyEvent.KEYCODE_F9 -> "F9"
            KeyEvent.KEYCODE_F10 -> "F10"
            KeyEvent.KEYCODE_F11 -> "F11"
            KeyEvent.KEYCODE_F12 -> "F12"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_SPACE -> " "
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            else -> null
        }
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
        // minSdk=31，WindowInsetsController API 始终可用
        window.setDecorFitsSystemWindows(false)
        val controller = window.insetsController
        if (controller != null) {
            controller.hide(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
            controller.setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            )
        }
    }

    internal fun showSystemBars() {
        if (webapp!!.isShowFullscreen) return
        // minSdk=31，WindowInsetsController API 始终可用
        window.setDecorFitsSystemWindows(true)
        val controller = window.insetsController
        if (controller != null) {
            controller.show(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
            controller.setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        var allGranted = grantResults.isNotEmpty()
        for (r in grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                allGranted = false
                break
            }
        }

        if (allGranted) {
            onPermissionsGranted(requestCode, permissions.toList())
        } else {
            onPermissionsDenied(requestCode, permissions.toList())
        }
    }

    internal fun enablePermissionBoolOnWebApp(successCallback: PermissionGrantedCallback) {
        webapp!!.isOverrideGlobalSettings = true
        successCallback.execute()
        DataManager.getInstance().replaceWebApp(webapp!!)
        wv!!.reload()
    }

    private fun onPermissionsGranted(requestCode: Int, list: List<String>) {
        if (requestCode == Const.PERMISSION_RC_LOCATION) {
            enablePermissionBoolOnWebApp { webapp!!.isAllowLocationAccess = true }
            this.handleGeoPermissionCallback(true)
        }
        if (requestCode == Const.PERMISSION_CAMERA) {
            enablePermissionBoolOnWebApp { webapp!!.isCameraPermission = true }
        }
    }

    private fun onPermissionsDenied(requestCode: Int, list: List<String>) {
        if (requestCode == Const.PERMISSION_RC_LOCATION) {
            this.handleGeoPermissionCallback(false)
        }
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
        try {
            // 缩放跟随生效配置（原固定 130 独立配置已废弃）
            if (webapp != null) {
                wv!!.settings.textZoom = webapp!!.textZoom
                applyPageZoom()
            }
            val lang = LocaleUtils.fileEnding
            val safeCode = code ?: ""
            val safeDesc = desc ?: ""
            // URL 编码 desc（含空格/特殊字符安全）
            val encodedDesc = java.net.URLEncoder.encode(safeDesc, "UTF-8")
            wv!!.loadUrl(
                "file:///android_asset/errorSite/error_" + lang
                    + ".html?code=" + safeCode + "&desc=" + encodedDesc
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
