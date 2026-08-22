package com.cylonid.nativealpha.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.HttpURLConnection
import java.net.URL
import java.util.TreeMap
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Web App 元数据抓取器：从站点提取标题 / 图标 / PWA start_url。
 *
 * 从原 ShortcutDialogFragment 的抓取逻辑抽取，供「添加向导」与
 * 「快捷方式重建」复用，避免两处重复实现。
 *
 * 抓取顺序：
 * 1. META refresh 重定向（跟随）
 * 2. PWA manifest（name / start_url / icons 优先）
 * 3. 兜底 HTML <title> + <link rel=icon/apple-touch-icon>
 *
 * 所有网络操作必须在后台线程执行。
 */
object WebAppDataFetcher {

    data class Result(
        val faviconUrl: String? = null,
        val title: String? = null,
        val newBaseUrl: String? = null
    )

    /** 站点特例图标映射（域名 → 官方图标 URL） */
    private fun buildKnownIconMap(baseUrl: String): TreeMap<Int, String> {
        val foundIcons = TreeMap<Int, String>()
        if (baseUrl.isBlank()) return foundIcons
        val host = baseUrl.replace("http://", "").replace("https://", "")
            .replace("www.", "")

        if (host.startsWith("amazon."))
            foundIcons[300] = "https://upload.wikimedia.org/wikipedia/commons/d/de/Amazon_icon.png"
        if (host.startsWith("paypal."))
            foundIcons[196] = "https://www.paypalobjects.com/webstatic/icon/pp196.png"
        if (host.startsWith("google."))
            foundIcons[240] = "https://www.gstatic.com/images/branding/googleg/2x/googleg_standard_color_120dp.png"

        // 尺寸不符合常规的站点特例
        if (host.startsWith("anchor.fm"))
            foundIcons[Int.MAX_VALUE] = "https://d12xoj7p9moygp.cloudfront.net/favicon/apple-touch-icon-wave-152x152.png"
        if (host.startsWith("oebb.at"))
            foundIcons[Int.MAX_VALUE] = "https://www.oebb.at/.resources/pv-2017/themes/images/favicons/android-chrome-192x192.png"
        if (host.startsWith("oe3.orf.at"))
            foundIcons[Int.MAX_VALUE] = "https://tubestatic.orf.at/mojo/1_3/storyserver//tube/common/images/apple-icons/oe3.png"

        return foundIcons
    }

