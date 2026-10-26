package com.cylonid.nativealpha.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 文件选择保活服务单测（2026-10-26 上传失效修复）：选图期间前台保活防 LMK
 * 杀进程致 filePathCallback 丢失。守卫状态幂等 + startForegroundService
 * 真实派发 + settle 停止。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChooserKeepAliveTest {

    private val context get() = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun startRunsForegroundServiceOnce_idempotent() {
        ChooserKeepAlive.start(context)
        ChooserKeepAlive.start(context)
        assertTrue("start 后守卫状态置位", ChooserKeepAlive.isRunningForTest())
        val service = shadowOf(context).nextStartedService
        assertNotNull("必须真实派发保活服务", service)
        assertEquals(
            "派发的必须是保活服务组件",
            ChooserKeepAlive::class.java.name, service.component?.className
        )
    }

    @Test
    fun stopClearsGuard_andStopsService() {
        ChooserKeepAlive.start(context)
        ChooserKeepAlive.stop(context)
        assertFalse("stop 后守卫状态复位", ChooserKeepAlive.isRunningForTest())
        val stopped = shadowOf(context).nextStoppedService
        assertNotNull("stop 必须真实停止服务", stopped)
    }
}
