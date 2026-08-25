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

    /** 桌面快捷方式稳定 ID 规则（唯一实现处）——pin/更新/重建必须用同一 ID 才能互相找到 */
    @JvmStatic
    fun pinnedShortcutId(webappId: Int): String = "webapp_$webappId"

    /**
     * 删除/禁用指定 Web App 的桌面快捷方式。
     * - pinned（pin 到桌面的）：disable 而非 remove——避免部分启动器 remove 弹确认框；
     *   Android 不允许 app 主动移除 pin 图标，disable 后图标灰化+点击提示已删除
     * - dynamic（动态注册的）：必须 removeDynamicShortcuts——否则残留可用快捷方式
     * @return 是否有桌面 pin 图标被禁用（调用方据此提示用户手动移除）
     */
    @JvmStatic
    fun deleteShortcuts(removableWebAppIds: List<Int>, context: Context): Boolean {
        if (removableWebAppIds.isEmpty()) return false
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
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
        // 动态快捷方式同步清理（否则应用内长按/搜索仍出现已删 WebApp 的入口）
        manager.removeDynamicShortcuts(removableWebAppIds.map { pinnedShortcutId(it) })
        return toDisable.isNotEmpty()
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
