package com.cylonid.nativealpha.util

import java.net.URI

/**
 * 扫码结果路由（纯函数可单测，C-扫码）：
 * - [Action.Ignore]：空文本/取消扫描——静默
 * - [Action.AddSite]：`webnative://add` 分享深链（复用 SiteShareCodec
 *   fail-closed 校验，非法降级 [Action.Invalid]）→ 进添加向导预填
 * - [Action.OpenPage]：http/https → 直接进页面（负 ID 瞬态浏览，不注册）
 * - [Action.Invalid]：其余一切不可识别内容 → 提示链接无效
 *
 * 判定顺序即优先级：webnative 协议优先于通用 http（webnative 解析失败
 * 不回落 http——防伪造链接把「坏的应用分享」当普通网页打开）。
 */
object ScanResultRouter {

    sealed interface Action {
        data object Ignore : Action
        data class AddSite(val url: String, val name: String) : Action
        data class OpenPage(val url: String) : Action
        data object Invalid : Action
    }

    fun route(raw: String?): Action {
        val text = raw?.trim() ?: return Action.Ignore
        if (text.isEmpty()) return Action.Ignore
        if (text.startsWith(SiteShareCodec.SCHEME + "://", ignoreCase = true)) {
            val parsed = SiteShareCodec.parseShareLink(text) ?: return Action.Invalid
            return Action.AddSite(parsed.url, parsed.name)
        }
        val uri = try {
            URI(text)
        } catch (ignored: Exception) {
            return Action.Invalid
        }
        val scheme = uri.scheme?.lowercase()
        if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) {
            return Action.OpenPage(text)
        }
        return Action.Invalid
    }
}
