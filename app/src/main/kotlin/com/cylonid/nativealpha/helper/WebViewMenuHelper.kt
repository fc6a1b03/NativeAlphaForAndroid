package com.cylonid.nativealpha.helper

import android.content.ClipData
import android.content.ClipboardManager
import android.app.AlertDialog
import android.content.Intent
import android.webkit.WebStorage
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.cylonid.nativealpha.MainActivity
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.WebAppSettingsActivity
import com.cylonid.nativealpha.WebViewActivity
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.ui.showShortcutMenuOverlay
import com.cylonid.nativealpha.ui.showWebViewMenuOverlay
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.CookieSessionManager
import com.cylonid.nativealpha.util.ErrorReporter
import com.cylonid.nativealpha.util.StatsRecorder
import com.cylonid.nativealpha.util.WebViewLauncher
import java.io.File

/**
 * WebView 菜单浮层处理器（v2.2.0 P3 第四刀，自 WebViewActivity 逐行迁移，零语义变更）。
 *
 * 职责：Compose 底部菜单（缩放预览/导航/会话标签/快捷键面板）+ 缓存统计。
 * 原私有 PopupMenu 上下文菜单为死代码（全项目零调用点）已随本刀清除，
 * IconPopupMenuHelper 与 mPopupMenu/fallbackToDefaultLongClickBehaviour 字段一并清零。
 */
class WebViewMenuHelper(private val activity: WebViewActivity) {

    /** 菜单中页面缩放预览值（保存时写回 webapp） */
    private var mMenuPageZoom = 100

    /** 显示 Compose 底部菜单（当前页叠加，WebView 保留在后面；滑杆实时预览，关闭即保存） */
    fun showWebViewMenuSheet() {
        val currentUrl = activity.wv?.url ?: ""
        // 初始化页面缩放待保存值（防只调字体时把已保存的 pageZoom 覆盖成 100）
        mMenuPageZoom = activity.webapp!!.pageZoom
        activity.showWebViewMenuOverlay(
            currentUrl,
            activity.wv!!.canGoBack(),
            activity.wv!!.canGoForward(),
            activity.webapp!!.textZoom,
            activity.webapp!!.pageZoom,
            { action -> handleMenuAction(action); Unit },
            { zoom ->
                // 实时预览字体缩放
                activity.wv?.settings?.textZoom = zoom
                Unit
            },
            { zoom ->
                // 实时预览页面缩放 + 记录待保存值（zoomBy 模拟捏合）
                mMenuPageZoom = zoom
                if (activity.wv != null) {
                    activity.webapp!!.pageZoom = zoom
                    applyPageZoom()
                }
                Unit
            },
            { saveZoomSettings(); Unit }
        )
    }

    /** 保存字体/缩放设置到 WebApp 原对象（菜单关闭时触发），不污染合并对象。
     *  菜单调整 = 应用设置（单一事实源，不存在优先级困惑）：
     *  跟随全局（override=false）时，先把当前生效配置整体继承为应用自身配置，
     *  再开 override——除本次缩放外，其他设置不因 override 切换而跳变。 */
    private fun saveZoomSettings() {
        if (activity.wv == null || activity.webapp == null) return
        val original =
            DataManager.getInstance().getWebAppIgnoringGlobalOverride(activity.webappID, true) ?: return
        if (!original.isOverrideGlobalSettings) {
            original.copySettings(activity.webapp!!) // webapp = 当前生效的合并对象
        }
        original.textZoom = activity.wv!!.settings.textZoom
        original.pageZoom = mMenuPageZoom
        original.isOverrideGlobalSettings = true
        DataManager.getInstance().replaceWebApp(original)
    }

    /**
     * 页面缩放：setInitialScale（内容缩放，不改 viewport 布局模式）。
     * 必须在页面加载完成后调用才稳定（加载前设置对移动自适应页面无效）。
     * 不用 zoomBy：模拟捏合会触发缩放状态机，破坏 viewport 导致页面空白/布局错乱。
     */
    fun applyPageZoom() {
        if (activity.wv == null || activity.webapp == null) return
        val zoom = activity.webapp!!.pageZoom
        activity.wv!!.setInitialScale(zoom)
    }

