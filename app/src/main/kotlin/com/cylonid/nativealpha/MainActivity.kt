package com.cylonid.nativealpha

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
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
import com.cylonid.nativealpha.util.ShortcutIconUtils
import com.cylonid.nativealpha.util.SiteShareCodec
import com.cylonid.nativealpha.util.WebViewLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)
        // 状态栏/虚拟键跟随主题（切换主题后刷新颜色）
        ThemeUtils.applySystemBarColors(this)
        // 记录当前主题（onResume 对比用：变化才 recreate，首次不重建）
        lastAppliedThemeId = DataManager.getInstance().settings.themeId

        entryPointReached(this)

        // 分享深链导入（C-分享）：扫码/点开 webnative://add 链接 → 校验后
        // 进添加向导预填（fail-closed：解析失败提示无效，不进向导）
        routeShareDeepLink(intent)

        // 崩溃恢复提示：上次进程崩溃过 → 引导导出错误日志（异步检查，不阻塞启动）
        checkAndPromptCrashLog()

        setContent {
            AppMaterialTheme {
                // P2 响应式列表：写路径收口后由 webAppsFlow 驱动重组（快照 revision
                // 保证原地修改场景必达），onResume 强制重读与 refreshTrigger 退役
                val snapshot by DataManager.getInstance().webAppsFlow.collectAsState()
                val webApps: List<WebApp> = remember(snapshot) {
                    snapshot.items.filterNotNull().filter { it.isActiveEntry }.sortedBy { it.order }
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
                    onCopyUrl = { webApp ->
                        // 复制 Web App URL 到剪贴板
                        val cm = getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText("URL", webApp.baseUrl))
                        Toast.makeText(this, getString(R.string.copy_url_done), Toast.LENGTH_SHORT).show()
                    },
                    onGlobalSettingsClick = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onMatrixClick = {
                        startActivity(
                            Intent(this, com.cylonid.nativealpha.matrix.MatrixActivity::class.java)
                        )
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
        lifecycleScope.launch(Dispatchers.IO) {
            // 近 3 天记录（仓库统一口径 getRecent）
            val entries = AppErrorLogRepository.getRecent(applicationContext)
            val recentCrash = entries.any { it.level == AppErrorEntry.LEVEL_CRASH }
            if (recentCrash) {
                // 同一次崩溃只提示一次：记录最近提示的崩溃时间戳，避免每次启动重复弹
                val prefs = getSharedPreferences(PREFS_CRASH_PROMPT, MODE_PRIVATE)
                val lastPrompted = prefs.getLong(KEY_LAST_PROMPTED_CRASH, 0L)
                val latestCrashTime = entries.filter { it.level == AppErrorEntry.LEVEL_CRASH }
                    .maxOfOrNull { it.time } ?: 0L
                if (latestCrashTime > lastPrompted) {
                    prefs.edit { putLong(KEY_LAST_PROMPTED_CRASH, latestCrashTime) }
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask：App 在前台时再扫一个码走这里（onCreate 只覆盖冷启路径）
        routeShareDeepLink(intent)
    }

    /**
     * 分享深链路由（C-分享）：webnative://add?v=1&u=..&n=.. → 添加向导预填。
     * 深链数据视为不可信输入（happier fail-closed 纪律）：解析失败提示
     * 「链接无效」即止，绝不带着未校验的 URL 进向导。
     */
    private fun routeShareDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!SiteShareCodec.SCHEME.equals(data.scheme, ignoreCase = true)) return
        val shared = SiteShareCodec.parseShareLink(data.toString())
        if (shared == null) {
            Toast.makeText(this, R.string.share_invalid_link, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent(this, AddWebAppActivity::class.java)
                .putExtra(AddWebAppActivity.EXTRA_PREFILL_URL, shared.url)
                .putExtra(AddWebAppActivity.EXTRA_PREFILL_NAME, shared.name)
        )
    }

    override fun onResume() {
        super.onResume()
        // 幂等加载（首启真正解析；已加载时短路）。数据变更后列表由 webAppsFlow
        // 驱动刷新，不再 force 重读 SP——force 退役后每帧回前台零 Gson 解析
        DataManager.getInstance().loadAppData()
        // 主题即时切换（像微信）：onResume 对比 themeId，变化则 recreate（系统栏/状态栏即时刷新）
        val themeId = DataManager.getInstance().settings.themeId
        if (lastAppliedThemeId != themeId) {
            lastAppliedThemeId = themeId
            recreate()
        }
    }

    override fun onStop() {
        super.onStop()
        // 掉电兜底：后台前挂起等待在途快照落盘（协程挂起不占线程，stop 无帧预算）
        lifecycleScope.launch { DataManager.getInstance().awaitPendingSave() }
    }

    companion object {
        /** 主题即时切换：记录上次应用的 themeId（onResume 对比，变化 recreate） */
        @Volatile
        var lastAppliedThemeId: Int = -1
        /** 崩溃提示去重：记录最近提示过的崩溃时间戳（同次崩溃只弹一次） */
        private const val PREFS_CRASH_PROMPT = "crash_prompt"
        private const val KEY_LAST_PROMPTED_CRASH = "last_prompted_crash_time"
    }

    /** 删除 WebApp：直接删除，不弹确认（用户要求） */
    private fun deleteWebApp(webApp: WebApp) {
        // 深拷贝陷阱（P1 修复 2026-08-24）：跟随全局的站点 getWebApp 返回 merged 深拷贝，
        // markInactive 改拷贝无效——必须按 ID 取内存原对象置 inactive，并落库防进程重启复活
        DataManager.getInstance().getWebAppIgnoringGlobalOverride(webApp.ID, true)
            ?.let { target ->
                target.isActiveEntry = false
                val hadPinned = ShortcutIconUtils.deleteShortcuts(listOf(target.ID), this)
                // 桌面 pin 图标平台不允许 app 强删（已灰化+点击提示）——有 pin 时告知用户手动移除
                if (hadPinned) {
                    Toast.makeText(
                        this, getString(R.string.shortcut_remove_manually), Toast.LENGTH_LONG
                    ).show()
                }
            }
        // 统一写收口：isActiveEntry 是绕过 DataManager 写方法的内存改动，
        // commitChanges 发射 flow（列表即时移除条目）+ 触发持久化
        DataManager.getInstance().commitChanges()
        // 网页事件规则级联删除（P5-3）：站点没了规则不留死角
        com.cylonid.nativealpha.webevent.WebeventRuntime.cascadeDeleteForSite(webApp.ID)
        // 站点健康登记同步清理（presence 会话态不残留已删站点）
        com.cylonid.nativealpha.util.SiteHealthRegistry.forget(webApp.ID)
    }
}
