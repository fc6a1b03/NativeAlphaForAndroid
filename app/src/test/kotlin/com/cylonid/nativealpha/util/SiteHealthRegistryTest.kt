package com.cylonid.nativealpha.util

import com.cylonid.nativealpha.util.SiteHealthRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 站点健康登记（presence 语义）穷举：会话内成败互覆、未观测为 null、
 * forget 级联清理（站点删除不留残留）。
 */
class SiteHealthRegistryTest {

    @Test
    fun unknown_siteReturnsNull() {
        assertNull(SiteHealthRegistry.statusOf(101))
    }

    @Test
    fun success_thenFailure_overwrites() {
        SiteHealthRegistry.markSuccess(102)
        assertEquals(true, SiteHealthRegistry.statusOf(102))
        SiteHealthRegistry.markFailure(102)
        assertEquals(false, SiteHealthRegistry.statusOf(102))
    }

    @Test
    fun failure_thenSuccess_recovers() {
        SiteHealthRegistry.markFailure(103)
        assertEquals(false, SiteHealthRegistry.statusOf(103))
        SiteHealthRegistry.markSuccess(103)
        assertEquals(true, SiteHealthRegistry.statusOf(103))
    }

    @Test
    fun forget_removesEntry() {
        SiteHealthRegistry.markFailure(104)
        SiteHealthRegistry.forget(104)
        assertNull(SiteHealthRegistry.statusOf(104))
    }
}
