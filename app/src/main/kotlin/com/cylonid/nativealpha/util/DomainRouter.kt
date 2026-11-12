package com.cylonid.nativealpha.util

import com.cylonid.nativealpha.model.WebApp
import java.net.URI

/**
 * 域名路由（默认浏览器接管的核心判定，纯函数可单测）：
 * WebNative 声明处理 http/https 链接后，点击进来（邮件/笔记/其他浏览器）
 * 的 URL 按 host 匹配到已注册 WebApp——命中则在对应 WebApp 内打开该链接
 * （保留完整路径：点具体文章就开具体文章，不是只开首页）；未命中则走
 * 瞬态浏览（负 ID 临时页，浏览器兜底角色）。
 *
 * 匹配语义：
 * - host 精确相等，或 URL host 是 baseUrl host 的子域（`endsWith(".host")`）——
 *   baseUrl=`zhihu.com` 吃下 `www.zhihu.com`；反向不成立（更具体的
 *   baseUrl 优先，见多命中规则）。
 * - 多命中取 baseUrlHost 最长者（`www.zhihu.com` 优先于 `zhihu.com`——
 *   配置更具体的应用接管）。
 * - 端口不参与匹配（host 级路由；同站不同端口视为同站）。
 * - baseUrl 非法（用户手填坏数据）→ 跳过该应用，不影响其余匹配。
 *
 * 判定顺序：http/https 且 host 非空才路由（其余一律 [Action.Invalid]），
 * 与 manifest 的 intent-filter 声明口径一致（系统只会发这两类，防御性复验）。
 */
object DomainRouter {

    sealed interface Action {
        /** 命中已注册 WebApp：在该应用内打开 [url]（非 baseUrl） */
        data class OpenInWebApp(val webApp: WebApp, val url: String) : Action
        /** 未命中：瞬态浏览（不注册、统计零写入） */
        data class TransientBrowse(val url: String) : Action
        /** 非 http/https 或无 host：提示无效 */
        data object Invalid : Action
    }

    /** 路由入口。url 已假定去过追踪参数由调用方按需处理（本层不改写） */
    fun route(url: String?, webApps: List<WebApp>): Action {
        if (url.isNullOrBlank()) return Action.Invalid
        val uri = try {
            URI(url)
        } catch (ignored: Exception) {
            return Action.Invalid
        }
        val host = uri.host?.lowercase()
        if ((uri.scheme?.lowercase() != "http" && uri.scheme?.lowercase() != "https") ||
            host.isNullOrBlank()
        ) {
            return Action.Invalid
        }
        val matched = matchHost(host, webApps)
        return if (matched != null) Action.OpenInWebApp(matched, url) else Action.TransientBrowse(url)
    }

    /** host 匹配：精确相等或子域；多命中取 baseUrlHost 最长（更具体的配置接管） */
    fun matchHost(urlHost: String, webApps: List<WebApp>): WebApp? {
        var best: WebApp? = null
        var bestLen = -1
        for (app in webApps) {
            val baseHost = baseHostOf(app.baseUrl) ?: continue
            val isMatch = urlHost == baseHost || urlHost.endsWith(".$baseHost")
            if (isMatch && baseHost.length > bestLen) {
                best = app
                bestLen = baseHost.length
            }
        }
        return best
    }

    /** baseUrl 提取 host（非法/无 host 返回 null）；小写归一 */
    private fun baseHostOf(baseUrl: String): String? = try {
        URI(baseUrl).host?.lowercase()?.takeIf { it.isNotBlank() }
    } catch (ignored: Exception) {
        null
    }
}
