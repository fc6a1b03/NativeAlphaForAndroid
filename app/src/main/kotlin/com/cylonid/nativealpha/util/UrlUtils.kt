package com.cylonid.nativealpha.util

import android.net.Uri

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
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val host = uri?.host
        if (host.isNullOrBlank() || host.contains(" ")) {
            return "invalid"
        }
        return null
    }

    /** 提取 URL 的 host（失败返回 null） */
    @JvmStatic
    fun hostOf(url: String): String? {
        return runCatching { Uri.parse(url).host }.getOrNull()
    }
}
