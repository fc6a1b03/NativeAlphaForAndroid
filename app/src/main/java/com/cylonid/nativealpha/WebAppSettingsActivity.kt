package com.cylonid.nativealpha

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.util.AppMaterialTheme
import com.cylonid.nativealpha.util.ThemeUtils
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.ui.WebAppSettingsScreen
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.Utility

/**
 * 单个 WebApp 的设置页（Compose 实现）。
 */
class WebAppSettingsActivity : AppCompatActivity() {

    private var webappID: Int = -1
    private var webapp: WebApp? = null
    private var isGlobalWebApp: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)

        webappID = intent.getIntExtra(Const.INTENT_WEBAPPID, -1)
        if (webappID == -1) {
            finish()
            return
        }
        isGlobalWebApp = webappID == DataManager.getInstance().settings.globalWebApp.ID

        webapp = if (isGlobalWebApp) {
            DataManager.getInstance().settings.globalWebApp
        } else {
            DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true)
        }

        if (webapp == null) {
            finish()
            return
        }

        setContent {
            AppMaterialTheme {
                WebAppSettingsScreen(
                    webapp = webapp!!,
                    isGlobal = isGlobalWebApp,
                    onBack = { finish() },
                    onSave = { modified -> save(modified) },
                    onRecreateShortcut = {
                        val frag = ShortcutDialogFragment.newInstance(webapp!!)
                        frag.show(supportFragmentManager, "SCFetcher-" + webapp!!.ID)
                    }
                )
            }
        }
    }

    private fun save(modified: WebApp) {
        if (isGlobalWebApp) {
            DataManager.getInstance().settings.globalWebApp = modified
            DataManager.getInstance().saveGlobalSettings()
        } else {
            DataManager.getInstance().replaceWebApp(modified)
        }
        val i = Intent(this, MainActivity::class.java)
        i.putExtra(Const.INTENT_WEBAPP_CHANGED, true)
        finish()
        startActivity(i)
    }
}
