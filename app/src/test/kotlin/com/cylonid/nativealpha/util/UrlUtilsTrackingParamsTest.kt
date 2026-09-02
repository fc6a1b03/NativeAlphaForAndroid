package com.cylonid.nativealpha.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 追踪参数剥离契约（白名单制）：只剥公认埋点参数，功能性参数永不误伤。
 */
class UrlUtilsTrackingParamsTest {

    @Test
    fun `strips utm family`() {
        assertEquals(
            "https://a.com/p?id=1",
            UrlUtils.stripTrackingParams(
                "https://a.com/p?id=1&utm_source=x&utm_medium=social&utm_campaign=c"
            )
        )
    }

    @Test
    fun `strips known platform trackers`() {
        assertEquals(
            "https://a.com/watch?v=q&feature=share",
            UrlUtils.stripTrackingParams("https://a.com/watch?v=q&si=abc&feature=share&fbclid=Iw")
        )
        assertEquals(
            "https://a.com/",
            UrlUtils.stripTrackingParams("https://a.com/?gclid=EAI&spm=from")
        )
    }

    @Test
    fun `keeps functional params untouched`() {
        val url = "https://a.com/login?token=utm_like&session=abc&redirect=/x"
        assertEquals(url, UrlUtils.stripTrackingParams(url))
    }

    @Test
    fun `keeps fragment and strips before it`() {
        assertEquals(
            "https://a.com/p?id=1#section",
            UrlUtils.stripTrackingParams("https://a.com/p?id=1&utm_source=x#section")
        )
    }

    @Test
    fun `removes bare question mark when all params stripped`() {
        assertEquals(
            "https://a.com/p",
            UrlUtils.stripTrackingParams("https://a.com/p?utm_source=x")
        )
    }

    @Test
    fun `returns same string when nothing to strip`() {
        val url = "https://a.com/p?id=1&x=y"
        assertEquals(url, UrlUtils.stripTrackingParams(url))
        assertEquals("https://a.com/p", UrlUtils.stripTrackingParams("https://a.com/p"))
    }

    @Test
    fun `key matching is case insensitive but values preserved verbatim`() {
        assertEquals(
            "https://a.com/p?next=/a%2Fb",
            UrlUtils.stripTrackingParams("https://a.com/p?UTM_Source=x&next=/a%2Fb")
        )
    }

    @Test
    fun `handles empty pairs and keeps encoded values`() {
        assertEquals(
            "https://a.com/p?&a=%2Fb",
            UrlUtils.stripTrackingParams("https://a.com/p?&a=%2Fb&utm_term=z")
        )
    }
}
