package com.cylonid.nativealpha.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cylonid.nativealpha.model.WebApp
import java.io.File
import java.io.FileOutputStream

/**
 * WebApp 头像唯一管理入口（统一数据源）。
 *
 * 数据源：WebApp.iconPath 记录 App 文件目录（files/icons/webapp_N.png）里
 * 的头像文件路径；null = 用字母渐变图标（IconGenerator）。
 *
 * 唯一逻辑：
 * - saveIcon(webApp, bitmap)  → 存文件 + 更新 webApp.iconPath（唯一写入点）
 * - loadIcon(webApp)          → 按 iconPath 读 bitmap（唯一读取点）
 * - deleteIcon(webApp)        → 删文件 + iconPath=null（唯一清除点）
 *
 * 调用方：AddWebApp（创建时选图/回填）、WebAppSettings（设置页换图/回填/重置）、
 * MainScreen（列表图标——有 iconPath 用头像，否则 IconGenerator）。
 *
 * 注意：**不重复存储**——Setuptab 创建的系统快捷方式图标由 ShortcutManager
 * 持有，App 内统一只通过 iconPath 引用（不做第二份拷贝）。
 */
object WebAppIconManager {

    /** 头像目录 */
    private fun iconDir(context: Context): File =
        File(context.filesDir, "icons").apply { if (!exists()) mkdirs() }

    /** 保存头像 bitmap → 更新 webApp.iconPath（覆盖旧文件，先删后写） */
    fun saveIcon(context: Context, webApp: WebApp, bitmap: Bitmap): Boolean {
        return try {
            val dir = iconDir(context)
            // 清除该 WebApp 旧头像（防残留）
            dir.listFiles()?.forEach { if (it.name.startsWith("webapp_${webApp.ID}_")) it.delete() }
            val file = File(dir, "webapp_${webApp.ID}_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            webApp.iconPath = file.absolutePath
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 加载头像 bitmap（iconPath 存在且文件可读时；否则 null → 调用方用字母图标） */
    fun loadIcon(context: Context, webApp: WebApp): Bitmap? {
        val p = webApp.iconPath ?: return null
        return try {
            val f = File(p)
            if (f.exists()) BitmapFactory.decodeFile(p) else null
        } catch (e: Exception) {
            null
        }
    }

    /** 删除头像（重置为字母图标时调用；iconPath 清空） */
    fun deleteIcon(context: Context, webApp: WebApp) {
        val p = webApp.iconPath ?: return
        try {
            File(p).delete()
        } catch (e: Exception) {
            // 删除失败静默
        }
        webApp.iconPath = null
    }
}
