package com.cylonid.nativealpha

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.util.AppMaterialTheme
import com.cylonid.nativealpha.util.ThemeUtils
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.ui.AddWebAppActivity
import com.cylonid.nativealpha.ui.MainScreen
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.EntryPointUtils.entryPointReached
import com.cylonid.nativealpha.util.WebViewLauncher

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)

        entryPointReached(this)

        setContent {
            AppMaterialTheme {
                val refreshKey = MainActivity.refreshTrigger
                val webApps: List<WebApp> = remember(refreshKey) {
                    DataManager.getInstance().activeWebsites.filterNotNull()
                }

                MainScreen(
                    webApps = webApps,
                    onAddClick = {
                        startActivity(Intent(this, AddWebAppActivity::class.java))
                    },
                    onOpenWebApp = { webApp ->
                        WebViewLauncher.startWebView(webApp, this)
                    },
                    onOpenSettings = { webApp ->
                        val intent = Intent(this, WebAppSettingsActivity::class.java)
                        intent.putExtra(Const.INTENT_WEBAPPID, webApp.ID)
                        startActivity(intent)
                    },
                    onDeleteWebApp = { webApp ->
                        deleteWebApp(webApp)
                    },
                    onGlobalSettingsClick = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 数据可能在设置页被修改，重新加载后刷新 Compose 状态
        DataManager.getInstance().loadAppData()
        refreshTrigger++
    }

    companion object {
        /** 用于触发 Compose 列表刷新的计数器（onResume 时自增）——必须用 Compose state，普通变量无法触发重组 */
        var refreshTrigger: Int by mutableIntStateOf(0)
    }

    /** 删除 WebApp：直接删除，不弹确认（用户要求） */
    private fun deleteWebApp(webApp: WebApp) {
        DataManager.getInstance().getWebAppIgnoringGlobalOverride(webApp.ID, true)?.markInactive(this)
        // 刷新列表（删除后立即移除条目）
        refreshTrigger++
    }
}
