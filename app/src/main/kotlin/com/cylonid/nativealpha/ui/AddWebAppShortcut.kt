package com.cylonid.nativealpha.ui

import android.content.Context
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.App
import com.cylonid.nativealpha.util.ShortcutIconUtils
import com.cylonid.nativealpha.util.WebAppIconManager
import com.cylonid.nativealpha.util.WebViewLauncher

/** 创建桌面快捷方式（复用 ShortcutDialogFragment 的 pin 逻辑）。
 *  图标与列表同源：resolveIconCached（iconPath→字母渐变，**无网络**——本方法在 UI 线程调用）。
 *  favicon 补拉已在 onFinish 后台完成（成功则 iconPath 已持久化），此处不重复拉网。 */
internal fun requestPinShortcut(webapp: WebApp) {
    val context = App.getAppContext()
    val intent = WebViewLauncher.createWebViewIntent(webapp, context) ?: return

    val icon = IconCompat.createWithBitmap(
        WebAppIconManager.resolveIconCached(context, webapp)
    )

    val title = webapp.title
    val safeTitle = if (title.isNullOrBlank()) "Unknown" else title

    val shortId = ShortcutIconUtils.pinnedShortcutId(webapp.ID)  // 稳定 ID（title 会变——快捷方式 id 不变，可后续更新）
    val pinInfo = ShortcutInfoCompat.Builder(context, shortId)
        .setIcon(icon)
        .setShortLabel(safeTitle)
        .setLongLabel(safeTitle)
        .setIntent(intent)
        .build()
    // 1) 注册 Dynamic Shortcut（同 ID）——可随时 updateShortcuts 更新图标（桌面图标跟随列表）
    // 2) requestPinShortcut 弹系统确认（固定到桌面）
    // 注：先 dynamic 后 pin——Launcher 识别同 ID 关联，后续 updateShortcuts 能刷新已 pin 的
    try {
        ShortcutManagerCompat.addDynamicShortcuts(context, listOf(pinInfo))
    } catch (e: Exception) {
        // 动态注册失败不阻塞 pin（部分旧 Launcher 不支持）
    }
    if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        ShortcutManagerCompat.requestPinShortcut(context, pinInfo, null)
    }
}

/** 更新已注册快捷方式的图标（图标就绪时调用——桌面跟随列表图标）。
 *  resolveIconCached 无网络（UI 线程安全）；此时 favicon 补拉已完成，iconPath 要么持久化要么用字母。 */
fun updateShortcutIcon(context: Context, webapp: WebApp) {
    try {
        val bmp = WebAppIconManager.resolveIconCached(context, webapp)
        val intent = WebViewLauncher.createWebViewIntent(webapp, context) ?: return
        val title = webapp.title
        val safeTitle = if (title.isNullOrBlank()) "Unknown" else title
        val info = ShortcutInfoCompat.Builder(context, ShortcutIconUtils.pinnedShortcutId(webapp.ID))
            .setIcon(IconCompat.createWithBitmap(bmp))
            .setShortLabel(safeTitle)
            .setLongLabel(safeTitle)
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.updateShortcuts(context, listOf(info))
    } catch (e: Exception) {
        // 更新失败静默（下次列表刷新再试）
    }
}
