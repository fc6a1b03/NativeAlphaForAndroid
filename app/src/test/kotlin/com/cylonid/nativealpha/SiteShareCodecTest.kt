package com.cylonid.nativealpha

import com.cylonid.nativealpha.util.SiteShareCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 站点分享深链编解码穷举（fail-closed 安全契约，借鉴 happier 配对纪律）：
 * 深链数据视为不可信输入——版本门/scheme 白名单/userinfo 剥离/长度上限/
 * 非法输入一律 null。
 */
class SiteShareCodecTest {

    /** 编码→解析往返：URL 与名称无损（含中文与查询参数） */
    @Test
    fun roundTrip_preservesUrlAndName() {
        val link = SiteShareCodec.buildShareLink(
            "https://example.com/path?a=1", "我的站点"
        )
        val parsed = link?.let { SiteShareCodec.parseShareLink(it) }
        assertNotNull(parsed)
        assertEquals("https://example.com/path?a=1", parsed!!.url)
        assertEquals("我的站点", parsed.name)
    }

    /** 编码侧白名单：非 http(s) 或无 host 的站点拒绝生成 */
    @Test
    fun build_rejectsInvalidSiteUrl() {
        assertNull(SiteShareCodec.buildShareLink("ftp://example.com", "x"))
        assertNull(SiteShareCodec.buildShareLink("javascript:alert(1)", "x"))
        assertNull(SiteShareCodec.buildShareLink("not a url", "x"))
        assertNull(SiteShareCodec.buildShareLink("", "x"))
    }

    /** 版本门：v 不等于受支持版本一律拒绝（未来格式演进防线） */
    @Test
    fun parse_rejectsUnknownVersion() {
        val good = SiteShareCodec.buildShareLink("https://example.com", "S")!!
        val evil = good.replace("v=1", "v=2")
        assertNull(SiteShareCodec.parseShareLink(evil))
        assertNull(SiteShareCodec.parseShareLink(good.replace("v=1&", "")))
    }

    /** scheme/host 门：非 webnative://add 形态拒绝 */
    @Test
    fun parse_rejectsWrongSchemeOrHost() {
        val link = SiteShareCodec.buildShareLink("https://example.com", "S")!!
        val httpForm = link.removePrefix("webnative://")
        assertNull(SiteShareCodec.parseShareLink("https://add?$httpForm"))
        assertNull(SiteShareCodec.parseShareLink("webnative://other?v=1&u=https%3A%2F%2Fx.com"))
        assertNull(SiteShareCodec.parseShareLink("garbage"))
    }

    /** URL 白名单：分享链接里的 u 必须是 http(s) 且有 host */
    @Test
    fun parse_rejectsNonHttpTargets() {
        fun encoded(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        assertNull(
            SiteShareCodec.parseShareLink(
                "webnative://add?v=1&u=${encoded("javascript:alert(1)")}&n=x"
            )
        )
        assertNull(
            SiteShareCodec.parseShareLink(
                "webnative://add?v=1&u=${encoded("file:///etc/passwd")}&n=x"
            )
        )
        assertNull(
            SiteShareCodec.parseShareLink(
                "webnative://add?v=1&u=${encoded("https://")}&n=x"
            )
        )
    }

    /** 钓鱼剥离：u 中的 userinfo（user:pass@）被移除而非透传 */
    @Test
    fun parse_stripsUserinfoFromTarget() {
        val encoded = java.net.URLEncoder.encode(
            "https://user:pass@evil.example.com/path", "UTF-8"
        )
        val parsed = SiteShareCodec.parseShareLink(
            "webnative://add?v=1&u=$encoded&n=x"
        )
        assertNotNull(parsed)
        assertEquals("https://evil.example.com/path", parsed!!.url)
        assertEquals(false, parsed.url.contains("user:pass"))
    }

    /** 长度上限：超长 URL 拒绝 */
    @Test
    fun parse_rejectsOverlongUrl() {
        val longPath = "/".repeat(SiteShareCodec.MAX_URL_LENGTH)
        val encoded = java.net.URLEncoder.encode("https://example.com$longPath", "UTF-8")
        assertNull(
            SiteShareCodec.parseShareLink("webnative://add?v=1&u=$encoded")
        )
    }

    /** 名称净化：空白退化为 host，超长截断 */
    @Test
    fun parse_sanitizesName() {
        val parsed = SiteShareCodec.parseShareLink(
            "webnative://add?v=1&u=${java.net.URLEncoder.encode("https://shop.example.com/a", "UTF-8")}&n="
        )
        assertNotNull(parsed)
        assertEquals("shop.example.com", parsed!!.name)

        val longName = "名".repeat(100)
        val parsed2 = SiteShareCodec.parseShareLink(
            "webnative://add?v=1&u=${java.net.URLEncoder.encode("https://example.com", "UTF-8")}" +
                "&n=${java.net.URLEncoder.encode(longName, "UTF-8")}"
        )
        assertNotNull(parsed2)
        assertEquals(SiteShareCodec.MAX_NAME_LENGTH, parsed2!!.name.length)
    }

    /** 名称含保留字符（& =）经 URL 编解码后不被误切分 */
    @Test
    fun roundTrip_nameWithSpecialChars() {
        val link = SiteShareCodec.buildShareLink("https://example.com", "A&B=C 论坛")
        val parsed = link?.let { SiteShareCodec.parseShareLink(it) }
        assertNotNull(parsed)
        assertEquals("A&B=C 论坛", parsed!!.name)
    }
}
