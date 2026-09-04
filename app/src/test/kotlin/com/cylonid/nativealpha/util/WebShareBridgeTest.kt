package com.cylonid.nativealpha.util

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * navigator.share 桥纯函数契约测试：载荷解析与 Intent 组装规则、
 * JS 注入脚本的锚点完整性（脚本与原生回执的字符串契约靠锚点断言锁住）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebShareBridgeTest {

    @Test
    fun buildShareIntent_combinesTextAndUrlWithNewline() {
        val intent = WebShareBridge.buildShareIntent(
            """{"title":"标题","text":"看看这个","url":"https://a.com/x"}"""
        )
        assertEquals("看看这个\nhttps://a.com/x", intent?.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("标题", intent?.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("text/plain", intent?.type)
    }

    @Test
    fun buildShareIntent_dedupesUrlEqualToText() {
        val intent = WebShareBridge.buildShareIntent(
            """{"text":"https://a.com/p","url":"https://a.com/p"}"""
        )
        assertEquals("https://a.com/p", intent?.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun buildShareIntent_trimsPayloadFields() {
        val intent = WebShareBridge.buildShareIntent(
            """{"title":" T ","text":" hello ","url":" https://a.com "}"""
        )
        assertEquals("hello\nhttps://a.com", intent?.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("T", intent?.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun buildShareIntent_returnsNullForInvalidJson() {
        assertNull(WebShareBridge.buildShareIntent("not json"))
    }

    @Test
    fun buildShareIntent_returnsNullForNullOrBlankPayload() {
        assertNull(WebShareBridge.buildShareIntent(null))
        assertNull(WebShareBridge.buildShareIntent("   "))
    }

    @Test
    fun buildShareIntent_returnsNullWhenNoShareBody() {
        // 仅 title 无正文：系统面板空内容无意义，与 JS 侧 can() 防御一致
        assertNull(WebShareBridge.buildShareIntent("""{"title":"only title"}"""))
    }

    @Test
    fun shareOverrideJs_containsContractAnchors() {
        val js = WebShareBridge.SHARE_OVERRIDE_JS
        assertTrue(js.contains("navigator.share=function"))
        assertTrue(js.contains("navigator.canShare=can"))
        assertTrue(js.contains("webnativeShare.postMessage"))
        assertTrue(js.contains("__wnShareInit"))
        assertTrue(js.contains("__wnShareDone"))
    }

    @Test
    fun resolveJs_settlesPendingPromise() {
        assertTrue(WebShareBridge.RESOLVE_JS.contains("__wnShareDone"))
    }
}
