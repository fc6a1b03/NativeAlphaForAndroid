package com.cylonid.nativealpha

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cylonid.nativealpha.model.AppErrorEntry
import com.cylonid.nativealpha.model.AppErrorLogRepository
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.util.AppMaterialTheme
import com.cylonid.nativealpha.util.ThemeUtils
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.ui.AddWebAppActivity
import com.cylonid.nativealpha.ui.MainScreen
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.EntryPointUtils.entryPointReached
import com.cylonid.nativealpha.util.WebViewLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)

        entryPointReached(this)

        // 崩溃恢复提示：上次进程崩溃过 → 引导导出错误日志（异步检查，不阻塞启动）
        checkAndPromptCrashLog()

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
                    onOpenStats = { webApp ->
                        val intent = Intent(this, WebAppStatsActivity::class.java)
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

    /**
     * 崩溃恢复提示：检查近 3 天是否有 CRASH 级应用错误日志，
     * 有则弹窗引导去全局设置导出（错误日志入口较深，主动提示形成闭环）。
     * 异步执行（IO 协程），不阻塞启动；无崩溃或检查失败静默。
     */
    private fun checkAndPromptCrashLog() {
        CoroutineScope(Dispatchers.IO).launch {
            val entries = AppErrorLogRepository.getAll(applicationContext)
            // 近 3 天 CRASH 级记录（常量 APP_ERROR_DAYS）
            val cutoff = System.currentTimeMillis() - Const.APP_ERROR_DAYS * 24L * 60 * 60 * 1000
            val recentCrash = entries.any { it.level == AppErrorEntry.LEVEL_CRASH && it.time >= cutoff }
            if (recentCrash) {
                runOnUiThread {
                    try {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(getString(R.string.crash_detected_title))
                            .setMessage(getString(R.string.crash_detected_msg))
                            .setPositiveButton(getString(R.string.crash_export_logs)) { _, _ ->
                                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } catch (ignored: Exception) {
                        // 弹窗失败（Activity 已销毁等）静默
                    }
                }
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
