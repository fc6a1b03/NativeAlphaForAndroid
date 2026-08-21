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

    /** 网站 favicon 缓存目录（列表图标 fallback：无自定义头像时显示网站图标） */
    private fun faviconDir(context: Context): File =
        File(context.cacheDir, "favicons").apply { if (!exists()) mkdirs() }

    /**
     * 加载/拉取网站 favicon（列表图标用）：优先缓存；无则拉站点根 /favicon.ico
     * （站点直连，多数站可达；Kimi/百度等）失败 fallback Google s2。
     * 网络在调用方协程/线程处理（本方法同步执行，需 IO 上下文）。
     * 失败返回 null（调用方 fallback 字母图标）。
     */
    fun loadFavicon(context: Context, webApp: WebApp): Bitmap? {
        val domain = try { java.net.URI(webApp.baseUrl).host } catch (e: Exception) { null }
            ?: return null
        val cacheFile = File(faviconDir(context), "$domain.png")
        // 缓存命中直接读
        if (cacheFile.exists()) {
            return try { BitmapFactory.decodeFile(cacheFile.absolutePath) } catch (e: Exception) { null }
        }
        // 拉取：先站点 favicon.ico，再 Google s2 兜底
        val candidates = listOf(
            java.net.URI(webApp.baseUrl).let { "${it.scheme}://${it.host}/favicon.ico" },
            "https://www.google.com/s2/favicons?domain=$domain&sz=112"
        )
        for (url in candidates) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("User-Agent", "WebNative-Favicon")
                if (conn.responseCode == 200) {
                    val bmp = BitmapFactory.decodeStream(conn.inputStream)
                    bmp?.let { FileOutputStream(cacheFile).use { out -> it.compress(Bitmap.CompressFormat.PNG, 90, out) } }
                    if (bmp != null) return bmp
                }
            } catch (e: Exception) {
                // 尝试下一个候选
            }
        }
        return null
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
