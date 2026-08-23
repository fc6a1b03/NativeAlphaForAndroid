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

    /** 加载头像 bitmap（iconPath 存在且文件可读时；否则 null → 调用方用字母图标）。
     *  健壮性：decode 失败/文件损坏 → 删文件 + 清 iconPath（防脏引用反复走字母且永不复位） */
    fun loadIcon(context: Context, webApp: WebApp): Bitmap? {
        val p = webApp.iconPath ?: return null
        return try {
            val f = File(p)
            if (f.exists()) {
                val bmp = BitmapFactory.decodeFile(p)
                if (bmp == null) {
                    // 文件损坏：清理防污染（下次 resolveIcon 重新拉）
                    f.delete()
                    webApp.iconPath = null
                    com.cylonid.nativealpha.model.DataManager.getInstance().saveWebAppData()
                    return null
                }
                bmp
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** 站点根 favicon.ico URL（从 baseUrl 提取协议+host+端口——IP:port 站如 Kimi Code 需保留端口） */
    private fun hostFor(baseUrl: String): String = try {
        java.net.URI(baseUrl).let { uri ->
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$port/favicon.ico"
        }
    } catch (e: Exception) {
        "$baseUrl/favicon.ico"
    }

    /** favicon 最小边长（px）：过滤 1x1 占位符等异常图 */
    private const val MIN_FAVICON_PX = 16

    /** favicon 网络超时（ms） */
    private const val FAVICON_TIMEOUT_MS = 4000

    /** 网站 favicon 缓存目录（列表图标 fallback：无自定义头像时显示网站图标） */
    private fun faviconDir(context: Context): File =
        File(context.cacheDir, "favicons").apply { if (!exists()) mkdirs() }

    /**
     * 加载/拉取网站 favicon（列表图标用）：优先缓存；无则拉站点根 /favicon.ico
     * （站点直连，多数站可达；Kimi/百度等）失败 fallback Google s2。
     * 网络在调用方协程/线程处理（本方法同步执行，需 IO 上下文）。
     * 一旦拉取成功：**持久化到 iconPath（filesDir 永久存储）+ 缓存**——
     * 列表图标稳定显示（不再依赖 cacheDir 易被系统清理，重启不丢）。
     * 失败返回 null（调用方 fallback 字母图标）。
     */
    fun loadFavicon(context: Context, webApp: WebApp): Bitmap? {
        val domain = try { java.net.URI(webApp.baseUrl).host } catch (e: Exception) { null }
            ?: return null
        val cacheFile = File(faviconDir(context), "$domain.png")
        // 缓存命中直接读（成功后 persistent；失败标记不存图片，每次重试防新图标）
        if (cacheFile.exists()) {
            val cached = try { BitmapFactory.decodeFile(cacheFile.absolutePath) } catch (e: Exception) { null }
            if (cached != null) return cached
            // 缓存文件损坏：删掉重拉
            cacheFile.delete()
        }
        // 拉取候选（多源冗余，国内/代理环境至少一个可达）：
        // 1. 站点根 /favicon.ico（本地直连，最快）
        // 2. Google s2（境外站兜底，代理环境可达）
        // 3. icon.horse（第三方聚合服务，冗余）
        // 4. DuckDuckGo icons（第三方聚合服务，冗余）
        val candidates = listOf(
            hostFor(webApp.baseUrl),
            "https://www.google.com/s2/favicons?domain=$domain&sz=112",
            "https://icon.horse/icon/$domain",
            "https://icons.duckduckgo.com/ip3/$domain.ico"
        )
        for (url in candidates) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = FAVICON_TIMEOUT_MS
                conn.readTimeout = FAVICON_TIMEOUT_MS
                conn.setRequestProperty("User-Agent", "WebNative-Favicon")
                if (conn.responseCode == 200) {
                    // 先读完整 bytes（流只能读一次），PNG/JPEG 直接解，ICO 用容器解析器
                    val bytes = conn.inputStream.readBytes()
                    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp == null) {
                        bmp = WebAppDataFetcher.decodeIco(bytes)
                    }
                    // 合法性校验：像素过小的异常图（如 1x1 占位）跳过
                    if (bmp != null && bmp.width >= MIN_FAVICON_PX && bmp.height >= MIN_FAVICON_PX) {
                        // 写缓存（cacheDir）+ 持久化 iconPath（filesDir 永久）
                        FileOutputStream(cacheFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 90, out) }
                        saveIcon(context, webApp, bmp)
                        // 持久化 iconPath 到 DataManager（防重启丢失）
                        com.cylonid.nativealpha.model.DataManager.getInstance().saveWebAppData()
                        return bmp
                    }
                }
            } catch (e: Exception) {
                // 尝试下一个候选
            }
        }
        return null
    }

    /**
     * 统一图标解析入口（**唯一展示逻辑**）：
     * 1. iconPath 有值 → 用户头像/网站图标（filesDir 持久化）
     * 2. 无 → 网站 favicon（loadFavicon：直连多候选 + 成功后持久化 iconPath）
     * 3. 仍无 → 字母渐变图标（IconGenerator）
     *
     * 所有 UI（列表/设置页/添加向导预览/快捷方式 fallback）统一调用本方法，
     * 避免各处在各自 fallback 逻辑（此前 4 处 IconGenerator.generate 冗余）。
     * 注：网络拉取在调用方协程执行；本方法同步（失败返回字母图标，不发网络）。
     */
    fun resolveIcon(context: Context, webApp: WebApp): Bitmap {
        loadIcon(context, webApp)?.let { return it }
        // 网络拉取（调用方负责 IO 线程）——成功持久化 iconPath
        loadFavicon(context, webApp)?.let { return it }
        // 字母渐变兜底
        val d = try { java.net.URI(webApp.baseUrl).host } catch (e: Exception) { null }
        return IconGenerator.generate(webApp.title, d, 112, 28)
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
