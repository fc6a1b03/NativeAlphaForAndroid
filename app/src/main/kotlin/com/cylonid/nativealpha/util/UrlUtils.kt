package com.cylonid.nativealpha.util

import android.net.Uri
import androidx.core.net.toUri
import java.net.URI

/**
 * URL 规范化与校验工具（纯逻辑，便于单元测试）。
 */
object UrlUtils {

    /**
     * 规范化 URL：
     * - 去首尾空白
     * - 空串返回空串
     * - 无协议前缀自动补 https://
     */
    @JvmStatic
    fun normalize(raw: String): String {
        var url = raw.trim()
        if (url.isBlank()) return ""
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            url = "https://$url"
        }
        // host 规范化：统一小写（URL host 不区分大小写，但展示/回填需一致；
        // 避免 Uri.buildUpon 的类型推导问题，纯字符串处理 protocol://Host 段）
        val schemeEnd = url.indexOf("://")
        if (schemeEnd >= 0) {
            val rest = url.substring(schemeEnd + 3)
            val pathStart = rest.indexOf('/')
            val hostPart = if (pathStart >= 0) rest.substring(0, pathStart) else rest
            // host 可能带端口（host:port），仅小写 host 部分
            val portIdx = hostPart.lastIndexOf(':')
            val hostPure = if (portIdx > 0) hostPart.substring(0, portIdx) else hostPart
            val portSuffix = if (portIdx > 0) hostPart.substring(portIdx) else ""
            if (hostPure.isNotEmpty()) {
                return url.substring(0, schemeEnd + 3) + hostPure.lowercase() + portSuffix +
                        (if (pathStart >= 0) rest.substring(pathStart) else "")
            }
        }
        return url
    }

    /**
     * 校验 URL 是否合法：非空 + 可解析 + 有 host + 无空格。
     * @return null 表示合法，否则返回错误信息
     */
    @JvmStatic
    fun validate(raw: String): String? {
        val url = normalize(raw)
        if (url.isBlank()) return "empty"
        val uri = runCatching { url.toUri() }.getOrNull()
        val host = uri?.host
        if (host.isNullOrBlank() || host.contains(" ")) {
            return "invalid"
        }
        return null
    }

    /** 提取 URL 的 host（失败返回 null） */
    @JvmStatic
    fun hostOf(url: String): String? {
        return runCatching { url.toUri().host }.getOrNull()
    }

    /**
     * 名称兜底：去协议/路径后的 host（保留端口，去 www. 前缀；解析失败退回去协议串）。
     * 用于标题抓取失败时的默认名称（如 linux.do/t/topic/123 → linux.do）。
     */
    @JvmStatic
    fun displayHost(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host
        if (!host.isNullOrBlank()) {
            val port = if (uri.port > 0) ":${uri.port}" else ""
            return host.removePrefix("www.") + port
        }
        return url.replace("http://", "").replace("https://", "").replace("www.", "")
    }

    // ===== 本地/私网地址判定（Android 17 本地网络权限场景化申请依据） =====

    /**
     * 判断 URL 是否指向本地/私网地址。纯字符串判定——不做 DNS 解析
     * （[java.net.InetAddress] 解析会触发网络查询，禁止在主线程判定路径引入）。
     *
     * 覆盖：IPv4 环回/RFC1918 私网（10/172.16-31/192.168）/链路本地 169.254/
     * 运营商级 NAT 100.64/10、localhost 主机名、mDNS `.local` 后缀、
     * IPv6 环回(::1)/ULA fc00::/7/链路本地 fe80::/10。
     * 公网域名不在判定内（即使其解析结果落内网——向导场景 URL 形态是字面的）。
     */
    @JvmStatic
    fun isPrivateNetworkHost(url: String): Boolean {
        val host = runCatching { URI(url).host }
            .getOrNull()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return false
        if (host.contains(':')) {
            // IPv6 字面地址：java.net.URI 保留 [] 括号，统一去除后按前缀分段判定
            val raw = host.trim('[', ']')
            return raw == "::1" ||
                // ULA fc00::/7 = fc/fd 两个首 nibble 区段
                raw.startsWith("fc") || raw.startsWith("fd") ||
                // 链路本地 fe80::/10 = fe8-feb 首三个字符区段
                raw.startsWith("fe8") || raw.startsWith("fe9") ||
                raw.startsWith("fea") || raw.startsWith("feb")
        }
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
            return true
        }
        // IPv4 字面地址：按点分段数值判定（字符串前缀匹配会误伤 10.x vs 100.x）
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() }
        if (octets.any { it == null || it < 0 || it > 255 }) return false
        val a = octets[0] ?: return false
        val b = octets[1] ?: return false
        return a == 10 || a == 127 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            (a == 100 && b in 64..127)
    }

    // ===== 追踪参数剥离（白名单制） =====

    /** 公认的纯追踪参数名（精确匹配，小写）——功能性参数（token/session/路由）永不在此列 */
    private val TRACKING_PARAM_KEYS = setOf(
        "fbclid",      // Facebook/Meta
        "gclid",       // Google Ads
        "msclkid",     // Microsoft Ads
        "igshid",      // Instagram
        "spm",         // 阿里系埋点
        "si",          // YouTube 分享追踪
        "tt_from",     // 字节系分享来源
        "share_source",
        "share_medium"
    )

    /** 追踪参数名前缀（如 utm_source/utm_campaign 全族） */
    private val TRACKING_PARAM_PREFIXES = listOf("utm_")

    /**
     * 剥离追踪 query 参数（白名单制，任意网站通用）：
     * 仅移除公认的广告/埋点参数（utm_* 全族/fbclid/gclid 等），其余一律保留——
     * 功能性参数（token/sessionId/路由）不受影响。
     * 字符串级处理不做重编码（保持原 URL 的转义形式）；fragment 锚点保留；
     * 无参数或无可剥项时原样返回。
     */
    @JvmStatic
    fun stripTrackingParams(url: String): String {
        val qStart = url.indexOf('?')
        if (qStart < 0) return url
        val qEnd = url.indexOf('#', qStart).let { if (it < 0) url.length else it }
        val query = url.substring(qStart + 1, qEnd)
        val kept = query.split('&').filter { pair ->
            if (pair.isEmpty()) return@filter true
            val rawKey = pair.substringBefore('=')
            val key = runCatching { java.net.URLDecoder.decode(rawKey, "UTF-8") }
                .getOrDefault(rawKey).lowercase().trim()
            val isTracking = key in TRACKING_PARAM_KEYS ||
                TRACKING_PARAM_PREFIXES.any { key.startsWith(it) }
            !isTracking
        }
        val newQuery = kept.joinToString("&")
        return when {
            newQuery == query -> url
            newQuery.isEmpty() -> url.substring(0, qStart) + url.substring(qEnd)
            else -> url.substring(0, qStart + 1) + newQuery + url.substring(qEnd)
        }
    }
}
