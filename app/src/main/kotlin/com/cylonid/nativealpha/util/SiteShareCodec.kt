package com.cylonid.nativealpha.util

import com.cylonid.nativealpha.model.WebApp
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 站点分享链接编解码（借鉴 happier 配对深链的 fail-closed 纪律）。
 *
 * v2（当前）：`webnative://add?v=2&u=<url>&n=<name>&d=<base64url(cfg)>`
 * —— d 为「设置差异」JSON（WebApp 全字段 Gson 序列化后：剔除身份/统计/
 * 快捷键/图标运行时字段，再剔除与默认实例相同的键）。**排除法保证新
 * 增设置字段自动进分享**（Gson 反射全覆盖），后续版本无需追改编解码；
 * 差异剔除保证 QR 密度最低（仅带非默认值，接收端默认构造兜底）。
 * v1（仅 u/n，无 d）向后兼容解析：无 d 时按纯默认设置。
 *
 * 深链数据视为不可信输入（happier notificationRouting 同款防线）：
 * - 版本门：v 必须是 [SUPPORTED_VERSION]（未来格式演进拒绝旧解析）
 * - URL 白名单：仅 http/https 且 host 非空；剥离 userinfo（user:pass@
 *   形式的钓鱼注入）；长度上限
 * - 名称净化：截断 + 空白退化为 host
 * - cfg 损坏 fail-safe：丢弃配置仅按 URL/名称添加，不阻断
 *
 * 纯 JVM 实现（java.net.URI/URLEncoder），不依赖 android.net.Uri，可直接单测。
 */
object SiteShareCodec {

    const val SCHEME = "webnative"
    const val HOST_ADD = "add"
    const val SUPPORTED_VERSION = "2"
    const val LEGACY_VERSION = "1"
    const val KEY_VERSION = "v"
    const val KEY_URL = "u"
    const val KEY_NAME = "n"
    const val KEY_CONFIG = "d"

    const val MAX_URL_LENGTH = 2048
    const val MAX_NAME_LENGTH = 50
    const val MAX_CONFIG_LENGTH = 4096

    /** 解析成功的分享载荷：url 已剥离 userinfo；cfg 为设置差异 JSON（可空） */
    data class SharedSite(val url: String, val name: String, val configJson: String?)

    /** 设置差异编码时剔除的字段：身份/布局/统计/快捷键/图标（运行时与接收端重建值）。
     *  约定：WebApp 新增「设置类」字段默认自动携带；新增「运行时类」字段须加入此名单 */
    private val EXCLUDED_CONFIG_KEYS = setOf(
        "ID", "order", "isActiveEntry", "title", "iconPath", "legacyDisplayName",
        "statLaunches", "statLoadTimeSum", "statLoadTimeCount", "statMaxLoadTime",
        "statCacheHttpBytes", "statCacheStoreBytes", "statErrors", "statLastError",
        "statFirstLoadedAt", "statLastUsedAt", "statLoadTimes",
        "keyShortcuts", "keyShortcutSendCounts"
    )

    private val gson = Gson()

    /**
     * 构建分享链接（v2）。站点 URL 非法（非 http/https 或无 host）返回 null。
     * config 传 WebApp 即携带全部设置差异（推荐，主页/设置页入口都传）。
     */
    fun buildShareLink(baseUrl: String, name: String, config: WebApp? = null): String? {
        val site = sanitizeTargetUrl(baseUrl) ?: return null
        val encodedUrl = URLEncoder.encode(site, "UTF-8")
        val displayName = name.trim().ifBlank { displayHostFallback(site) }
        val encodedName = URLEncoder.encode(
            displayName.take(MAX_NAME_LENGTH), "UTF-8"
        )
        val cfgPart = config?.let {
            val diff = encodeConfigDiff(it)
            if (diff.isNotEmpty()) "&$KEY_CONFIG=" + URLEncoder.encode(diff, "UTF-8") else ""
        } ?: ""
        return "$SCHEME://$HOST_ADD?$KEY_VERSION=$SUPPORTED_VERSION" +
            "&$KEY_URL=$encodedUrl&$KEY_NAME=$encodedName$cfgPart"
    }

    /**
     * 解析分享链接（fail-closed）：版本不识别 / scheme 不符 / URL 非法 /
     * 超长一律返回 null。cfg 损坏仅丢弃配置（降级为默认设置），不拒整链。
     */
    fun parseShareLink(link: String): SharedSite? {
        if (link.length > MAX_URL_LENGTH * 4) return null
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
        // 版本门：v2 当前格式；v1 遗留链接（无 cfg）兼容解析；
        // 其余版本 fail-closed（不识别的未来格式拒绝，防误读）
        val version = params[KEY_VERSION]
        if (version != SUPPORTED_VERSION && version != LEGACY_VERSION) return null
        val url = sanitizeTargetUrl(params[KEY_URL] ?: return null) ?: return null
        if (url.length > MAX_URL_LENGTH) return null
        val name = (params[KEY_NAME] ?: "").trim()
            .take(MAX_NAME_LENGTH)
            .ifBlank { displayHostFallback(url) }
        // cfg：缺失=纯默认设置；超长=丢弃配置（不阻断添加，用户可手动重配）。
        // 原样透传差异 JSON 字符串，接收端经 decodeConfigDiff 还原（fail-safe）
        val cfg = params[KEY_CONFIG]
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_CONFIG_LENGTH }
        return SharedSite(url, name, cfg)
    }

    /** 设置差异 JSON：全字段 Gson 树 - 排除名单 - 与默认实例同值键 */
    private fun encodeConfigDiff(config: WebApp): String {
        val full = gson.toJsonTree(config).asJsonObject
        val defaults = gson.toJsonTree(WebApp(config.baseUrl, config.ID)).asJsonObject
        val diff = JsonObject()
        full.entrySet().forEach { (key, value) ->
            if (key in EXCLUDED_CONFIG_KEYS) return@forEach
            if (defaults.get(key) == value) return@forEach
            diff.add(key, value)
        }
        // 无差异返回空串：调用方字符串 isNotEmpty 判定恰好正确（QR 省 d 字段）
        return if (diff.size() == 0) "" else diff.toString()
    }

    /** 差异 JSON 还原为部分填充的 WebApp（仅设置字段有值）；损坏返回 null */
    fun decodeConfigDiff(raw: String?): WebApp? {
        if (raw.isNullOrBlank()) return null
        return try {
            gson.fromJson(raw, WebApp::class.java)
        } catch (ignored: Exception) {
            null
        }
    }

    /** 目标 URL 白名单净化：仅 http/https + host 非空；剥离 userinfo
     * （防 user:pass@evil.com 形式的视觉钓鱼）；返回规范化字符串 */
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
