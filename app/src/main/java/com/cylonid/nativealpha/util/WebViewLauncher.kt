package com.cylonid.nativealpha.util

import android.content.Context
import com.cylonid.nativealpha.WebViewActivity
import com.cylonid.nativealpha.model.WebApp
import androidx.appcompat.app.AppCompatActivity
import com.cylonid.nativealpha.R
import com.google.android.material.snackbar.Snackbar
import android.content.Intent
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

    /** 多标签会话：webappId + tabIndex 定位会话（小菜单新增/切换/删除会话用） */
    @JvmStatic
    fun createWebViewIntent(webapp: WebApp, c: Context?, webappId: Int, tabIndex: Int): Intent? {
        val intent = Intent(c, WebViewActivity::class.java)
        intent.putExtra(Const.INTENT_WEBAPPID, webappId)
        intent.putExtra(Const.INTENT_TAB_INDEX, tabIndex)
        intent.action = Intent.ACTION_VIEW
        return intent
    }

    /** 通过 webappId + tabIndex 重新打开会话（无需 WebApp 对象，切换标签用） */
    @JvmStatic
    fun startWebViewById(webappId: Int, tabIndex: Int, c: Context) {
        try {
            val intent = Intent(c, WebViewActivity::class.java)
            intent.putExtra(Const.INTENT_WEBAPPID, webappId)
            intent.putExtra(Const.INTENT_TAB_INDEX, tabIndex)
            intent.action = Intent.ACTION_VIEW
            c.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
