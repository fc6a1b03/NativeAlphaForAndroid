package com.cylonid.nativealpha.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * WebApp 头像唯一管理入口（统一数据源 + 图标能力唯一实现处，调用方只编排）。
 *
 * 数据源：WebApp.iconPath 记录 App 文件目录（files/icons/webapp_N.png）里
 * 的头像文件路径；null = 用字母渐变图标（IconGenerator）。
 *
 * 能力分层：
 * - saveIcon(webApp, bitmap)   → 存文件 + 更新 webApp.iconPath（唯一写入点）
 * - loadIcon(webApp)           → 按 iconPath 读 bitmap（唯一读取点）
 * - deleteIcon(webApp)         → 删文件 + iconPath=null（唯一清除点）
 * - fetchFavicon(baseUrl)      → 纯拉取（候选源+HTML 解析，不持久化；IO 线程）
 * - loadFavicon(webApp)        → 缓存 + fetchFavicon + 持久化 iconPath（IO 线程）
 * - resolveIcon(webApp)        → iconPath→favicon→字母（含网络，IO 线程）
 * - resolveIconCached(webApp)  → iconPath→字母（无网络，UI 线程安全）
 *
 * 注意：**不重复存储**——系统快捷方式图标由 ShortcutManager 持有，
 * App 内统一只通过 iconPath 引用（不做第二份拷贝）。
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
            Log.w(TAG, "saveIcon failed [webapp_${webApp.ID}]: ${e.message}")
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
                    DataManager.getInstance().saveWebAppData()
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
        URI(baseUrl).let { uri ->
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$port/favicon.ico"
        }
    } catch (e: Exception) {
        "$baseUrl/favicon.ico"
    }

    /** favicon 最小边长（px）：过滤 1x1 占位符等异常图 */
    private const val MIN_FAVICON_PX = 16

    /** 日志 TAG（图标链路排查用：logcat -s IconMgr） */
    private const val TAG = "IconMgr"

    /** 字母兜底图标尺寸（px）/ 圆角（px） */
    private const val LETTER_ICON_PX = 112
    private const val LETTER_ICON_RADIUS_PX = 28

    /** favicon 网络超时（ms） */
    private const val FAVICON_TIMEOUT_MS = 4000

    /** 网站 favicon 缓存目录（列表图标 fallback：无自定义头像时显示网站图标） */
    private fun faviconDir(context: Context): File =
        File(context.cacheDir, "favicons").apply { if (!exists()) mkdirs() }

    /**
     * 加载/拉取网站 favicon（列表图标用）：优先缓存；无则 [fetchFavicon] 拉取。
     * 网络在调用方协程/线程处理（本方法同步执行，需 IO 上下文）。
     * 一旦拉取成功：**持久化到 iconPath（filesDir 永久存储）+ 缓存**——
     * 列表图标稳定显示（不再依赖 cacheDir 易被系统清理，重启不丢）。
     * 失败返回 null（调用方 fallback 字母图标）。
     */
    fun loadFavicon(context: Context, webApp: WebApp): Bitmap? {
        val domain = UrlUtils.hostOf(webApp.baseUrl) ?: return null
        val cacheFile = File(faviconDir(context), "$domain.png")
        // 缓存命中直接读（成功后 persistent；失败标记不存图片，每次重试防新图标）
        if (cacheFile.exists()) {
            val cached = try { BitmapFactory.decodeFile(cacheFile.absolutePath) } catch (e: Exception) { null }
            if (cached != null) {
                Log.d(TAG, "cache hit [$domain]")
                return cached
            }
            // 缓存文件损坏：删掉重拉
            cacheFile.delete()
        }
        val bmp = fetchFavicon(webApp.baseUrl) ?: return null
        return persistFavicon(context, webApp, cacheFile, bmp)
    }

    /**
     * 纯拉取 favicon（**不读缓存、不持久化**——能力唯一实现处）。候选源按序：
     * 1. 站点根 /favicon.ico（本地直连，最快）
     * 2. Google s2（境外站兜底，代理环境可达）
     * 3. icon.horse（第三方聚合服务，冗余）
     * 4. DuckDuckGo icons（第三方聚合服务，冗余）
     * 5. HTML <link rel=icon> 解析（IP/自托管站唯一来源——第三方服务不收录 IP，
     *    站点根 /favicon.ico 也常不存在；与创建流程同源 WebAppDataFetcher）
     *
     * 临时场景（创建向导预览等 WebApp 未持久化时）用本方法——切勿用临时 WebApp
     * 调 [loadFavicon]（saveIcon 会落 webapp_-1 孤儿文件）。须 IO 线程调用。
     */
    fun fetchFavicon(baseUrl: String): Bitmap? {
        val domain = UrlUtils.hostOf(baseUrl) ?: return null
        val candidates = listOf(
            hostFor(baseUrl),
            "https://www.google.com/s2/favicons?domain=$domain&sz=112",
            "https://icon.horse/icon/$domain",
            "https://icons.duckduckgo.com/ip3/$domain.ico"
        )
        for (url in candidates) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
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
                    if (isUsableFavicon(bmp)) {
                        Log.d(TAG, "favicon ok [$domain] <- $url")
                        return bmp
                    }
                    Log.d(TAG, "candidate unusable [$url] (too small/undecodable)")
                } else {
                    Log.d(TAG, "candidate http ${conn.responseCode} [$url]")
                }
            } catch (e: Exception) {
                Log.d(TAG, "candidate fail [$url]: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        try {
            val meta = WebAppDataFetcher.fetch(baseUrl)
            val bmp = WebAppDataFetcher.loadBitmap(meta.faviconUrl)
            if (isUsableFavicon(bmp)) {
                Log.d(TAG, "favicon ok [$domain] <- html parse")
                return bmp
            }
        } catch (e: Exception) {
            Log.d(TAG, "html parse fail [$domain]: ${e.javaClass.simpleName}")
        }
        Log.d(TAG, "favicon MISS [$domain] all sources failed")
        return null
    }

    /** favicon 合法性：像素过小的异常图（如 1x1 占位）不可用 */
    private fun isUsableFavicon(bmp: Bitmap?): Boolean =
        bmp != null && bmp.width >= MIN_FAVICON_PX && bmp.height >= MIN_FAVICON_PX

    /** favicon 落盘：写缓存（cacheDir）+ 持久化 iconPath（filesDir 永久）+ 保存数据（防重启丢失） */
    private fun persistFavicon(context: Context, webApp: WebApp, cacheFile: File, bmp: Bitmap): Bitmap {
        FileOutputStream(cacheFile).use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 90, out) }
        saveIcon(context, webApp, bmp)
        DataManager.getInstance().saveWebAppData()
        return bmp
    }

    /**
     * 统一图标解析入口（**唯一展示逻辑**）：
     * 1. iconPath 有值 → 用户头像/网站图标（filesDir 持久化）
     * 2. 无 → 网站 favicon（loadFavicon：直连多候选 + 成功后持久化 iconPath）
     * 3. 仍无 → 字母渐变图标（IconGenerator）
     *
     * 列表等 IO 上下文统一调用本方法（**含网络，禁止 UI 线程调用**——
     * 候选源超时累计可达 16s，UI 线程请用 [resolveIconCached]）。
     */
    fun resolveIcon(context: Context, webApp: WebApp): Bitmap {
        loadIcon(context, webApp)?.let { return it }
        // 网络拉取（调用方负责 IO 线程）——成功持久化 iconPath
        loadFavicon(context, webApp)?.let { return it }
        // 字母渐变兜底
        return resolveIconCached(context, webApp)
    }

    /**
     * 本地图标解析（**无网络，UI 线程安全**）：
     * 1. iconPath 有值 → 持久化头像/网站图标
     * 2. 无 → 字母渐变图标（IconGenerator）
     *
     * UI 线程调用点（快捷方式创建/更新、设置页/弹窗预览）统一用本方法；
     * favicon 网络补拉只走 [resolveIcon]（IO 线程）。
     */
    fun resolveIconCached(context: Context, webApp: WebApp): Bitmap {
        loadIcon(context, webApp)?.let { return it }
        return IconGenerator.generate(
            webApp.title,
            UrlUtils.hostOf(webApp.baseUrl),
            LETTER_ICON_PX,
            LETTER_ICON_RADIUS_PX
        )
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
