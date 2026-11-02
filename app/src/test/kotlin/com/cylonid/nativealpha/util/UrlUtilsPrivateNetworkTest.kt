package com.cylonid.nativealpha.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 私网地址判定穷举（Android 17 LNA 场景化申请依据）：
 * 正例覆盖全支持段，反例重点锁「前缀字符串误伤」陷阱（10.x vs 100.x、
 * 172.16-31 段边界、CGNAT 段边界）与公网 IPv6/域名不误判。
 */
class UrlUtilsPrivateNetworkTest {

    /** 正例：RFC1918 三段 + 环回 + 链路本地 + CGNAT */
    @Test
    fun private_ipv4LiteralFamilies() {
        assertTrue(UrlUtils.isPrivateNetworkHost("http://192.168.1.1"))
        assertTrue(UrlUtils.isPrivateNetworkHost("http://10.0.0.5"))
        assertTrue(UrlUtils.isPrivateNetworkHost("http://172.16.0.1"))
        assertTrue(UrlUtils.isPrivateNetworkHost("http://172.31.255.254"))
        assertTrue(UrlUtils.isPrivateNetworkHost("http://169.254.10.1"))
        assertTrue(UrlUtils.isPrivateNetworkHost("http://127.0.0.1:9000"))
        // CGNAT 100.64/10 两端边界
        assertTrue(UrlUtils.isPrivateNetworkHost("http://100.64.0.1"))
        assertTrue(UrlUtils.isPrivateNetworkHost("http://100.127.255.254"))
    }

    /** 反例：公网地址不误伤（含段边界外一格与前缀陷阱） */
    @Test
    fun public_ipv4NotMisjudged() {
        assertFalse(UrlUtils.isPrivateNetworkHost("https://example.com"))
        assertFalse(UrlUtils.isPrivateNetworkHost("http://8.8.8.8"))
        assertFalse(UrlUtils.isPrivateNetworkHost("http://9.9.9.9"))
        // 前缀陷阱：100.x 越出 CGNAT 段
        assertFalse(UrlUtils.isPrivateNetworkHost("http://100.63.0.1"))
        assertFalse(UrlUtils.isPrivateNetworkHost("http://100.128.0.1"))
        // 172 段边界外
        assertFalse(UrlUtils.isPrivateNetworkHost("http://172.15.0.1"))
        assertFalse(UrlUtils.isPrivateNetworkHost("http://172.32.0.1"))
        assertFalse(UrlUtils.isPrivateNetworkHost("http://192.169.1.1"))
    }

    /** 正例：localhost 主机名与 mDNS `.local` 后缀 */
    @Test
    fun private_localHostnameAndMdns() {
        assertTrue(UrlUtils.isPrivateNetworkHost("http://localhost:3000"))
        assertTrue(UrlUtils.isPrivateNetworkHost("http://mynas.local:5000"))
        assertTrue(UrlUtils.isPrivateNetworkHost("http://foo.localhost"))
    }

    /** 正例：IPv6 环回/ULA/链路本地（java.net.URI 的 host 保留 [] 括号，须正确处理） */
    @Test
    fun private_ipv6Families() {
        assertTrue(UrlUtils.isPrivateNetworkHost("http://[::1]:8080"))
        assertTrue(UrlUtils.isPrivateNetworkHost("http://[fd00::1]/admin"))
        assertTrue(UrlUtils.isPrivateNetworkHost("http://[fc00::abcd]"))
        assertTrue(UrlUtils.isPrivateNetworkHost("http://[fe80::1234:56ff:fe78:9abc]"))
    }

    /** 反例：公网 IPv6 与畸形输入不误判、不抛异常 */
    @Test
    fun public_ipv6AndMalformed() {
        assertFalse(UrlUtils.isPrivateNetworkHost("http://[2001:db8::1]"))
        assertFalse(UrlUtils.isPrivateNetworkHost(""))
        assertFalse(UrlUtils.isPrivateNetworkHost("not a url"))
        // 无 scheme 的裸 host:port 形态（URI 解析为 scheme=localhost）不误判为 host
        assertFalse(UrlUtils.isPrivateNetworkHost("localhost:3000"))
    }

    /** 大小写与端口/路径形态鲁棒性 */
    @Test
    fun robust_caseAndPortForms() {
        assertTrue(UrlUtils.isPrivateNetworkHost("HTTP://192.168.1.1:8080/path?q=1"))
        assertTrue(UrlUtils.isPrivateNetworkHost("http://MYNAS.LOCAL"))
    }
}
