package com.cylonid.nativealpha

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.cylonid.nativealpha.helper.WebViewDarkModeController
import com.cylonid.nativealpha.helper.WebViewGestureHelper
import com.cylonid.nativealpha.helper.WebViewLifecycleDelegate
import com.cylonid.nativealpha.helper.WebViewMenuHelper
import com.cylonid.nativealpha.util.FileChooserDelegate
import com.cylonid.nativealpha.helper.WebViewPageChrome
import com.cylonid.nativealpha.helper.WebViewShortcutInjectHelper
import com.cylonid.nativealpha.helper.WebViewTouchHandler
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.StatsRecorder
import com.cylonid.nativealpha.util.SystemBars
import com.cylonid.nativealpha.util.ThemeUtils
import com.cylonid.nativealpha.util.WebviewRecycleRegistry
import com.cylonid.nativealpha.util.WebViewLauncher

/**
 * 渲染核心 Activity（集成枢纽）：持有 WebView 视图字段与系统回调入口，
 * 实现体委托 helper 包各处理器——装配/生命周期（WebViewLifecycleDelegate）、
 * 深色/系统栏（WebViewDarkModeController）、页面加载 UI（WebViewPageChrome）、
 * 触摸/快捷键/菜单/权限（WebViewTouchHandler 等既有 helper）。
 *
 * 契约留守本类：ChromeClient/BrowserClient 直读的字段（wv/progressBar 等）、
 * parseShortcut（CoreLogicTest 单测契约）、权限回写三件套
 * （WebViewChromeClient 直接引用）、生命周期与返回键 override 入口。
 */
class WebViewActivity : AppCompatActivity(), WebViewSiteContext, SystemBars.SelfManagedInsets {

