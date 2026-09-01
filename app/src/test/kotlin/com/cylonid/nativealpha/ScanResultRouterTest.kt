package com.cylonid.nativealpha

import com.cylonid.nativealpha.util.ScanResultRouter
import com.cylonid.nativealpha.util.ScanResultRouter.Action
import com.cylonid.nativealpha.util.SiteShareCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码结果路由穷举：webnative 深链优先（坏链不回落 http）、http/https
 * 直接进页面、空文本静默、其余一切拒绝。
 */
class ScanResultRouterTest {

    /** 空文本/取消 → 静默 */
    @Test
    fun ignore_onBlankOrNull() {
        assertEquals(Action.Ignore, ScanResultRouter.route(null))
        assertEquals(Action.Ignore, ScanResultRouter.route(""))
        assertEquals(Action.Ignore, ScanResultRouter.route("   "))
    }

    /** webnative 有效深链 → 添加站点（URL/名称解出） */
    @Test
    fun addSite_onValidShareLink() {
        val link = SiteShareCodec.buildShareLink("https://example.com", "示例")
        val action = ScanResultRouter.route(link)
        assertTrue(action is Action.AddSite)
        assertEquals("https://example.com", (action as Action.AddSite).url)
        assertEquals("示例", action.name)
    }

    /** webnative 坏链（版本门坏）→ Invalid 而非回落 http（防伪造链接绕过白名单） */
    @Test
    fun invalid_onBrokenShareLink_noHttpFallback() {
        val good = SiteShareCodec.buildShareLink("https://example.com", "S")!!
        assertEquals(Action.Invalid, ScanResultRouter.route(good.replace("v=1", "v=9")))
    }

    /** http/https → 直接进页面 */
    @Test
    fun openPage_onHttpUrls() {
        assertEquals(Action.OpenPage("http://example.com"), ScanResultRouter.route("http://example.com"))
        assertEquals(
            Action.OpenPage("https://news.example.com/a?b=1"),
            ScanResultRouter.route("https://news.example.com/a?b=1")
        )
    }

    /** 非法 URL（无 host/其他 scheme/纯文本）→ Invalid */
    @Test
    fun invalid_onUnrecognized() {
        assertEquals(Action.Invalid, ScanResultRouter.route("hello world"))
        assertEquals(Action.Invalid, ScanResultRouter.route("ftp://example.com"))
        assertEquals(Action.Invalid, ScanResultRouter.route("javascript:alert(1)"))
        assertEquals(Action.Invalid, ScanResultRouter.route("https://"))
        assertEquals(Action.Invalid, ScanResultRouter.route("webnative://other?x=1"))
    }
}
