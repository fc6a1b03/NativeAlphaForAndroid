package com.cylonid.nativealpha.util

import android.content.Context
import com.cylonid.nativealpha.WebViewActivity
import com.cylonid.nativealpha.model.WebApp
import androidx.appcompat.app.AppCompatActivity
import com.cylonid.nativealpha.R
import com.google.android.material.snackbar.Snackbar
import android.content.Intent
import android.net.Uri
import java.lang.NullPointerException

object WebViewLauncher {
    @JvmStatic
    fun startWebView(webapp: WebApp, c: Context) {
        try {
            c.startActivity(createWebViewIntent(webapp, c))
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
        val intent = Intent(c, WebViewActivity::class.java)
        intent.putExtra(Const.INTENT_WEBAPPID, webapp.ID)
        intent.data = Uri.parse(webapp.baseUrl + webapp.ID)
        intent.action = Intent.ACTION_VIEW
        return intent
    }
}
