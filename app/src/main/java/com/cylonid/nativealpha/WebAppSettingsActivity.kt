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
        // 状态栏/虚拟键跟随主题（切换主题后刷新颜色）
        ThemeUtils.applySystemBarColors(this)

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
        // 图标统一源防护：ShortcutDialogFragment（重新创建快捷方式弹窗）操作的是
        // 原对象 webapp（快捷方式图标/名称回填会更新其 iconPath），
        // 保存时必须把原对象的最新 iconPath 同步到 modified——否则用旧副本覆盖
        // 会把弹窗刚获取的图标抹掉（列表图标回退字母——重开丢失根因之一）。
        val originIconPath = webapp?.iconPath
        if (modified.iconPath == null && originIconPath != null) {
            modified.iconPath = originIconPath
        }
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
