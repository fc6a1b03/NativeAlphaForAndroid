package com.cylonid.nativealpha.util

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 站点分享链接编解码（借鉴 happier 配对深链的 fail-closed 纪律）。
 *
 * 链接形态：`webnative://add?v=1&u=<encoded>&n=<encoded>`——对端扫码/点开
 * 后进添加向导预填。深链数据视为不可信输入（happier notificationRouting
 * 同款防线）：
 * - 版本门：v 必须等于 [SUPPORTED_VERSION]（未来格式演进拒绝旧解析）
 * - URL 白名单：仅 http/https 且 host 非空；剥离 userinfo（user:pass@
 *   形式的钓鱼注入）；长度上限
 * - 名称净化：截断 + 空白退化为 host
 * - 全程 fail-closed：任何一步不满足返回 null（调用方提示链接无效）
 *
 * 纯 JVM 实现（java.net.URI/URLEncoder），不依赖 android.net.Uri，可直接单测。
 */
object SiteShareCodec {

    const val SCHEME = "webnative"
    const val HOST_ADD = "add"
    const val SUPPORTED_VERSION = "1"
    const val KEY_VERSION = "v"
    const val KEY_URL = "u"
    const val KEY_NAME = "n"

    const val MAX_URL_LENGTH = 2048
    const val MAX_NAME_LENGTH = 50

    /** 解析成功的分享载荷：url 已剥离 userinfo 并回填协议小写 host */
    data class SharedSite(val url: String, val name: String)

    /**
     * 构建分享链接。站点 URL 非法（非 http/https 或无 host）返回 null。
     */
    fun buildShareLink(baseUrl: String, name: String): String? {
        val site = sanitizeTargetUrl(baseUrl) ?: return null
        val encodedUrl = URLEncoder.encode(site, "UTF-8")
        val displayName = name.trim().ifBlank { displayHostFallback(site) }
        val encodedName = URLEncoder.encode(
            displayName.take(MAX_NAME_LENGTH), "UTF-8"
        )
        return "$SCHEME://$HOST_ADD?$KEY_VERSION=$SUPPORTED_VERSION" +
            "&$KEY_URL=$encodedUrl&$KEY_NAME=$encodedName"
    }

    /**
     * 解析分享链接（fail-closed）：版本不识别 / scheme 不符 / URL 非法 /
     * 超长一律返回 null。
     */
    fun parseShareLink(link: String): SharedSite? {
        if (link.length > MAX_URL_LENGTH * 2) return null
        val uri = try {
            URI(link.trim())
        } catch (ignored: Exception) {
            return null
        }
        if (!SCHEME.equals(uri.scheme, ignoreCase = true) ||
            !HOST_ADD.equals(uri.host, ignoreCase = true)
        ) {
            return null
        }
        val params = mutableMapOf<String, String>()
        uri.rawQuery?.split("&")?.forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = pair.substring(0, idx)
                if (!params.containsKey(key)) {
                    params[key] = try {
                        URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    } catch (ignored: Exception) {
                        ""
                    }
                }
            }
        }
        // 版本门：不识别的版本 fail-closed
        if (params[KEY_VERSION] != SUPPORTED_VERSION) return null
        val url = sanitizeTargetUrl(params[KEY_URL] ?: return null) ?: return null
        if (url.length > MAX_URL_LENGTH) return null
        val name = (params[KEY_NAME] ?: "").trim()
            .take(MAX_NAME_LENGTH)
            .ifBlank { displayHostFallback(url) }
        return SharedSite(url, name)
    }

    /**
     * 目标 URL 白名单净化：仅 http/https + host 非空；剥离 userinfo
     * （防 user:pass@evil.com 形式的视觉钓鱼）；返回规范化字符串。
     */
    private fun sanitizeTargetUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val uri = try {
            URI(raw.trim())
        } catch (ignored: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (host.isBlank()) return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.rawPath ?: ""
        val query = if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
        val fragment = if (uri.rawFragment != null) "#${uri.rawFragment}" else ""
        return "$scheme://$host$port$path$query$fragment"
    }

    private fun displayHostFallback(url: String): String = try {
        val uri = URI(url)
        (uri.host ?: url).removePrefix("www.")
    } catch (ignored: Exception) {
        url
    }
}
