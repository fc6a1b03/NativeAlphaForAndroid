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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.ShareCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.cylonid.nativealpha.helper.IconPopupMenuHelper
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.ErrorType
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.CookieSessionManager
import com.cylonid.nativealpha.util.DateUtils
import com.cylonid.nativealpha.util.EntryPointUtils
import com.cylonid.nativealpha.util.LocaleUtils
import com.cylonid.nativealpha.util.NotificationUtils
import com.cylonid.nativealpha.util.StatsRecorder
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
        // Constants for touchlistener
        private const val NONE = 0
        private const val SWIPE = 1
        private const val SINGLE_FINGER = 2

        /** 单指左右滑手势触发距离（px）：比 TRESHOLD 更严——只识别明确意图的长滑，防误触 */
        private const val GESTURE_SWIPE_MIN_PX = 150

        /** 长按触发时间（ms）——长按媒体直接下载（500ms 主流折中：不误触不迟钝） */
        private const val LONG_PRESS_MS = 500L

        /** 单指手势边缘区宽度（屏幕横向比例）：起点在左右各 <20% 或 >80% 才识别左右滑（页面内表格不冲突） */
        private const val GESTURE_EDGE_ZONE_RATIO = 0.2f

        /** 水平位移 > 垂直位移的倍数：过滤倾斜滑动（上下滚动不误判左右手势） */
        private const val GESTURE_HORIZONTAL_DOMINANCE = 1.2f
        private const val TRESHOLD = 100

        /**
         * 双击空白判定 JS：检测双击点是否真正落在可见文本字符上。
         * caretRangeFromPoint（浏览器内部命中）+ 文本节点矩形命中验证——
         * 空白处 caretRangeFromPoint 会返回"最近插入位置"，必须加矩形命中
         * 才能区分「点在字符上」vs「点在空白但邻近文本」。
         * 兼容 Taro/WebComponents：自定义组件文字系统可选中，但标准 DOM 探测
         * 命中不到——此时按 text 处理（双击文字不弹菜单，交还系统选中单词）。
         * 返回 'text' 或 'blank'。
         */
        private val LONGPRESS_JS =
            "(function(){" +
                "var px=%1\$f,py=%2\$f;" +
                "var dpr=window.devicePixelRatio||1;" +
                "var innerW=window.innerWidth||document.documentElement.clientWidth;" +
                "var outerW=window.outerWidth||innerW;" +
                "var scale=dpr*outerW/innerW;" +
                "if(!(scale>0)||scale===1){scale=1;}" +
                "var x=px/scale,y=py/scale;" +
                "var e=document.elementFromPoint(x,y);" +
                "if(!e)return 'blank';" +
                "var tag=e.tagName?e.tagName.toLowerCase():'';" +
                "if(tag==='html'||tag==='body')return 'blank';" +
                // 交互元素检测：双击落在按钮/链接/输入等可操作元素上时交还网页，
                // 只有真空白才弹小菜单（用户需求：菜单只属于无功能空白区）
                "var it=e;" +
                "while(it&&it!==document.body){" +
                "var t2=it.tagName?it.tagName.toLowerCase():'';" +
                "if(t2==='button'||t2==='a'||t2==='input'||t2==='select'||t2==='textarea'" +
                "||t2==='option'||t2==='label'||t2==='form'||t2==='details'||t2==='summary'" +
                "||t2==='audio'||t2==='embed'||t2==='object')return 'interactive';" +
                "var role=it.getAttribute?it.getAttribute('role'):'';" +
                "if(role==='button'||role==='link'||role==='tab'||role==='checkbox'" +
                "||role==='radio'||role==='switch'||role==='menuitem'||role==='slider'" +
                "||role==='combobox'||role==='listbox'||role==='option'||role==='textbox'" +
                "||role==='searchbox')return 'interactive';" +
                "if(it.isContentEditable)return 'interactive';" +
                "var tb=it.getAttribute?it.getAttribute('tabindex'):null;" +
                "if(tb&&tb!=='-1')return 'interactive';" +
                "it=it.parentElement;" +
                "}" +
                "var te=e;" +
                "while(te&&te!==document.body){" +
                "var tt=te.tagName?te.tagName.toLowerCase():'';" +
                "if(tt==='img'||tt==='canvas'||tt==='svg'||tt==='video'||tt==='iframe')return 'media';" +
                "te=te.parentElement;" +
                "}" +
                "var range=null;" +
                "if(document.caretRangeFromPoint){range=document.caretRangeFromPoint(x,y);}" +
                "if(range&&range.startContainer){" +
                "var n=range.startContainer;" +
                "if(n.nodeType===3){" +
                "var len=n.length||0;" +
                "if(range.startOffset>0&&range.startOffset<len){" +
                "var full=document.createRange();" +
                "full.selectNodeContents(n);" +
                "var rect=full.getBoundingClientRect();" +
                "if(x>=rect.left&&x<=rect.right&&y>=rect.top&&y<=rect.bottom){" +
                "return 'text';" +
                "}" +
                "}" +
                "}" +
                "}" +
                "return 'blank';})()"

        /**
         * 图片长按检测 JS：返回长按点命中的 img 的绝对 URL（src/currentSrc），
         * 没命中返回 'null'。**只匹配 img**——视频不做长按下载（全屏播放/页面视频均不误触）。
         * 与 LONGPRESS_JS 的坐标换算保持一致（devicePixelRatio + 视口缩放）。
         */
        private val MEDIA_LONGPRESS_JS =
            "(function(){" +
                "var px=%1\$f,py=%2\$f;" +
                "var dpr=window.devicePixelRatio||1;" +
                "var innerW=window.innerWidth||document.documentElement.clientWidth;" +
                "var outerW=window.outerWidth||innerW;" +
                "var scale=dpr*outerW/innerW;" +
                "if(!(scale>0)||scale===1){scale=1;}" +
                "var x=px/scale,y=py/scale;" +
                "var e=document.elementFromPoint(x,y);" +
                "if(!e)return 'null';" +
                "var te=e;" +
                "while(te&&te!==document.body){" +
                "var tt=te.tagName?te.tagName.toLowerCase():'';" +
                "if(tt==='img'){var s=te.currentSrc||te.src;" +
                "if(s&&s.indexOf('data:')!==0)return s;" +
                "return 'null';}" +
                "te=te.parentElement;" +
                "}" +
                "return 'null';})()"
    }

    var webappID = -1
    var webappTabIndex = 0
    private var wv: WebView? = null
    private var progressBar: ProgressBar? = null
    private var loadingAnimal: ImageView? = null
    private var loadingBg: View? = null

    /** 加载页动物动画最长显示时间（ms）：站点自带 loader/慢加载站点不长期盖住 */
    private val animalMaxShowMs = 3000L

    /** 动画显示起止计时（onProgressChanged 里判定短暂显示窗口用） */
    private var pageLoadStartTime2 = 0L
    private var currentlyReloading = true
    private var mGeoPermissionRequestCallback: GeolocationPermissions.Callback? = null
    private var mGeoPermissionRequestOrigin: String? = null
    private var dlRequest: DownloadManager.Request? = null
    private var customHeaders: Map<String, String>? = null
    var filePathCallback: ValueCallback<Array<Uri>>? = null

    private var quitOnNextBackpress = false
    private var reloadHandler: Handler? = null
    private var webapp: WebApp? = null
    private var urlOnFirstPageload = ""

    // 错误页重试目标（onReceivedError 主框架失败时记录，webnative://retry 用它重新加载）
    private var retryUrl = ""
    private var fallbackToDefaultLongClickBehaviour = false
    private var mPopupMenu: PopupMenu? = null

    // 长按动态分流：系统是否已启动文本/链接选择 ActionMode（区分「有内容」vs「空白处」）
    private var actionModeActive = false

    // 当前系统文本选择 ActionMode（空白长按时 finish 取消）
    private var currentActionMode: ActionMode? = null

    // 权限审计：记录已发起过系统请求的权限（区分「首次请求」vs「永久拒绝」）
    private val requestedPermissions = HashSet<String>()

    // 白屏检测：当前加载最后进度 + 进度推进时间戳（无推进超时判定白屏）
    private var lastProgress = 0
    private var lastProgressTime = 0L
    private val blankScreenHandler = Handler()

    /** 长按检测（媒体保存菜单）：按下 600ms 无移动触发 */
    private val longPressHandler = Handler()
    private var longPressRunnable: Runnable? = null
    private val blankScreenCheck = Runnable { handleBlankScreen() }
    private var pageLoadFinished = false

    // 统计埋点：页面加载开始时间（onPageStarted 到 onPageFinished 计算耗时）
    private var pageLoadStartTime = 0L

    /** 菜单中页面缩放预览值（保存时写回 webapp） */
    private var mMenuPageZoom = 100

    override fun onCreate(savedInstanceState: Bundle?) {
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
        // 登录态隔离：开启隔离的 WebApp 恢复自己的 Cookie 会话（异步，多标签按 tabIndex）
        CookieSessionManager.restoreSnapshot(this, webappID, webappTabIndex)
        if (webapp == null) {
            // Toast is shown in getWebApp method
            finish()
        } else {
            // 复用实例（onNewIntent）时旧 WebView 还在：先销毁释放，再新建
            if (wv != null) {
                cancelBlankScreenCheck()
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

        // 移除 WebView 字段名注入的 UA 尾巴（找不到字段时静默跳过，避免 NPE）
        val fieldName = WebViewActivity::class.java.declaredFields
            .firstOrNull { it.type == WebView::class.java }?.name ?: ""
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
        webview.webViewClient = CustomBrowser()
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
        // 硬件加速强制（避免软件层合成拖慢流式更新）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webview.setLayerType(View.LAYER_TYPE_NONE, null)
        }
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
        webview.webChromeClient = CustomWebChromeClient()
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
                        val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager?
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            val perms = arrayOf(
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            )
                            var allGranted = true
                            for (perm in perms) {
                                if (ContextCompat.checkSelfPermission(
                                        this@WebViewActivity, perm
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    allGranted = false
                                    break
                                }
                            }
                            if (!allGranted) {
                                dlRequest = request
                                ActivityCompat.requestPermissions(
                                    this@WebViewActivity, perms, Const.PERMISSION_RC_STORAGE
                                )
                            } else {
                                if (dm != null) {
                                    dm.enqueue(request)
                                    NotificationUtils.showInfoSnackbar(
                                        this, getString(R.string.file_download),
                                        Snackbar.LENGTH_SHORT
                                    )
                                }
                            }
                        }
                        // No storage permission needed for Android 10+
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        }
        attachTouchListener()
    }

    /** 收起软键盘（双击空白弹小菜单时调用，避免输入法和小菜单打架） */
    private fun hideSoftKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as
                InputMethodManager
            imm.hideSoftInputFromWindow(wv!!.windowToken, 0)
        } catch (ignored: Exception) {
            // 收起失败不影响小菜单弹出
        }
    }

    /** 触摸手势（双击菜单/长按媒体下载/多指切换/边缘左右滑）——原匿名 OnTouchListener 逐行翻译 */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchListener() {
        var mode = NONE
        var startX = 0f
        var stopX = 0f
        var startY = 0f
        var stopY = 0f
        // 双击检测：双击空白 → 弹小菜单。
        // 设计：长按完全交还系统（文字选中 100% 正常，不再与系统 ActionMode
        // 竞争——此前空白长按判定在真实站点频繁误判，是历史 bug 根因）。
        // 双击是纯手势识别（300ms 内同点二次按下），系统在空白处无默认行为。
        // 自实现不用 GestureDetector：其内部状态机对注入事件/快速连点
        // 识别不稳定，自实现时间戳+坐标判定简单可靠。
        var lastDownTime = 0L
        var lastDownX = -1f
        var lastDownY = -1f

        /** 双击空白判定：JS 检测双击点是否落在文本字符上，空白则弹小菜单 */
        fun checkBlankAndShowMenu(px: Float, py: Float) {
            if (isFinishing || wv == null) return
            val js = String.format(Locale.US, LONGPRESS_JS, px, py)
            val mediaJs = String.format(Locale.US, MEDIA_LONGPRESS_JS, px, py)
            wv!!.evaluateJavascript(js) { value ->
                var type = value?.replace("\"", "") ?: "blank"
                if (type == "null" || type.isEmpty()) type = "blank"
                val isBlank = type == "blank"
                if (isBlank && !isFinishing && wv != null) {
                    // 空白检测通过，再探测是否命中 media（双击图片时弹保存菜单而非小菜单）
                    wv!!.evaluateJavascript(mediaJs) { mediaValue ->
                        val mediaUrl = mediaValue?.replace("\"", "") ?: "null"
                        if (mediaUrl == "null" || mediaUrl.isEmpty() || !isBlank) {
                            // 非 media（空白/文本）→ 走原有小菜单
                            runOnUiThread {
                                if (wv != null) {
                                    // 双击弹菜单时收起输入法：blur 失焦（键盘必收且小菜单
                                    // 关闭后不弹回）+ hideSoftInput 兜底
                                    wv!!.evaluateJavascript(
                                        "window.getSelection().removeAllRanges();", null
                                    )
                                    // 输入框失焦：键盘必然收起且不再弹
                                    wv!!.evaluateJavascript(
                                        "var el=document.activeElement;"
                                            + "if(el&&(el.tagName==='INPUT'||el.tagName==='TEXTAREA'||el.isContentEditable)){el.blur();}",
                                        null
                                    )
                                }
                                // 收起输入法（兜底，blur 已处理主要路径）
                                hideSoftKeyboard()
                                showWebViewMenuSheet()
                            }
                        } else {
                            // 命中图片/视频：交还 WebView（双击放大由网页处理；保存走长按）
                            Log.d("LongPress", "media double-tap -> webview handles")
                        }
                    }
                }
            }
        }

        /** 判断下载 URL 是否为视频（去 query/fragment 后按后缀；带签名参数的 CDN 链接也能识别） */
        fun isVideoUrl(url: String?): Boolean {
            if (url == null) return false
            var clean = url
            val q = clean.indexOf('?')
            if (q >= 0) clean = clean.substring(0, q)
            val h = clean.indexOf('#')
            if (h >= 0) clean = clean.substring(0, h)
            clean = clean.lowercase(Locale.US)
            return clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mov")
                || clean.endsWith(".ogg") || clean.endsWith(".mkv") || clean.endsWith(".m3u8")
        }

        /** 保存图片/视频：复用 DownloadManager（与全站下载一致的统一处理） */
        fun downloadMedia(url: String?) {
            if (url.isNullOrEmpty()) {
                NotificationUtils.showToast(
                    this@WebViewActivity,
                    getString(R.string.file_download), Toast.LENGTH_SHORT
                )
                return
            }
            var dlUrl = url
            if (dlUrl.startsWith("blob:")) {
                dlUrl = dlUrl.replace("blob:", "")
                try {
                    dlUrl = URLDecoder.decode(dlUrl, "UTF-8")
                } catch (ignored: UnsupportedEncodingException) {
                }
            }
            try {
                val request = DownloadManager.Request(Uri.parse(dlUrl))
                val isMediaVideo = isVideoUrl(dlUrl)
                request.setMimeType(if (isMediaVideo) "video/*" else "image/*")
                var fileName = Utility.getFileNameFromDownload(dlUrl, null, null)
                if (fileName.isNullOrEmpty()) {
                    fileName = "media_" + System.currentTimeMillis() +
                        (if (isMediaVideo) ".mp4" else ".png")
                }
                request.setTitle(fileName)
                request.setDescription(getString(R.string.file_download_started))
                request.allowScanningByMediaScanner()
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager?
                if (dm != null) {
                    // 与「应用更新」一致：系统 DownloadManager + 开始/完成通知；开始即 Snackbar 提示
                    dm.enqueue(request)
                    NotificationUtils.showInfoSnackbar(
                        this@WebViewActivity,
                        getString(R.string.file_download_started),
                        Snackbar.LENGTH_SHORT
                    )
                }
            } catch (e: Exception) {
                NotificationUtils.showToast(
                    this@WebViewActivity,
                    getString(R.string.file_download), Toast.LENGTH_SHORT
                )
            }
        }

        /** 取消长按检测（移动/抬起/多指时） */
        fun cancelLongPress() {
            if (longPressRunnable != null) {
                longPressHandler.removeCallbacks(longPressRunnable!!)
                longPressRunnable = null
            }
        }

        /** 长按命中 media：直接下载（用户需求——长按即存，无中间菜单） */
        fun checkMediaLongPress(px: Float, py: Float) {
            if (isFinishing || wv == null) return
            val mediaJs = String.format(Locale.US, MEDIA_LONGPRESS_JS, px, py)
            wv!!.evaluateJavascript(mediaJs) { value ->
                val mediaUrl = value?.replace("\"", "") ?: "null"
                if (mediaUrl != "null" && mediaUrl.isNotEmpty() && !isFinishing) {
                    runOnUiThread {
                        // 直接下载（DownloadManager）；Toast 提示已存
                        downloadMedia(mediaUrl)
                    }
                }
            }
        }

        wv!!.setOnTouchListener { _, event ->
            val webapp = DataManager.getInstance().getWebApp(webappID)
            if (webapp == null || webapp.isRequestDesktop) {
                return@setOnTouchListener false
            }
            // 鼠标/触控笔源不参与触摸手势：双击/长按/多指语义均针对手指设计，
            // 鼠标滚轮(hover+scroll 走 onGenericMotionEvent)与左键快连曾误判双击弹菜单
            if (event.getSource() and InputDevice.SOURCE_CLASS_POINTER != 0
                && event.isFromSource(InputDevice.SOURCE_MOUSE)
            ) {
                return@setOnTouchListener false
            }

            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    // 双击检测：250ms 内同点（±40px）再次按下 → 双击 → 弹小菜单。
                    // 250ms/40px 是快速双击窗口：慢速点击/滚动不会误判
                    // （300ms/50px 偏宽松，滚动+连点曾误触——实测收紧）
                    val now = System.currentTimeMillis()
                    val x = event.getX(0)
                    val y = event.getY(0)
                    if (now - lastDownTime < 250
                        && kotlin.math.abs(x - lastDownX) < 40
                        && kotlin.math.abs(y - lastDownY) < 40
                    ) {
                        lastDownTime = 0 // 重置防三连击
                        // 双击：弹小菜单（输入框双击也走菜单——交互一致性）
                        checkBlankAndShowMenu(x, y)
                    } else {
                        lastDownTime = now
                        lastDownX = x
                        lastDownY = y
                        // 单击：完全交还 WebView（键盘自然弹，无任何拦截——
                        // 拦截/恢复机制是实机「输入法反复弹收」的根因，已删除）
                    }
                    // 单指按下：记录起始坐标（供滑动阈值判断）
                    // stopX/stopY 同时初始化（防 POINTER_UP 用旧值/0 误判）
                    startX = x
                    startY = y
                    stopX = x
                    stopY = y
                    // 长按检测：600ms 无移动且命中图片/视频 → 弹保存菜单
                    if (longPressRunnable != null) {
                        longPressHandler.removeCallbacks(longPressRunnable!!)
                    }
                    val pressX = x
                    val pressY = y
                    longPressRunnable = Runnable {
                        longPressHandler.removeCallbacks(longPressRunnable!!)
                        checkMediaLongPress(pressX, pressY)
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_MS)
                    // 单指手势：仅单指（未进入多指）时启用
                    mode = SINGLE_FINGER
                    false
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // This happens when you touch the screen with two fingers
                    mode = SWIPE
                    // 多指手势（捏合/双指滚动）：不是双击，清除双击检测状态
                    lastDownTime = 0
                    // You can also use event.getY(1) or the average of the two
                    startX = event.getX(0)
                    startY = event.getY(0)
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    // This happens when you release the second finger
                    mode = NONE
                    // release 前指针数（POINTER_UP 时 getPointerCount 已减 1）
                    val prevCount = event.pointerCount + 1
                    if (kotlin.math.abs(startX - stopX) > TRESHOLD) {
                        if (startX > stopX) {
                            if (prevCount == 3 &&
                                DataManager.getInstance().settings.isThreeFingerMultitouch
                            ) {
                                WebViewLauncher.startWebView(
                                    DataManager.getInstance().getPredecessor(webappID),
                                    this@WebViewActivity
                                )
                                finish()
                            } else if (DataManager.getInstance().settings.isTwoFingerMultitouch) {
                                safeGoForward()
                            }
                        } else {
                            if (prevCount == 3 &&
                                DataManager.getInstance().settings.isThreeFingerMultitouch
                            ) {
                                WebViewLauncher.startWebView(
                                    DataManager.getInstance().getSuccessor(webappID),
                                    this@WebViewActivity
                                )
                                finish()
                            } else if (DataManager.getInstance().settings.isTwoFingerMultitouch) {
                                safeBackPressed()
                            }
                        }
                        return@setOnTouchListener true
                    }
                    if (DataManager.getInstance().settings.isMultitouchReload &&
                        kotlin.math.abs(startY - stopY) > TRESHOLD
                    ) {
                        if (stopY > startY) {
                            currentlyReloading = true
                            safeReload()
                        }
                        return@setOnTouchListener true
                    }
                    // fall through 语义（原 Java switch 无 break 直落）：不满足阈值时
                    // 走 UP 分支的收尾——POINTER_UP 后 mode=NONE，UP 的边缘手势判断
                    // 天然不触发，等价于仅做长按取消 + 状态复位
                    false
                }

                MotionEvent.ACTION_UP -> {
                    // 抬起即取消长按：单击(快速 DOWN→UP)后 600ms 定时器不得再触发——
                    // 否则单击图片必然在抬起后误弹「保存/下载」（用户实测反馈的 bug）。
                    // 只清长按不清双击状态：双击=UP 后短窗内再 DOWN，UP 本身是双击的
                    // 正常组成部分，清掉会让双击检测永久失效（实测踩坑）
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    handleActionUp(mode, event, startX, startY, stopX, stopY)
                }

                MotionEvent.ACTION_MOVE -> {
                    // 无论单指/多指都记录当前坐标（stopX/stopY 保持最新，POINTER_UP/UP 用最新值）
                    // 单指非滑动（mode==单指）也更新：防 POINTER_UP 用旧值误判
                    stopX = event.getX(0)
                    stopY = event.getY(0)
                    // 移动超阈值（滑动/拖动滚动）：不是双击，清除双击检测状态；移动也取消长按
                    if (kotlin.math.abs(stopX - startX) > TRESHOLD
                        || kotlin.math.abs(stopY - startY) > TRESHOLD
                    ) {
                        cancelLongPressAndReset { }
                    }
                    false
                }

                MotionEvent.ACTION_SCROLL -> {
                    // 滚轮滚动：不是双击，清除双击检测状态（防止误判弹菜单）
                    lastDownTime = 0
                    false
                }

                else -> false
            }
        }
    }

    /** UP/POINTER_UP 落底共享逻辑：边缘单指滑手势 + 状态重置（原 switch 直落语义拆出） */
    private fun handleActionUp(
        mode: Int,
        event: MotionEvent,
        startX: Float,
        startY: Float,
        stopX: Float,
        stopY: Float
    ): Boolean {
        // 抬起：重置滑动状态。
        // 单指左右滑手势（竖屏单手控制前进/后退）：
        // - 右滑（stopX > startX）= 后退（回上一个页面，与返回一致）
        // - 左滑（stopX < startX）= 前进（有历史才执行）
        // 防冲突（用户反馈）：只识别**屏幕左右边缘区**（起点在左右各 20% 内）——
        // 页面中间区域（表格/轮播等可横向滚动内容）完全交还 WebView，不拦截。
        // 触发距离 150px（比 TRESHOLD 更严），要求水平位移 > 垂直位移*1.2。
        if (mode == SINGLE_FINGER && event.pointerCount == 1) {
            val dx = stopX - startX
            val dy = kotlin.math.abs(stopY - startY)
            val screenW = resources.displayMetrics.widthPixels
            val edgeZone = startX < screenW * GESTURE_EDGE_ZONE_RATIO
                || startX > screenW * (1f - GESTURE_EDGE_ZONE_RATIO)
            if (edgeZone && kotlin.math.abs(dx) > GESTURE_SWIPE_MIN_PX
                && kotlin.math.abs(dx) > dy * GESTURE_HORIZONTAL_DOMINANCE
            ) {
                if (dx > 0) {
                    // 右滑后退：有历史才退（与系统返回一致）；
                    // 无历史不动作（此前会退出应用）——给轻提示
                    if (wv != null && wv!!.canGoBack()) {
                        safeBackPressed()
                    } else {
                        NotificationUtils.showToast(
                            this@WebViewActivity,
                            getString(R.string.gesture_no_prev_page), Toast.LENGTH_SHORT
                        )
                    }
                } else if (dx < 0) {
                    // 左滑前进：有前进历史才执行；无则不动作，给轻提示
                    if (wv != null && wv!!.canGoForward()) {
                        safeGoForward()
                    } else {
                        NotificationUtils.showToast(
                            this@WebViewActivity,
                            getString(R.string.gesture_no_next_page), Toast.LENGTH_SHORT
                        )
                    }
                }
                // 关键：不消费 UP（return false）——WebView 需要完整 DOWN→MOVE→UP
                // 事件流，消费 UP 会让 WebView 触摸状态机卡在"按住"，
                // 导致后续上下滑动完全失效（用户反馈的 bug 根因）
                return false
            }
        }
        return false
    }

    /** MOVE 超阈值：取消长按 + 清双击状态（局部状态经回调复位） */
    private fun cancelLongPressAndReset(resetDoubleTap: () -> Unit) {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
        resetDoubleTap()
    }

    @SuppressLint("RequiresFeature")
    private fun setDarkModeIfNeeded() {
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val isForceDarkSupported =
                WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)
            val isForceDarkStrategySupported =
                WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)
            val isAlgorithmicDarkeningSupported =
                WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)

            if (needsForcedDarkMode) {
                wv!!.setBackgroundColor(Color.BLACK)
                wv!!.setForceDarkAllowed(true)
                delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
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
    private fun safeGoForward() {
        try {
            if (wv != null && wv!!.canGoForward()) wv!!.goForward()
        } catch (e: Exception) {
            Log.w("WebViewActivity", "safeGoForward failed", e)
        }
    }

    /** 安全后退（WebView 状态异常时回落到系统返回） */
    private fun safeBackPressed() {
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
    private fun safeReload() {
        try {
            wv?.reload()
        } catch (e: Exception) {
            Log.w("WebViewActivity", "safeReload failed", e)
        }
    }

    /** 启动加载页动物走路动画（ImageView + AnimationDrawable）+ 主题背景 */
    private fun startLoadingAnimal() {
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
    private fun stopLoadingAnimal() {
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
    private fun showWebViewMenuSheet() {
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
                wv?.settings?.textZoom = zoom.toInt()
                Unit
            },
            { zoom ->
                // 实时预览页面缩放 + 记录待保存值（zoomBy 模拟捏合）
                mMenuPageZoom = zoom.toInt()
                if (wv != null) {
                    webapp!!.pageZoom = zoom.toInt()
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
    private fun applyPageZoom() {
        if (wv == null || webapp == null) return
        val zoom = webapp!!.pageZoom
        wv!!.setInitialScale(zoom)
    }

    /** 菜单动作处理 */
    private fun handleMenuAction(action: String) {
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
        // 二级会话菜单（简约）：列表切换 + "新增"；多会话才显示"删除"
        val items = Array(count) { i ->
            "会话 " + (i + 1) + (if (i == webappTabIndex) "（当前）" else "")
        }
        val b = AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_session))
            .setMessage(getString(R.string.session_isolated_hint))
            .setItems(items) { _, which ->
                if (which != webappTabIndex) {
                    CookieSessionManager.saveSnapshot(this, webappID, webappTabIndex)
                    WebViewLauncher.startWebViewById(webappID, which, this)
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
    private fun recordCacheUsage() {
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
    private fun updateStatsCache(httpBytes: Long, storeBytes: Long) {
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
    private fun sendShortcutToPage(shortcut: String?) {
        if (wv == null || shortcut.isNullOrEmpty()) return
        // 统计：记录发送次数（面板/统计页反馈）
        StatsRecorder.recordShortcutSent(webappID, shortcut)
        // 解析组合键 → keyCode + metaState
        var ctrl = false
        var shift = false
        var alt = false
        var key = ""
        for (part in shortcut.split("+".toRegex())) {
            val p = part.trim()
            when (p) {
                "Ctrl" -> ctrl = true
                "Shift" -> shift = true
                "Alt" -> alt = true
                else -> key = p
            }
        }
        if (key.isEmpty()) return
        val keyCode = keyCodeOf(key)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return
        val metaState = (if (ctrl) KeyEvent.META_CTRL_ON else 0) or
            (if (shift) KeyEvent.META_SHIFT_ON else 0) or
            (if (alt) KeyEvent.META_ALT_ON else 0)

        // 方案一：JS 合成 KeyboardEvent（主方案——kimi code 源码确认不校验 isTrusted）
        // 带 code 字段（CodeMirror 类编辑器按 e.code 匹配）+ 聚焦输入框（target 正确）
        injectJsWithFocus(ctrl, shift, alt, key)
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
        mPopupMenu!!.menu.getItem(0).title = spanStringWebAppTitle

        for (i in 0 until mPopupMenu!!.menu.size()) {
            val item = mPopupMenu!!.menu.getItem(i)
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

    override fun onConfigurationChanged(@NonNull newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        this.setDarkModeIfNeeded()
    }

    // 渲染核心的有意实现：WebView 后退优先 + 再按退出，不走 super（避免双重处理）
    // 注：Manifest enableOnBackInvokedCallback=true 下系统手势仍会回调本方法（legacy 兼容）
    @SuppressLint("MissingSuperCall", "GestureBackNavigation")
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
    private fun isBoundShortcut(shortcut: String): Boolean {
        val w = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true)
        return w != null && w.keyShortcuts != null && w.keyShortcuts.contains(shortcut)
    }

    /** 构建组合键字符串（Ctrl+S / Ctrl+Shift+S） */
    private fun buildShortcutString(ctrl: Boolean, shift: Boolean, alt: Boolean, key: String): String {
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
            val c = ('a' + (keyCode - KeyEvent.KEYCODE_A)).toChar()
            return if (shift) c.uppercaseChar().toString() else c.toString()
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            return ('0' + (keyCode - KeyEvent.KEYCODE_0)).toChar().toString()
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

    private fun loadURL(view: WebView, url: String) {
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
                    DataManager.getInstance().saveWebAppData()
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

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        } else {
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private fun showSystemBars() {
        if (webapp!!.isShowFullscreen) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
        } else {
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        @NonNull permissions: Array<String>,
        @NonNull grantResults: IntArray
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

    private fun enablePermissionBoolOnWebApp(successCallback: PermissionGrantedCallback) {
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
        if (requestCode == Const.PERMISSION_RC_STORAGE) {
            if (dlRequest != null) {
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager?
                if (dm != null) {
                    dm.enqueue(dlRequest)
                    NotificationUtils.showInfoSnackbar(
                        this, getString(R.string.file_download), Snackbar.LENGTH_SHORT
                    )
                }
                dlRequest = null
            }
        }
    }

    private fun onPermissionsDenied(requestCode: Int, list: List<String>) {
        if (requestCode == Const.PERMISSION_RC_LOCATION) {
            this.handleGeoPermissionCallback(false)
        }
    }

    private fun handleGeoPermissionCallback(allow: Boolean) {
        if (mGeoPermissionRequestCallback != null) {
            mGeoPermissionRequestCallback!!
                .invoke(mGeoPermissionRequestOrigin, allow, false)
            mGeoPermissionRequestCallback = null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (resultCode == RESULT_CANCELED && requestCode == Const.CODE_OPEN_FILE) {
            this.filePathCallback?.onReceiveValue(null)
        } else if (resultCode == RESULT_OK && requestCode == Const.CODE_OPEN_FILE) {
            filePathCallback!!.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, intent)
            )
            filePathCallback = null
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
    private fun scheduleBlankScreenCheck() {
        blankScreenHandler.removeCallbacks(blankScreenCheck)
        if (!pageLoadFinished) {
            blankScreenHandler.postDelayed(blankScreenCheck, Const.BLANK_SCREEN_TIMEOUT_MS.toLong())
        }
    }

    private fun cancelBlankScreenCheck() {
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
    private fun loadCustomErrorPage(code: String?, desc: String?) {
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

    private fun showHttpAuthDialog(handler: HttpAuthHandler, host: String, realm: String) {
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

    private inner class CustomWebChromeClient : WebChromeClient() {
        private var mCustomView: View? = null
        private var mCustomViewCallback: CustomViewCallback? = null
        private var mOriginalOrientation = 0
        private var mOriginalSystemUiVisibility = 0

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            // 统计埋点：页面 JS 错误（未捕获异常/语法错误走 console.error 上报）
            if (consoleMessage != null
                && consoleMessage.messageLevel() ==
                ConsoleMessage.MessageLevel.ERROR
            ) {
                StatsRecorder.recordPageError(
                    webappID, ErrorType.JS.name,
                    ErrorType.JS.code,
                    consoleMessage.message() ?: "JS error"
                )
            }
            return false // 不阻断页面（仅采集）
        }

        private fun handlePermissionRequest(
            resId: String,
            currentState: Boolean,
            androidPermissions: Array<String>,
            requestCode: Int,
            permissionsToGrant: MutableList<String>,
            webkitPermission: Array<String>,
            successCallback: PermissionGrantedCallback
        ) {
            val androidPermissionsMissing = areAndroidPermissionsMissing(androidPermissions)
            if (currentState && androidPermissionsMissing) {
                // 权限审计：区分「首次请求」vs「永久拒绝」（勾选"不再询问"）
                // 全部权限都已请求过 + shouldShowRequestPermissionRationale=false → 永久拒绝，
                // 不再重复弹系统框，引导用户去系统设置手动开启
                val allRequested = androidPermissions.all { it in requestedPermissions }
                if (allRequested) {
                    var permanentlyDenied = false
                    for (perm in androidPermissions) {
                        if (ContextCompat.checkSelfPermission(
                                this@WebViewActivity, perm
                            ) != PackageManager.PERMISSION_GRANTED
                            && !ActivityCompat.shouldShowRequestPermissionRationale(
                                this@WebViewActivity, perm
                            )
                        ) {
                            permanentlyDenied = true
                            break
                        }
                    }
                    if (permanentlyDenied) {
                        handleGeoPermissionCallback(false)
                        showPermissionPermanentlyDeniedDialog(resId)
                        return
                    }
                }
                androidPermissions.forEach { requestedPermissions.add(it) }
                ActivityCompat.requestPermissions(
                    this@WebViewActivity, androidPermissions, requestCode
                )
                return
            }
            if (currentState && !androidPermissionsMissing) {
                permissionsToGrant.addAll(webkitPermission)
                handleGeoPermissionCallback(true)
                return
            }

            AlertDialog.Builder(this@WebViewActivity)
                .setTitle(getPermissionRequestStringResource("dialog_permission_", resId, "_title"))
                .setMessage(getPermissionRequestStringResource("dialog_permission_", resId, "_txt"))
                .setPositiveButton(android.R.string.yes) { _, _ ->
                    enablePermissionBoolOnWebApp(successCallback)
                    handleGeoPermissionCallback(true)
                    permissionsToGrant.addAll(webkitPermission)
                    if (androidPermissionsMissing) {
                        ActivityCompat.requestPermissions(
                            this@WebViewActivity, androidPermissions, requestCode
                        )
                    }
                }
                .setNegativeButton(android.R.string.no) { _, _ -> handleGeoPermissionCallback(false) }
                .create()
                .show()
        }

        private fun getPermissionRequestStringResource(
            prefix: String, variable: String, suffix: String
        ): String {
            return getString(
                this@WebViewActivity.resources.getIdentifier(
                    prefix + variable + suffix, "string", this@WebViewActivity.packageName
                )
            )
        }

        private fun areAndroidPermissionsMissing(androidPermissions: Array<String>): Boolean {
            for (perm in androidPermissions) {
                if (ContextCompat.checkSelfPermission(
                        this@WebViewActivity, perm
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return true
                }
            }
            return false
        }

        /**
         * 权限被永久拒绝：不再重复弹系统框，提示用户去系统设置手动开启。
         */
        private fun showPermissionPermanentlyDeniedDialog(resId: String) {
            val title = getPermissionRequestStringResource("dialog_permission_", resId, "_title")
            AlertDialog.Builder(this@WebViewActivity)
                .setTitle(title)
                .setMessage(getString(R.string.permission_permanently_denied_msg, title))
                .setPositiveButton(getString(R.string.permission_go_to_settings)) { _, _ ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + packageName)
                        )
                        startActivity(intent)
                    } catch (ignored: Exception) {
                        // 无设置页可跳时静默（不影响主功能）
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
                .show()
        }

        override fun onShowFileChooser(
            webView: WebView?,
            pFilePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            filePathCallback = pFilePathCallback
            return try {
                val intent = fileChooserParams.createIntent()
                startActivityForResult(intent, Const.CODE_OPEN_FILE)
                true
            } catch (e: Exception) {
                NotificationUtils.showInfoSnackbar(
                    this@WebViewActivity, getString(R.string.no_filemanager),
                    Snackbar.LENGTH_LONG
                )
                e.printStackTrace()
                true
            }
        }

        override fun getDefaultVideoPoster(): Bitmap {
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawARGB(0, 0, 0, 0)
            return bitmap
        }

        override fun onHideCustomView() {
            (window.decorView as FrameLayout).removeView(this.mCustomView)
            this.mCustomView = null
            window.decorView.systemUiVisibility = this.mOriginalSystemUiVisibility
            setRequestedOrientation(this.mOriginalOrientation)
            this.mCustomViewCallback!!.onCustomViewHidden()
            this.mCustomViewCallback = null
            showSystemBars()
        }

        override fun onShowCustomView(pView: View, pViewCallback: CustomViewCallback) {
            if (this.mCustomView != null) {
                onHideCustomView()
                return
            }
            this.mCustomView = pView
            this.mOriginalSystemUiVisibility = window.decorView.systemUiVisibility
            this.mOriginalOrientation = requestedOrientation
            this.mCustomViewCallback = pViewCallback
            (window.decorView as FrameLayout)
                .addView(this.mCustomView, FrameLayout.LayoutParams(-1, -1))
            hideSystemBars()
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val permissionsToGrant = ArrayList<String>()

            val containsDrmRequest = request.resources
                .contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
            val containsCameraRequest = request.resources
                .contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            val containsMicrophoneRequest = request.resources
                .contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

            if (containsDrmRequest) {
                this.handlePermissionRequest(
                    "drm", webapp!!.isDrmAllowed, arrayOf(), -1, permissionsToGrant,
                    arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
                ) { webapp!!.isDrmAllowed = true }
            }
            if (containsCameraRequest) {
                this.handlePermissionRequest(
                    "camera", webapp!!.isCameraPermission,
                    arrayOf(Manifest.permission.CAMERA), Const.PERMISSION_CAMERA,
                    permissionsToGrant, arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                ) { webapp!!.isCameraPermission = true }
            }

            if (containsMicrophoneRequest) {
                this.handlePermissionRequest(
                    "microphone", webapp!!.isMicrophonePermission,
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.MODIFY_AUDIO_SETTINGS
                    ),
                    Const.PERMISSION_AUDIO, permissionsToGrant,
                    arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                ) { webapp!!.isMicrophonePermission = true }
            }

            request.grant(permissionsToGrant.toTypedArray())
        }

        override fun onProgressChanged(view: WebView?, progress: Int) {
            // 白屏检测：记录进度推进（用于 20s 无推进超时判定）
            if (progress > lastProgress) {
                lastProgress = progress
                lastProgressTime = System.currentTimeMillis()
                // 进度有推进 → 重新计时（每次推进重置 20s）
                scheduleBlankScreenCheck()
            }

            // 加载动画：独立于进度条显示开关——页面加载期短暂展示（站点 loader 接管前撤）：
            // - progress < 100（整段加载）
            // - 距加载开始 < ANIMAL_MAX_SHOW_MS（避免长期盖住带自带 loader 的慢站）
            // 注意：放进度条判断外，用户未开启进度条时动画依然可见（此前默认关闭进度条导致动画不显示）
            if (progress < 100 && loadingAnimal != null) {
                // 距加载开始时间（首次 onPageStarted 前 pageLoadStartTime2=0 → 视为 0 刚从窗口内开始）
                val elapsed = if (pageLoadStartTime2 == 0L) 0L
                else System.currentTimeMillis() - pageLoadStartTime2
                if (elapsed < animalMaxShowMs) {
                    startLoadingAnimal()
                } else {
                    stopLoadingAnimal()
                }
            } else {
                stopLoadingAnimal()
            }

            if (DataManager.getInstance().settings.isShowProgressbar || currentlyReloading) {
                if (progressBar!!.visibility == ProgressBar.GONE && progress < 100) {
                    progressBar!!.visibility = ProgressBar.VISIBLE
                }

                // 平滑过渡（150ms），避免进度跳变
                progressBar!!.setProgress(progress, true)

                if (progress == 100) {
                    progressBar!!.visibility = ProgressBar.GONE
                    currentlyReloading = false
                    stopLoadingAnimal()
                }
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            mGeoPermissionRequestCallback = callback
            mGeoPermissionRequestOrigin = origin
            this.handlePermissionRequest(
                "location", webapp!!.isAllowLocationAccess,
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                Const.PERMISSION_RC_LOCATION, ArrayList(),
                arrayOf()
            ) { webapp!!.isAllowLocationAccess = true }
        }
    }

    private inner class CustomBrowser : WebViewClient() {

        override fun onReceivedHttpAuthRequest(
            view: WebView,
            handler: HttpAuthHandler,
            host: String,
            realm: String
        ) {
            showHttpAuthDialog(handler, host, realm)
        }

        override fun onPageFinished(view: WebView, url: String) {
            // 加载完成：取消白屏检测（避免误判）
            pageLoadFinished = true
            cancelBlankScreenCheck()
            // 页面加载完成：隐藏加载页动物动画
            stopLoadingAnimal()
            // 统计埋点：主体加载耗时（started 到 finished）
            if (pageLoadStartTime > 0) {
                StatsRecorder.recordPageLoaded(
                    webappID, System.currentTimeMillis() - pageLoadStartTime
                )
                pageLoadStartTime = 0
            }
            // 统计埋点：缓存占用（cacheDir + WebStorage，异步不阻塞）
            recordCacheUsage()
            if (url == "about:blank") {
                loadCustomErrorPage("blank", "")
            }
            wv!!.evaluateJavascript(
                "document.addEventListener(\"visibilitychange\"," +
                    "function (event) {event.stopImmediatePropagation();},true);", null
            )
            // 移除图片 title/alt 属性（防止 WebView 查看图片时显示图片名浮层遮挡）。
            // MutationObserver 持续清除（SPA 动态图片）；busy 标志防递归
            // （clean 修改属性会再触发 observer）
            wv!!.evaluateJavascript(
                "(function(){"
                    + "var busy=false;"
                    + "var clean=function(){"
                    + "if(busy)return;busy=true;"
                    + "document.querySelectorAll('img').forEach(function(i){i.removeAttribute('title');i.removeAttribute('alt');});"
                    + "busy=false;"
                    + "};"
                    + "clean();"
                    + "var mo=new MutationObserver(function(){clean();});"
                    + "mo.observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['title','alt']});"
                    + "})()",
                null
            )
            // 页面缩放：zoomBy 模拟捏合（对移动版自适应页面可靠）
            applyPageZoom()
            super.onPageFinished(view, url)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            // 新页面加载：重置白屏检测（进度从 0 重新计时）
            pageLoadFinished = false
            lastProgress = 0
            lastProgressTime = System.currentTimeMillis()
            // 加载动画计时起点（短暂显示窗口判定）
            pageLoadStartTime2 = System.currentTimeMillis()
            scheduleBlankScreenCheck()
            // 统计埋点：记录加载开始时间
            pageLoadStartTime = System.currentTimeMillis()
            super.onPageStarted(view, url, favicon)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            // 仅主框架错误处理（子资源错误不统计防噪音）
            if (request.isForMainFrame) {
                val code = error?.errorCode?.toString() ?: "unknown"
                val desc = error?.description?.toString() ?: ""
                // 统计埋点：记录页面错误
                StatsRecorder.recordPageError(webappID, ErrorType.NETWORK.name, code, desc)
                // 记录重试目标 + 加载自定义错误页（不显示系统默认白屏）
                retryUrl = request.url?.toString() ?: urlOnFirstPageload
                loadCustomErrorPage(code, desc)
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            // 统计埋点：HTTP 状态码错误（主框架）
            if (request.isForMainFrame) {
                StatsRecorder.recordPageError(
                    webappID, ErrorType.HTTP.name,
                    errorResponse.statusCode.toString(),
                    "HTTP error"
                )
            }
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail
        ): Boolean {
            // 渲染进程崩溃/OOM：避免整个应用崩溃，提示用户并关闭页面
            StatsRecorder.recordPageError(
                webappID, ErrorType.RENDER.name,
                ErrorType.RENDER.code, "Render process gone"
            )
            runOnUiThread {
                NotificationUtils.showInfoSnackbar(
                    this@WebViewActivity,
                    getString(R.string.render_process_gone),
                    Snackbar.LENGTH_LONG
                )
                finish()
            }
            return true // 已处理，阻止系统终止应用
        }

        @Nullable
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            if (urlOnFirstPageload == "") urlOnFirstPageload = request.url.toString()

            if (webapp!!.isBlockThirdPartyRequests) {
                val uri = request.url
                val webappUri = Uri.parse(webapp!!.baseUrl)

                if (uri.host != null) {
                    if (!uri.host!!.endsWith(webappUri.host!!)) {
                        return WebResourceResponse("text/plain", "utf-8", null)
                    }
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError
        ) {
            // This option is hidden in "expert settings"
            if (webapp!!.isIgnoreSslErrors) {
                handler.proceed()
                return
            }

            // 统计埋点：SSL 错误
            StatsRecorder.recordPageError(
                webappID, ErrorType.SSL.name,
                error.primaryError.toString(), "SSL error"
            )

            val builder = AlertDialog.Builder(this@WebViewActivity)

            var message = getString(R.string.ssl_error_msg_line1) + " "
            when (error.primaryError) {
                SslError.SSL_UNTRUSTED ->
                    message += getString(R.string.ssl_error_unknown_authority) + "\n"
                SslError.SSL_EXPIRED ->
                    message += getString(R.string.ssl_error_expired) + "\n"
                SslError.SSL_IDMISMATCH ->
                    message += getString(R.string.ssl_error_id_mismatch) + "\n"
                SslError.SSL_NOTYETVALID ->
                    message += getString(R.string.ssl_error_notyetvalid) + "\n"
            }
            message += getString(R.string.ssl_error_msg_line2) + "\n"

            builder.setTitle(getString(R.string.ssl_error_title))
            builder.setMessage(message)
            builder.setIcon(android.R.drawable.ic_dialog_alert)
            builder.setPositiveButton(getString(android.R.string.cancel)) { _, _ -> handler.cancel() }
            builder.setNegativeButton(getString(R.string.load_anyway)) { _, _ -> handler.proceed() }
            val dialog = builder.create()
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(
                    ContextCompat.getColor(
                        this@WebViewActivity, android.R.color.holo_red_dark
                    )
                )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setTextColor(
                    ContextCompat.getColor(
                        this@WebViewActivity, android.R.color.holo_green_dark
                    )
                )
        }

        override fun onLoadResource(view: WebView, url: String) {
            super.onLoadResource(view, url)

            val webapp = DataManager.getInstance().getWebApp(webappID)
            if (webapp != null && webapp.isRequestDesktop) {
                view.evaluateJavascript(
                    """
                    var needsForcedWidth = document.documentElement.clientWidth < 1200;
                    if(needsForcedWidth) {
                      document.querySelector('meta[name="viewport"]').setAttribute('content', 'width=1200px, initial-scale=' + (document.documentElement.clientWidth / 1200));
                    }
                    """.trimIndent(),
                    null
                )
            }
            view.evaluateJavascript(
                "document.addEventListener(    \"visibilitychange\"    , (event) => {         event.stopImmediatePropagation();    }  );",
                null
            )
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            runOnUiThread { setDarkModeIfNeeded() }
            val url = request.url.toString()
            val webapp = DataManager.getInstance().getWebApp(webappID)

            if (url.startsWith("tel:")) {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse(url))
                startActivity(intent)
                return true
            }
            if (url.startsWith("mailto:")) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
                return true
            }

            // 错误页重试：重新加载失败时的原地址（恢复用户 textZoom）
            if (url.startsWith("webnative://retry")) {
                if (retryUrl.isNotEmpty() && wv != null) {
                    wv!!.settings.textZoom = webapp!!.textZoom
                    wv!!.loadUrl(retryUrl)
                }
                return true
            }

            // 非 http/https 协议（tbopen://、weixin:// 等 App 唤起协议）：
            // 交给系统处理（可唤起对应 App），避免 ERR_UNKNOWN_URL_SCHEME 错误页
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    // 无对应 App：留在当前页不崩溃
                }
                return true
            }

            if (webapp == null) {
                return false
            }

            if (webapp.isOpenUrlExternal) {
                val baseUrl = webapp.baseUrl
                val uri = Uri.parse(baseUrl)
                val host = uri.host
                if (!url.contains(host!!)) {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    return true
                }
            }
            loadURL(view, url)
            return true
        }
    }
}