    companion object {
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

    // ===== WebViewClient/ChromeClient 直读字段（契约留守，勿迁） =====

    var webappID = -1
    var webappTabIndex = 0
    internal var wv: WebView? = null
    internal var progressBar: ProgressBar? = null
    internal var loadingAnimal: ImageView? = null
    internal var loadingBg: View? = null

    /** 加载页动物动画最长显示时间（ms）：站点自带 loader/慢加载站点不长期盖住 */
    internal val animalMaxShowMs = 3000L

    /** 动画显示起止计时（onProgressChanged 里判定短暂显示窗口用） */
    internal var pageLoadStartTime2 = 0L
    internal var currentlyReloading = true
    // 白屏检测进度值（onProgressChanged 更新；计时与判定在 WebViewPageChrome）
    internal var lastProgress = 0
    internal var lastProgressTime = 0L

    override var webapp: WebApp? = null

    // ===== WebViewSiteContext 实现（QA 基类抽取：站点行为经接口访问宿主） =====
    override val siteContext: Context get() = this
    override val webappId: Int get() = webappID

    override var urlOnFirstPageload: String = ""

    // 错误页重试目标（onReceivedError 主框架失败时记录，webnative://retry 用它重新加载）
    override var retryUrl: String = ""

    // 统计埋点：页面加载开始时间（onPageStarted 到 onPageFinished 计算耗时）
    override var pageLoadStartTime: Long = 0L

    /** 文件选择器统一委托（registerForActivityResult 必须在 Activity 构造期完成——不能迁入 helper）。
     * 历史遗留的 fileChooserLauncher/filePathCallback 已删除：onShowFileChooser
     * 自 v2.3.7 起统一走 FileChooserDelegate，二者零消费 */
    internal val fileChooserDelegate = FileChooserDelegate(this)

    // ===== 处理器装配（构造注入；delegate 依赖 pageChrome/darkMode，须在其后声明） =====
    internal val touchHandler = WebViewTouchHandler(this)
    internal val shortcutHelper = WebViewShortcutInjectHelper(this)
    internal val menuHelper = WebViewMenuHelper(this)
    internal val pageChrome = WebViewPageChrome(this)
    internal val darkMode = WebViewDarkModeController(this)
    internal val lifecycleDelegate = WebViewLifecycleDelegate(this)

    /** 委托：WebViewTouchHandler 双击菜单调用点保持零改动 */
    internal fun showWebViewMenuSheet() = menuHelper.showWebViewMenuSheet()

    /** 委托：页面加载完成回调的缩放生效点保持零改动 */
    override fun applyPageZoom() = menuHelper.applyPageZoom()

    /** 委托：onPageFinished 缓存统计入口保持零改动 */
    override fun recordCacheUsage() = menuHelper.recordCacheUsage()

    /** 菜单「后退」入口：onBackPressed 为 protected，helper 经此转发（语义不变） */
    internal fun triggerBack() = onBackPressed()

    // ===== WebViewSiteContext 加载生命周期钩子（实现体在 WebViewPageChrome） =====

    override fun onPageLoadStarted() = pageChrome.onPageLoadStarted()

    override fun onPageLoadFinished() = pageChrome.onPageLoadFinished()

    override fun showCustomErrorPage(code: String?, desc: String?) {
        pageChrome.loadCustomErrorPage(code, desc)
    }

    override fun onHttpAuthRequested(handler: HttpAuthHandler, authHost: String, realm: String) {
        pageChrome.showHttpAuthDialog(handler, authHost, realm)
    }

    override fun refreshDarkModeOnMainThread() {
        runOnUiThread { darkMode.setDarkModeIfNeeded() }
    }

    override fun loadSiteUrl(view: WebView, url: String) {
        pageChrome.loadURL(view, url)
    }

    override fun recordPageLoadDuration(durationMs: Long) {
        StatsRecorder.recordPageLoaded(webappID, durationMs)
    }

    override fun recordPageError(errorType: String, code: String, desc: String) {
        StatsRecorder.recordPageError(webappID, errorType, code, desc)
    }

    override fun startReconnectWatch() = pageChrome.startReconnectWatch()

    override fun stopReconnectWatch() = pageChrome.stopReconnectWatch()

    // ===== 页面加载 UI 桥（WebViewChromeClient/WebViewTouchHandler 直接调用） =====

    internal fun scheduleBlankScreenCheck() = pageChrome.scheduleBlankScreenCheck()

    internal fun startLoadingAnimal() = pageChrome.startLoadingAnimal()

    internal fun stopLoadingAnimal() = pageChrome.stopLoadingAnimal()

    internal fun safeGoForward() = pageChrome.safeGoForward()

    internal fun safeBackPressed() = pageChrome.safeBackPressed()

    internal fun safeReload() = pageChrome.safeReload()

    // ===== 系统栏开关（WebViewChromeClient 全屏视频进出直接调用） =====

    internal fun hideSystemBars() = darkMode.hideSystemBars()

    internal fun showSystemBars() = darkMode.showSystemBars()

    /** 后台分级回收入口（WebviewRecycleRegistry 调用，实现体在 delegate） */
    internal fun recycleWebView() = lifecycleDelegate.recycleWebView()

    // ===== 生命周期（实现体在 WebViewLifecycleDelegate） =====

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题前置（规范 R「Activity 规范」）：App.onCreate 已全局 applyUiMode，
        // 此处再应用保证单测/多进程路径下主题一致；setTheme 必须先于 super
        // （super 里 AppCompat 锁定主题后不可换）
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)
        lifecycleDelegate.registerBackCallback()
        lifecycleDelegate.handleIntent(intent)
    }

    /**
     * 复用实例时（documentLaunchMode=intoExisting 或任务栈复用）：
     * 更新 webappID 并重新加载对应 WebApp——防止打开新应用时仍显示旧错误页。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleDelegate.handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        lifecycleDelegate.onHostStart()
    }

    override fun onStop() {
        super.onStop()
        lifecycleDelegate.onHostStop()
    }

    override fun onResume() {
        super.onResume()
        lifecycleDelegate.onHostResume()
    }

    override fun onPause() {
        super.onPause()
        lifecycleDelegate.onHostPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        lifecycleDelegate.onHostTrimMemory(level)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        darkMode.setDarkModeIfNeeded()
    }

    override fun onDestroy() {
        lifecycleDelegate.onHostDestroy()
        super.onDestroy()
    }

    // 渲染核心的有意实现：WebView 后退优先 + 再按退出，不走 super（避免双重处理）
    // 注：Manifest enableOnBackInvokedCallback=true 下系统手势仍会回调本方法（legacy 兼容）
    @SuppressLint("MissingSuperCall", "GestureBackNavigation")
    @Suppress("OVERRIDE_DEPRECATION") // enableOnBackInvokedCallback=true 仍回调本方法（legacy 兼容路径）
    override fun onBackPressed() {
        lifecycleDelegate.handleBackPress()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 组合快捷键：已绑定组合键拦截发送（不触发浏览器默认），管理在设置页点选录入
        if (shortcutHelper.tryInterceptShortcutKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // ===== 权限回写三件套（WebViewChromeClient 直接引用，契约留守） =====

    /**
     * Android 运行时权限异步请求通道（WebPermissionCoordinator 注入点）：
     * launcher 单回调槽——权限请求天然低频且不并发， granted 全授予判定。
     */
    private var runtimePermissionCallback: ((granted: Boolean) -> Unit)? = null
    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        runtimePermissionCallback?.invoke(grants.isNotEmpty() && grants.all { it.value })
        runtimePermissionCallback = null
    }

    internal fun requestRuntimePermissions(permissions: List<String>, onResult: (granted: Boolean) -> Unit) {
        runtimePermissionCallback = onResult
        runtimePermissionLauncher.launch(permissions.toTypedArray())
    }
}
