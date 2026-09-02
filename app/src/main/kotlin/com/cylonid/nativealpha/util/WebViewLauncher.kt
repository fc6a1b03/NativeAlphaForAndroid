package com.cylonid.nativealpha.util

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import com.cylonid.nativealpha.WebViewActivity
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp
import com.google.android.material.snackbar.Snackbar
import java.lang.NullPointerException

object WebViewLauncher {
    @JvmStatic
    fun startWebView(webapp: WebApp, c: Context) {
        startWebView(webapp, c, webapp.ID, 0)
    }

    @JvmStatic
    fun startWebView(webapp: WebApp, c: Context, webappId: Int, tabIndex: Int) {
        try {
            c.startActivity(createWebViewIntent(webapp, c, webappId, tabIndex))
        } catch (e: NullPointerException) {
            NotificationUtils.showInfoSnackbar(
                c as AppCompatActivity,
                c.getString(R.string.webview_activity_launch_failed),
                Snackbar.LENGTH_LONG
            )
            e.printStackTrace()
        }
    }

    @JvmStatic
    fun createWebViewIntent(webapp: WebApp, c: Context?): Intent? {
        return createWebViewIntent(webapp, c, webapp.ID, 0)
    }

    /**
     * 多标签会话：webappId + tabIndex 定位会话（小菜单新增/切换/删除会话用）。
     *
     * **data=站点地址是任务身份**（C-多开）：WebViewActivity 为
     * documentLaunchMode=intoExisting——文档任务按启动 intent 区分，
     * 不带 data 时 A/B 启动 intent 完全同构，B 会顶掉 A 的任务卡
     * （「只剩一个」的根因）。data=规范化站点地址后：不同站点各自
     * 独立任务卡（后台多开并存），同站点复用同一卡（onNewIntent 切换）。
     */
    @JvmStatic
    fun createWebViewIntent(webapp: WebApp, c: Context?, webappId: Int, tabIndex: Int): Intent? {
        val intent = Intent(c, WebViewActivity::class.java)
        intent.putExtra(Const.INTENT_WEBAPPID, webappId)
        intent.putExtra(Const.INTENT_TAB_INDEX, tabIndex)
        intent.data = taskIdentityUri(webapp.baseUrl, webappId)
        intent.action = Intent.ACTION_VIEW
        return intent
    }

    /** 通过 webappId + tabIndex 重新打开会话（切换标签用；复用单实例 WebViewActivity） */
    @JvmStatic
    fun startWebViewById(webappId: Int, tabIndex: Int, c: Context) {
        try {
            val intent = Intent(c, WebViewActivity::class.java)
            intent.putExtra(Const.INTENT_WEBAPPID, webappId)
            intent.putExtra(Const.INTENT_TAB_INDEX, tabIndex)
            intent.action = Intent.ACTION_VIEW
            // 任务身份与主启动链路一致（data=站点地址）
            DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappId, true)
                ?.let { intent.data = taskIdentityUri(it.baseUrl, it.ID) }
            // 单实例复用：CLEAR_TOP 复用现有 WebViewActivity（onNewIntent 重载），
            // 避免 finish+新建时序问题（实测 start 后 finish 会干掉新实例）
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            c.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 扫码临时浏览（C-扫码）：携带原始 URL 直接进页面，不注册站点
     * （WebViewActivity 按 INTENT_RAW_URL 构建负 ID 瞬态站点，统计零写入）。
     * URL 已由 ScanRouting 按 http/https 白名单校验。
     * data=该 URL：每次扫码独立任务卡（互不顶替）。
     */
    @JvmStatic
    fun startRawUrl(url: String, c: Context) {
        try {
            val intent = Intent(c, WebViewActivity::class.java)
            intent.putExtra(Const.INTENT_RAW_URL, url)
            intent.data = taskIdentityUri(url, -1)
            intent.action = Intent.ACTION_VIEW
            c.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** 任务身份标识（data URI）：站点地址规范化，负 ID 瞬态页用原始 URL */
    private fun taskIdentityUri(baseUrl: String, webappId: Int) =
        baseUrl.toUri().buildUpon()
            .apply { if (webappId < 0) fragment("raw$webappId") }
            .build()
}
