@file:Suppress("DEPRECATION")

package com.cylonid.nativealpha.helper

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.AnimationDrawable
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.view.LayoutInflater
import android.view.View
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.WebViewActivity
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.LoadFailureClassifier
import com.cylonid.nativealpha.util.LocaleUtils
import com.cylonid.nativealpha.util.NotificationUtils
import com.cylonid.nativealpha.util.SiteReconnectSupervisor
import com.cylonid.nativealpha.util.Utility
import com.cylonid.nativealpha.webevent.EventRuleStore
import com.cylonid.nativealpha.webevent.JsHookScript
import com.cylonid.nativealpha.webevent.WebeventRuntime
import com.google.android.material.snackbar.Snackbar
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections

/**
 * 页面加载 UI 处理器（重构刀 2，自 WebViewActivity 逐行迁移，零语义变更）。
 *
 * 职责：加载动物动画、白屏检测、自定义错误页、HTTP 认证对话框、
 * 安全导航（前进/后退/刷新兜底）、站内加载（HTTP 明文确认 + 自定义请求头）、
 * 下载监听装配、断线自动恢复监视。
 *
 * 设计约束：持有 Activity 实例引用（构造注入，非静态——防泄漏）；
 * 方法体与原实现逐行对应，行为差异零容忍。
 */
class WebViewPageChrome(private val activity: WebViewActivity) {

    /** 上次主帧是否失败（错误页 finished 与真成功的区分依据） */
    private var lastMainFrameFailed = false

    /** 页面加载完成标记（白屏检测调度窗口判定） */
    private var pageLoadFinished = false

    private val blankScreenHandler = Handler()

    /** 断线自动恢复监视器（加载失败后探测站点可达性，恢复即自动重载） */
    private val reconnectSupervisor by lazy {
        SiteReconnectSupervisor(activity.applicationContext, activity.lifecycleScope)
    }

    /** 自定义请求头（initCustomHeaders 构建，loadURL 消费） */
    private var customHeaders: Map<String, String>? = null

    // ===== 页面加载生命周期（WebViewSiteContext 桥接的实现体） =====

    fun onPageLoadStarted() {
        // 新页面加载：重置白屏检测（进度从 0 重新计时）
        pageLoadFinished = false
        // 主帧失败标记清除（新导航开始——错误页 finished 不再误判成功）
        lastMainFrameFailed = false
        activity.lastProgress = 0
        activity.lastProgressTime = System.currentTimeMillis()
        // 加载动画计时起点（短暂显示窗口判定）
        activity.pageLoadStartTime2 = System.currentTimeMillis()
        scheduleBlankScreenCheck()
        // 网页事件 hook 注入（P5：仅配规则站，幂等脚本；未配站零开销）
        activity.wv?.let { webview ->
            WebeventRuntime.hookScriptFor(activity.webappID)?.let { script ->
                webview.evaluateJavascript(script, null)
            }
        }
    }