    /**
     * 判断标题是否为"挑战页/占位页脏标题"（Cloudflare "Just a moment..."、
     * 安全验证页等）。此类标题不应该被当成站点真实名称回填——抓取失败时
     * 宁可不填（保持用户输入），也不要硬塞英文挑战文案。
     */
    @JvmStatic
    fun isChallengeTitle(title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty()) return false
        // "Just a moment..."：Cloudflare 反爬挑战页的标准标题
        return t.equals("Just a moment...", ignoreCase = true) ||
                t.equals("Just a moment", ignoreCase = true) ||
                t.startsWith("Attention Required", ignoreCase = true) ||
                t.startsWith("Access Denied", ignoreCase = true) ||
                t.startsWith("Access denied", ignoreCase = true) ||
                t.startsWith("One more step", ignoreCase = true) ||
                t.startsWith("请稍候", ignoreCase = true) ||
                t.startsWith("验证", ignoreCase = true) ||
                t.startsWith("安全验证", ignoreCase = true) ||
                t.startsWith("人机验证", ignoreCase = true) ||
                // 常见的简短占位：无关内容无意义标题
                t.length <= 3 && (t == "404" || t == "403" || t == "502" || t == "503")
    }

    /** 抓取站点元数据；所有 IO 在调用线程执行（调用方负责切后台线程） */
    @JvmStatic
    fun fetch(baseUrl: String, userAgent: String = Const.DESKTOP_USER_AGENT): Result {
        var currentUrl = baseUrl
        val foundIcons = buildKnownIconMap(currentUrl)
        var title: String? = null
        var newBaseUrl: String? = null

        try {
            var doc = Jsoup.connect(currentUrl)
                .ignoreHttpErrors(true)
                .userAgent(userAgent)
                .header("Accept-Language", LocaleUtils.acceptLanguage)
                .followRedirects(true)
                .timeout(CONNECT_TIMEOUT_MS)
                .get()

            // Step 1: META refresh 重定向
            val metaTags = doc.select("meta[http-equiv=refresh]")
            if (metaTags.isNotEmpty()) {
                val content = metaTags.first()!!.attr("content")
                val pattern = Pattern.compile(".*URL='?(.*)$", Pattern.CASE_INSENSITIVE)
                val m = pattern.matcher(content)
                val redirectUrl = if (m.matches()) m.group(1) else null
                if (redirectUrl != null) {
                    currentUrl = redirectUrl
                    doc = Jsoup.connect(currentUrl).followRedirects(true).timeout(CONNECT_TIMEOUT_MS).get()
                }
            }

            // Step 2: PWA manifest
            val manifest = doc.select("link[rel=manifest]")
            if (manifest.isNotEmpty()) {
                val mf = manifest.first()!!
                val data = Jsoup.connect(mf.absUrl("href"))
                    .ignoreContentType(true).timeout(CONNECT_TIMEOUT_MS).execute().body()
                val json = JSONObject(data)

                try {
                    val manifestName = json.getString("name")
                    if (manifestName.isNotBlank()) title = manifestName
                    val startUrl = json.getString("start_url")
                    if (startUrl.isNotEmpty()) {
                        val base = URL(mf.absUrl("href"))
                        val full = URL(base, startUrl)
                        newBaseUrl = full.toString()
                    }
                } catch (_: Exception) {
                    // 部分 manifest 缺字段，忽略
                }
                try {
                    val manifestIcons = json.getJSONArray("icons")
                    for (i in 0 until manifestIcons.length()) {
                        val icon = manifestIcons.getJSONObject(i)
                        val iconHref = icon.getString("src")
                        val sizes = icon.getString("sizes")
                        val width = ShortcutIconUtils.getWidthFromIcon(sizes)
                        val base = URL(mf.absUrl("href"))
                        val fullUrl = URL(base, iconHref)
                        foundIcons[width] = fullUrl.toString()
                    }
                } catch (_: Exception) {
                    // manifest 无 icons 字段，走兜底
                }
            }

            // Step 3: 兜底 HTML title + icon 链接
            if (foundIcons.isEmpty() || title == null) {
                val htmlTitle = doc.select("title")
                if (htmlTitle.isNotEmpty() && title == null)
                    title = htmlTitle.first()!!.text()

                if (foundIcons.isEmpty()) {
                    val icons = Elements()
                    icons.addAll(doc.select("link[rel=icon]"))
                    icons.addAll(doc.select("link[rel=shortcut icon]"))
                    if (icons.size < 3) {
                        icons.addAll(doc.select("link[rel=apple-touch-icon]"))
                        icons.addAll(doc.select("link[rel=apple-touch-icon-precomposed]"))
                    }
                    for (icon in icons) {
                        val iconHref = icon.absUrl("href")
                        val sizes = icon.attr("sizes")
                        val width = if (sizes.isNotEmpty())
                            ShortcutIconUtils.getWidthFromIcon(sizes)
                        else 1
                        if (isUsableIconUrl(iconHref)) {
                            foundIcons[width] = iconHref
                        }
                    }
                }
            }

            // Step 4: 终极兜底 —— 标准 /favicon.ico
            // 部分站点（如 fanyi.baidu.com）的图标经 JS 动态注入，静态解析拿不到，
            // 直接探测站点根路径的 favicon.ico（最标准的约定路径）。
            if (foundIcons.isEmpty()) {
                val rootUrl = try {
                    URL(currentUrl).let { URL(it.protocol, it.host, "/favicon.ico") }
                } catch (_: Exception) {
                    null
                }
                if (rootUrl != null) {
                    try {
                        val con = rootUrl.openConnection() as HttpURLConnection
                        try {
                            con.requestMethod = "HEAD"
                            con.connectTimeout = 4000
                            con.readTimeout = 4000
                            if (con.responseCode == HttpURLConnection.HTTP_OK) {
                                foundIcons[8] = rootUrl.toString() // 16x16 以下权重最低，仅作兜底
                            }
                        } finally {
                            con.disconnect()
                        }
                    } catch (_: Exception) {
                        // 站点无 favicon.ico，保持空
                    }
                }
            }
        } catch (e: Exception) {
            // 网络失败：返回已收集的部分数据（可能全空）
        }

        val faviconUrl = if (foundIcons.isNotEmpty()) foundIcons.lastEntry().value else null
        return Result(
            faviconUrl = faviconUrl,
            title = title,
            newBaseUrl = newBaseUrl
        )
    }

    /**
     * 从 URL 加载站点图标（网络操作，须在后台线程）。
     *
     * 兼容多种格式：
     * - PNG / JPEG / WebP：BitmapFactory 直接解码
     * - ICO：Android 不支持，解析 ICO 容器提取内嵌 PNG/BMP
     *   （现代网站 favicon.ico 多为 PNG 压缩，如 fanyi.baidu.com）
     * - 尺寸门槛放宽到 16px（原 96px 会拒绝 32x32 的常见 favicon）
     */
    @JvmStatic
    fun loadBitmap(strUrl: String?): Bitmap? {
        if (strUrl.isNullOrBlank()) return null
        return try {
            val url = URL(strUrl)
            val con = url.openConnection() as HttpURLConnection
            try {
                con.connectTimeout = CONNECT_TIMEOUT_MS
                con.readTimeout = CONNECT_TIMEOUT_MS
                val bytes = con.inputStream.readBytes()
                var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    bitmap = decodeIco(bytes)
                }
                if (bitmap != null && bitmap.width < MIN_ACCEPTABLE_WIDTH) null else bitmap
            } finally {
                con.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 网络连接/读取超时（ms）。挑战站/无响应站快速失败，避免添加流程卡死 */
    private const val CONNECT_TIMEOUT_MS = 10000

    /** 可接受的最小图标宽度（px）。16x16 是 HTML 规范 favicon 下限。 */
    private const val MIN_ACCEPTABLE_WIDTH = 16

    /**
     * 解析 ICO 容器，提取其中最大尺寸的内嵌图像（PNG/JPEG/BMP）。
     * 现代 ICO 的 PNG 压缩条目可直接被 BitmapFactory 解码。
     *
     * ICO 格式：
     *   0-3   reserved + type (00 00 01 00)
     *   4-5   image count (LE)
     *   6+    16-byte entries: w, h, colors, reserved, planes, bpp,
     *         bytesInRes(4, LE), imageOffset(4, LE)
     */
    @JvmStatic
    internal fun decodeIco(data: ByteArray): Bitmap? {
        if (data.size < 6) return null
        // 非 ICO 头直接放弃
        if (data[0] != 0.toByte() || data[1] != 0.toByte() ||
            data[2] != 1.toByte() || data[3] != 0.toByte()) return null

        val count = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)
        if (count <= 0 || count > 30) return null

        // 选最大宽度的条目（0 表示 256）
        var bestIndex = -1
        var bestWidth = 0
        for (i in 0 until count) {
            val off = 6 + i * 16
            if (off + 16 > data.size) break
            val rawW = data[off].toInt() and 0xFF
            val width = if (rawW == 0) 256 else rawW
            if (width > bestWidth) {
                bestWidth = width
                bestIndex = i
            }
        }
        if (bestIndex < 0) return null

        val off = 6 + bestIndex * 16
        val imgOffset = (data[off + 8].toInt() and 0xFF) or
                ((data[off + 9].toInt() and 0xFF) shl 8) or
                ((data[off + 10].toInt() and 0xFF) shl 16) or
                ((data[off + 11].toInt() and 0xFF) shl 24)
        val imgSize = (data[off + 12].toInt() and 0xFF) or
                ((data[off + 13].toInt() and 0xFF) shl 8) or
                ((data[off + 14].toInt() and 0xFF) shl 16) or
                ((data[off + 15].toInt() and 0xFF) shl 24)
        if (imgOffset < 0 || imgSize <= 0 || imgOffset + imgSize > data.size) return null

        val imgBytes = data.copyOfRange(imgOffset, imgOffset + imgSize)
        // 内嵌图像：PNG(89 50 4E 47) / JPEG(FF D8 FF) / BMP(42 4D) 均可直接解码；
        // 老式 ICO 的 AND 掩码位图（DIB）无文件头，此场景已极少，忽略
        return BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
    }

    /**
     * 判断图标 URL 是否可下载：
     * - 非空
     * - http/https（排除 data:、javascript: 等空图标声明，如 example.com 的 `data:,`）
     */
    @JvmStatic
    internal fun isUsableIconUrl(url: String?): Boolean {
        return !url.isNullOrBlank() && url.startsWith("http")
    }
}
