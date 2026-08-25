package com.cylonid.nativealpha

import com.cylonid.nativealpha.util.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UpdateChecker 核心逻辑测试：
 * - 语义化版本比较（含 v 前缀、预发布版本、不同位数）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateCheckerTest {

    @Test
    fun `compareVersions equal without prefix`() {
        assertEquals(0, UpdateChecker.compareVersions("2.1.34", "2.1.34"))
    }

    @Test
    fun `compareVersions equal with v prefix`() {
        assertEquals(0, UpdateChecker.compareVersions("v2.1.34", "2.1.34"))
        assertEquals(0, UpdateChecker.compareVersions("2.1.34", "v2.1.34"))
    }

    @Test
    fun `compareVersions newer patch`() {
        assertTrue(UpdateChecker.compareVersions("2.1.35", "2.1.34") > 0)
        assertTrue(UpdateChecker.compareVersions("2.1.34", "2.1.35") < 0)
    }

    @Test
    fun `compareVersions newer minor`() {
        assertTrue(UpdateChecker.compareVersions("2.2.0", "2.1.34") > 0)
    }

    @Test
    fun `compareVersions different length`() {
        assertTrue(UpdateChecker.compareVersions("2.1.34.1", "2.1.34") > 0)
        assertEquals(0, UpdateChecker.compareVersions("2.1.34.0", "2.1.34"))
    }

    @Test
    fun `compareVersions prerelease is older than release`() {
        // 2.1.35-beta 应小于 2.1.35 正式版
        assertTrue(UpdateChecker.compareVersions("2.1.35-beta", "2.1.35") < 0)
        assertTrue(UpdateChecker.compareVersions("2.1.35", "2.1.35-beta") > 0)
    }

    @Test
    fun `compareVersions prerelease ordering`() {
        assertTrue(UpdateChecker.compareVersions("2.1.35-beta", "2.1.35-rc") < 0)
        assertTrue(UpdateChecker.compareVersions("2.1.35-rc1", "2.1.35-rc2") < 0)
    }

    @Test
    fun `compareVersions current beta latest release`() {
        // 当前是 beta，GitHub 最新是正式版 → 提示更新
        assertTrue(UpdateChecker.compareVersions("v2.1.35", "2.1.35-beta") > 0)
    }
}
