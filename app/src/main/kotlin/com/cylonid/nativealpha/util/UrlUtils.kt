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