    fun onPageLoadFinished() {
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
            EventRuleStore.hasActiveRules(activity.webappID)
        ) {
            activity.wv?.evaluateJavascript(
                "String(window." + JsHookScript.IDEMPOTENCY_FLAG + " === true)"
            ) { result ->
                WebeventRuntime.onHookProbe(
                    activity.webappID, result?.contains("true") == true
                )
            }
        }
    }

    // ===== 断线自动恢复 =====

    fun startReconnectWatch() {
        val baseUrl = DataManager.getInstance().getWebApp(activity.webappID)?.baseUrl ?: return
        reconnectSupervisor.start(baseUrl) { _ ->
            activity.runOnUiThread {
                val target = activity.retryUrl.ifBlank { baseUrl }
                val wv = activity.wv ?: return@runOnUiThread
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                loadURL(wv, target)
            }
        }
    }

    fun stopReconnectWatch() {
        reconnectSupervisor.stop()
    }

    // ===== 安全导航（内核态异常兜底） =====

    /** 安全前进（WebView 状态异常时不崩溃——手势误触/内核态异常兜底） */
    fun safeGoForward() {
        try {
            val wv = activity.wv
            if (wv != null && wv.canGoForward()) wv.goForward()
        } catch (e: Exception) {
            android.util.Log.w("WebViewActivity", "safeGoForward failed", e)
        }
    }

    /** 安全后退（WebView 状态异常时回落到系统返回） */
    fun safeBackPressed() {
        try {
            val wv = activity.wv
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                activity.onBackPressed()
            }
        } catch (e: Exception) {
            android.util.Log.w("WebViewActivity", "safeBackPressed failed", e)
        }
    }

    /** 安全刷新（reload 异常兜底） */
    fun safeReload() {
        try {
            activity.wv?.reload()
        } catch (e: Exception) {
            android.util.Log.w("WebViewActivity", "safeReload failed", e)
        }
    }

    // ===== 加载页动物动画 =====

    /** 启动加载页动物走路动画（ImageView + AnimationDrawable）+ 主题背景 */
    fun startLoadingAnimal() {
        try {
            val loadingAnimal = activity.loadingAnimal ?: return
            // 主题背景同步显示：加载期 WebView 内容未渲染，铺主题色防深色白屏
            val loadingBg = activity.loadingBg
            if (loadingBg != null && loadingBg.visibility != View.VISIBLE) {
                loadingBg.visibility = View.VISIBLE
            }
            if (loadingAnimal.visibility != View.VISIBLE) {
                loadingAnimal.visibility = View.VISIBLE
            }
            val anim = loadingAnimal.drawable as? AnimationDrawable
            if (anim != null && !anim.isRunning) {
                anim.start()
            }
        } catch (ignored: Exception) {
            // 动画启动失败不影响主功能
        }
    }

    /** 停止并隐藏加载页动物动画 + 主题背景 */
    fun stopLoadingAnimal() {
        try {
            val loadingBg = activity.loadingBg
            if (loadingBg != null && loadingBg.visibility != View.GONE) {
                loadingBg.visibility = View.GONE
            }
            val loadingAnimal = activity.loadingAnimal ?: return
            val anim = loadingAnimal.drawable as? AnimationDrawable
            if (anim != null && anim.isRunning) {
                anim.stop()
            }
            loadingAnimal.visibility = View.GONE
        } catch (ignored: Exception) {
        }
    }

    // ===== 白屏检测 =====

    /**
     * 白屏检测：进度在 20s 内无推进 → 判定加载卡死，加载错误页并提示重试。
     * 只在新页面加载开始后计时，进度推进即重置；加载完成即取消。
     * AI 流式页进度持续推进（onProgressChanged 持续回调），不会误判。
     */
    fun scheduleBlankScreenCheck() {
        blankScreenHandler.removeCallbacks(blankScreenCheck)
        if (!pageLoadFinished) {
            blankScreenHandler.postDelayed(blankScreenCheck, Const.BLANK_SCREEN_TIMEOUT_MS.toLong())
        }
    }

    fun cancelBlankScreenCheck() {
        blankScreenHandler.removeCallbacks(blankScreenCheck)
    }

    private val blankScreenCheck = Runnable { handleBlankScreen() }

    private fun handleBlankScreen() {
        if (pageLoadFinished || activity.wv == null) return
        val idle = System.currentTimeMillis() - activity.lastProgressTime
        if (idle >= Const.BLANK_SCREEN_TIMEOUT_MS && activity.lastProgress < 100) {
            // 加载卡死：加载本地错误页（带重试），避免白屏挂起
            activity.runOnUiThread {
                NotificationUtils.showInfoSnackbar(
                    activity,
                    activity.getString(R.string.blank_screen_detected),
                    Snackbar.LENGTH_LONG
                )
                // 加载中断：重置计时起点，避免错误页误计为页面加载耗时
                activity.pageLoadStartTime = 0
                activity.wv!!.stopLoading()
                loadCustomErrorPage("timeout", activity.getString(R.string.blank_screen_detected))
            }
        }
    }

    // ===== 自定义错误页 =====

    /**
     * 加载自定义错误页（M3 靛蓝统一风格，替代系统默认白屏）。
     * 带错误码/描述参数（query 传入，页面显示开发者向信息）。
     * 语言：跟随 LocaleUtils（zh/en）。
     * 字体/页面缩放：跟随当前生效配置（与页面同一套，不再有独立缩放）。
     */
    fun loadCustomErrorPage(code: String?, desc: String?) {
        val wv = activity.wv ?: return
        // 主帧失败标记：错误页自身也会回调 onPageFinished——finished 时
        // 据此区分「真成功」与「失败页完成」，避免误停断线监视
        lastMainFrameFailed = true
        try {
            // 缩放跟随生效配置（原固定 130 独立配置已废弃）
            val webapp = activity.webapp
            if (webapp != null) {
                wv.settings.textZoom = webapp.textZoom
                activity.applyPageZoom()
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
            val encodedReason = URLEncoder.encode(activity.getString(reasonRes), "UTF-8")
            // URL 编码 desc（含空格/特殊字符安全）
            val encodedDesc = URLEncoder.encode(safeDesc, "UTF-8")
            wv.loadUrl(
                "file:///android_asset/errorSite/error_" + lang
                    + ".html?code=" + safeCode + "&desc=" + encodedDesc
                    + "&reason=" + encodedReason
            )
        } catch (ignored: Exception) {
            // 错误页加载失败静默（保持现状）
        }
    }

    // ===== HTTP Basic 认证对话框 =====

    fun showHttpAuthDialog(handler: HttpAuthHandler, host: String, realm: String) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_http_auth, null)
        AlertDialog.Builder(activity)
            .setView(view)
            .setTitle(activity.getString(R.string.http_auth_title))
            .setMessage(activity.getString(R.string.enter_http_auth_credentials, realm, host))
            .setPositiveButton(activity.getString(R.string.ok)) { _, _ ->
                val username = view.findViewById<EditText>(R.id.username)
                    .text.toString()
                val password = view.findViewById<EditText>(R.id.password)
                    .text.toString()
                handler.proceed(username, password)
            }
            .setNegativeButton(activity.getString(R.string.cancel)) { _, _ -> handler.cancel() }
            .show()
    }

    // ===== 站内加载（自定义请求头 + HTTP 明文确认） =====

    fun initCustomHeaders(saveData: Boolean): Map<String, String> {
        val extraHeaders = HashMap<String, String>()
        extraHeaders["DNT"] = "1"
        extraHeaders["X-REQUESTED-WITH"] = ""
        extraHeaders["Accept-Language"] = LocaleUtils.acceptLanguage
        if (saveData) {
            extraHeaders["Save-Data"] = "on"
        }
        customHeaders = Collections.unmodifiableMap(extraHeaders)
        return customHeaders!!
    }

    fun loadURL(view: WebView, url: String) {
        val webApp = DataManager.getInstance().getWebApp(activity.webappID)
        if (webApp == null) {
            activity.finish()
            return
        }
        if (url.contains("http://") && !webApp.isAllowHttp) {
            val builder = AlertDialog.Builder(activity)

            builder.setTitle(activity.getString(R.string.no_https_dialog_title))
            builder.setMessage(activity.getString(R.string.no_https_dialog_msg))
            builder.setIcon(android.R.drawable.ic_dialog_alert)
            builder.setPositiveButton(activity.getString(R.string.no_https_dialog_accept)) { _, _ ->
                // 必须写回列表内的存储实例（ignoreOverride=true 取活对象）——
                // 上方 webApp 在 override=false 时是合并副本，改副本保存会静默丢失
                val stored =
                    DataManager.getInstance().getWebAppIgnoringGlobalOverride(activity.webappID, true)
                if (stored != null) {
                    stored.isAllowHttp = true
                    stored.isOverrideGlobalSettings = true
                    // 统一写收口：发射 flow（设置页返回后列表/设置即时反映）+ 触发持久化
                    DataManager.getInstance().commitChanges()
                }
                view.loadUrl(url, customHeaders!!)
            }
            builder.setNegativeButton(activity.getString(android.R.string.cancel)) { _, _ ->
                activity.finish()
            }
            val dialog = builder.create()
            dialog.show()
        } else {
            view.loadUrl(url, customHeaders!!)
        }
    }

    // ===== 下载监听 =====

    /** 装配下载监听（pdf 外跳、blob 解码、DownloadManager 入队）——原 setDownloadListener 逐行迁移 */
    fun installDownloadListener(webview: WebView) {
        webview.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            if (mimeType == "application/pdf") {
                val i = Intent(Intent.ACTION_VIEW)
                i.data = Uri.parse(dlUrl)
                activity.startActivity(i)
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
                            activity, activity.getString(R.string.file_download),
                            Snackbar.LENGTH_SHORT
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
                        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE)
                            as DownloadManager?
                        if (dm != null) {
                            dm.enqueue(request)
                            NotificationUtils.showInfoSnackbar(
                                activity, activity.getString(R.string.file_download),
                                Snackbar.LENGTH_SHORT
                            )
                        }
                    }
                }
            }
        }
    }
}
