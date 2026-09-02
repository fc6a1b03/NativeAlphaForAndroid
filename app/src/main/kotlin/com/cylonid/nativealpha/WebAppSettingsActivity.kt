package com.cylonid.nativealpha

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.cylonid.nativealpha.util.SystemBars
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.util.AppMaterialTheme
import com.cylonid.nativealpha.util.ThemeUtils
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.ui.ShortcutRecreateDialog
import com.cylonid.nativealpha.ui.WebAppSettingsScreen
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.Utility

/**
 * 单个 WebApp 的设置页（Compose 实现）。
 */
class WebAppSettingsActivity : AppCompatActivity(), SystemBars.SelfManagedInsets {

    private var webappID: Int = -1
    private var webapp: WebApp? = null
    private var isGlobalWebApp: Boolean = false
    private var recreateDialog by mutableStateOf(false)

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
                        recreateDialog = true
                    }
                )
                if (recreateDialog && webapp != null && !isGlobalWebApp) {
                    ShortcutRecreateDialog(webapp = webapp!!, onDismiss = { recreateDialog = false })
                }
            }
        }
    }

    private fun save(modified: WebApp) {
        // iconPath 以 modified 为准（设置页全链路已保留：null 表示"重置/未设置"——不能再覆盖）
        // 注：此前"原对象 iconPath 补回"的防护已删除——那会把用户重置图标(null)反向覆盖成旧值
        if (isGlobalWebApp) {
            DataManager.getInstance().settings.globalWebApp = modified
            DataManager.getInstance().saveGlobalSettings()
        } else {
            DataManager.getInstance().replaceWebApp(modified)
        }
        val i = Intent(this, MainActivity::class.java)
        i.putExtra(Const.INTENT_WEBAPP_CHANGED, true)
        // 先启动 MainActivity 再 finish 自己：singleTask 下复用已有实例，
        // 确保 onNewIntent/onResume 被触发；finish 在前可能导致 Intent 丢失或生命周期异常
        startActivity(i)
        finish()
    }
}
