package com.cylonid.nativealpha.util

import android.content.Context
import android.content.pm.ShortcutManager
import com.cylonid.nativealpha.R

/**
 * 快捷方式管理：删除/禁用 Web App 对应的桌面快捷方式。
 *
 * 首页删除 Web App、应用卸载时都会调用，保证桌面快捷方式同步清理。
 */
object ShortcutIconUtils {

    /**
     * 禁用指定 Web App 的桌面快捷方式。
     * 使用 disable 而非 remove：避免部分启动器在 remove 时弹确认框。
     */
    @JvmStatic
    fun deleteShortcuts(removableWebAppIds: List<Int>, context: Context) {
        if (removableWebAppIds.isEmpty()) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val toDisable = mutableListOf<String>()
        for (info in manager.pinnedShortcuts) {
            val id = info.intent?.getIntExtra(Const.INTENT_WEBAPPID, -1) ?: -1
            if (removableWebAppIds.contains(id)) {
                toDisable.add(info.id)
            }
        }
        if (toDisable.isNotEmpty()) {
            manager.disableShortcuts(toDisable, context.getString(R.string.webapp_already_deleted))
        }
    }

    @JvmStatic
    fun getWidthFromIcon(sizeString: String): Int {
        var xIndex = sizeString.indexOf("x")
        if (xIndex == -1) xIndex = sizeString.indexOf("×")
        if (xIndex == -1) xIndex = sizeString.indexOf("*")

        if (xIndex == -1) return 1
        val width = sizeString.substring(0, xIndex)

        return width.toInt()
    }
}