    /** 菜单动作处理（异常进错误日志——实机排查唯一入口是导出日志） */
    private fun handleMenuAction(action: String) {
        try {
            handleMenuActionInner(action)
        } catch (e: Exception) {
            ErrorReporter.report(activity, "MenuAction", "menu action failed: $action", e)
        }
    }

    private fun handleMenuActionInner(action: String) {
        when (action) {
            "back" -> activity.triggerBack()
            "forward" -> if (activity.wv != null && activity.wv!!.canGoForward()) activity.wv!!.goForward()
            "reload" -> activity.wv?.reload()
            "copy" -> {
                if (activity.wv != null && activity.wv!!.url != null) {
                    val clipboard = activity.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("URL", activity.wv!!.url))
                }
            }
            "share" -> {
                if (activity.wv != null && activity.wv!!.url != null) {
                    androidx.core.app.ShareCompat.IntentBuilder(activity)
                        .setType("text/plain")
                        .setChooserTitle("Share URL")
                        .setText(activity.wv!!.url)
                        .startChooser()
                }
            }
            "home" -> {
                val intent = Intent(activity, MainActivity::class.java)
                activity.startActivity(intent)
            }
            "close" -> activity.finishAndRemoveTask()
            "new_tab" -> {
                // 新增会话：sessionTabCount+1，跳到新标签（销毁当前，重建）
                addNewSessionTab()
            }
            "switch_tab" -> {
                // 切换会话：弹标签选择（单实例，选后销毁重建）
                showSessionSwitchDialog()
            }
            "delete_tab" -> {
                // 删除会话：会话数-1，重建回第一个标签
                deleteCurrentSessionTab()
            }
            "shortcuts" -> {
                // 组合快捷键面板（录制/发送，页面独有快捷键）
                showShortcutMenuSheet()
            }
            "settings" -> {
                // 跳转 WebApp 设置页（小菜单直达管理，与快捷键面板「管理」一致）
                val settingsIntent = Intent(activity, WebAppSettingsActivity::class.java)
                settingsIntent.putExtra(Const.INTENT_WEBAPPID, activity.webappID)
                activity.startActivity(settingsIntent)
            }
        }
    }

    /** 新增会话：sessionTabCount+1（隔离模式下），保存当前快照后销毁重建到新标签 */
    private fun addNewSessionTab() {
        if (activity.webapp == null) return
        val original =
            DataManager.getInstance().getWebAppIgnoringGlobalOverride(activity.webappID, true) ?: return
        // 会话数+1（上限10，防内存）
        if (original.sessionTabCount < 10) {
            original.sessionTabCount = original.sessionTabCount + 1
            DataManager.getInstance().replaceWebApp(original)
        }
        val newTab = original.sessionTabCount - 1
        // 保存当前快照（异步）→ CLEAR_TOP 复用实例重载到新标签（不销毁，单实例）
        CookieSessionManager.saveSnapshot(activity, activity.webappID, activity.webappTabIndex)
        WebViewLauncher.startWebViewById(activity.webappID, newTab, activity)
    }

    /** 切换会话：弹对话框列出所有会话标签，选一个销毁重建 */
    private fun showSessionSwitchDialog() {
        if (activity.webapp == null) return
        val count = maxOf(1, activity.webapp!!.sessionTabCount)
        // 二级会话菜单（简约）：列表切换 + "新增"；多会话才显示"删除"。
        // 禁用 setMessage：AlertDialog 的 message 与 items 互斥——message 存在时
        // 列表行不渲染（用户实测「看不到会话行只见按钮」的根因）；
        // 会话行文案已含「（当前）」标记，提示语并入 title 行
        val items = Array(count) { i ->
            if (i == activity.webappTabIndex) activity.getString(R.string.session_item_current, i + 1)
            else activity.getString(R.string.session_item, i + 1)
        }
        val b = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.menu_session))
            .setItems(items) { _, which ->
                if (which != activity.webappTabIndex) {
                    ErrorReporter.runCatchingReport(
                        activity, "SessionSwitch",
                        activity.getString(R.string.session_switch_failed)
                    ) {
                        CookieSessionManager.saveSnapshot(activity, activity.webappID, activity.webappTabIndex)
                        WebViewLauncher.startWebViewById(activity.webappID, which, activity)
                    }
                }
            }
            .setPositiveButton(R.string.session_add) { _, _ -> addNewSessionTab() }
        // 单会话不显示删除（至少保留一个）
        if (count > 1) {
            b.setNegativeButton(R.string.delete) { _, _ -> deleteCurrentSessionTab() }
        }
        b.show()
    }

    /** 删除会话：会话数-1，销毁重建到第一个会话（保留目标快照） */
    private fun deleteCurrentSessionTab() {
        if (activity.webapp == null) return
        val original =
            DataManager.getInstance().getWebAppIgnoringGlobalOverride(activity.webappID, true) ?: return
        val count = maxOf(1, original.sessionTabCount)
        if (count == 1) {
            // 单会话：不删除（至少保留一个），提示
            Toast.makeText(activity, activity.getString(R.string.session_at_least_one), Toast.LENGTH_SHORT)
                .show()
            return
        }
        original.sessionTabCount = count - 1
        DataManager.getInstance().replaceWebApp(original)
        // 保存快照 → CLEAR_TOP 复用重载到第一个会话（不销毁）
        CookieSessionManager.saveSnapshot(activity, activity.webappID, activity.webappTabIndex)
        WebViewLauncher.startWebViewById(activity.webappID, 0, activity)
    }

    /** 显示组合快捷键面板（ModalBottomSheet，纯发送；管理在设置页） */
    private fun showShortcutMenuSheet() {
        activity.showShortcutMenuOverlay(
            activity.webappID
        ) { shortcut ->
            // 发送组合键到当前页面（JS 合成 KeyboardEvent）
            activity.shortcutHelper.sendShortcutToPage(shortcut)
            Unit
        }
    }

    /** 保存快捷键到 WebApp 原对象 */
    private fun saveShortcutSettings() {
        val original =
            DataManager.getInstance().getWebAppIgnoringGlobalOverride(activity.webappID, true) ?: return
        DataManager.getInstance().replaceWebApp(original)
    }

    /**
     * 统计缓存占用（异步，不阻塞主线程）：
     * - HTTP 缓存：cacheDir 递归求和（WebView 缓存目录，含 app_webview）
     * - 站点存储：WebStorage.getUsageForOrigin（localStorage/IndexedDB 等，回调异步补写）
     * 调用点：页面加载完成（onPageFinished）后，WebView 缓存已就绪。
     */
    fun recordCacheUsage() {
        if (activity.wv == null) return
        try {
            // HTTP 缓存：cacheDir 递归求和（IO 操作，放 StatsRecorder 线程避免主线程卡顿）
            // 不依赖 getOrigins 回调：HTTP 缓存立即统计，站点存储回调补写（两者独立）
            StatsRecorder.record {
                try {
                    val httpBytes = dirSize(activity.cacheDir)
                    updateStatsCache(httpBytes, -1L) // -1 表示站点存储待补
                } catch (e: Exception) {
                    // 缓存统计失败静默（不影响主功能）
                }
            }
            // 站点存储：异步查询（WebStorage 回调），回调后单独补写
            WebStorage.getInstance().getOrigins { originsMap ->
                var storeBytes = 0L
                if (originsMap != null) {
                    // getOrigins 回调为原始 Map：values 需强转 WebStorage.Origin
                    for (o in originsMap.values) {
                        if (o is WebStorage.Origin) {
                            storeBytes += if (o.quota > 0) o.usage else 0L
                        }
                    }
                }
                val finalStoreBytes = storeBytes
                StatsRecorder.record {
                    updateStatsCache(-1L, finalStoreBytes) // -1 表示 HTTP 缓存已统计
                }
            }
        } catch (e: Exception) {
            // 缓存统计失败静默（不影响主功能）
        }
    }

    /** 目录递归求和（缓存目录统计；符号链接不跟随，防重复计数） */
    private fun dirSize(dir: File): Long {
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            size += if (f.isDirectory) dirSize(f) else f.length()
        }
        return size
    }

    /** 更新 WebApp 缓存统计字段（原对象，防合并副本覆盖；-1 表示该值待补/已统计，跳过） */
    private fun updateStatsCache(httpBytes: Long, storeBytes: Long) {
        val original =
            DataManager.getInstance().getWebAppIgnoringGlobalOverride(activity.webappID, true) ?: return
        if (httpBytes >= 0) original.statCacheHttpBytes = httpBytes
        if (storeBytes >= 0) original.statCacheStoreBytes = storeBytes
        DataManager.getInstance().replaceWebApp(original)
    }
}
