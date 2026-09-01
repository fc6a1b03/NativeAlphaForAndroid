package com.cylonid.nativealpha

import android.content.ComponentCallbacks2
import com.cylonid.nativealpha.util.App
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 内存压力记录判定穷举：UI_HIDDEN（退后台例行回调）不算压力、
 * 低于 RUNNING_LOW 的回调不记、同级别及以下去重、升级才再记。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryPressureLogTest {

    private val uiHidden = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
    private val moderate = ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
    private val low = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    private val critical = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
    private val complete = ComponentCallbacks2.TRIM_MEMORY_COMPLETE

    /** UI_HIDDEN 伪压力不记（真机 21 条 LEVEL_20 噪音的教训） */
    @Test
    fun reject_uiHidden() {
        assertFalse(App.shouldLogMemoryPressure(uiHidden, 0))
    }

    /** 低于 RUNNING_LOW 的运行态回调不记 */
    @Test
    fun reject_belowRunningLow() {
        assertFalse(App.shouldLogMemoryPressure(moderate, 0))
    }

    /** RUNNING_LOW 首次记 WARNING */
    @Test
    fun accept_firstRunningLow() {
        assertTrue(App.shouldLogMemoryPressure(low, 0))
    }

    /** 同级别去重：RUNNING_LOW 记过后不再重复记 */
    @Test
    fun reject_sameLevelDedup() {
        assertFalse(App.shouldLogMemoryPressure(low, low))
    }

    /** 升级到 CRITICAL/COMPLETE 才再记 */
    @Test
    fun accept_escalation() {
        assertTrue(App.shouldLogMemoryPressure(critical, low))
        assertTrue(App.shouldLogMemoryPressure(complete, critical))
    }

    /** UI_HIDDEN 不得污染单调门槛：记过 COMPLETE 后仍按级别判定 */
    @Test
    fun uiHidden_doesNotPoisonGate() {
        // 旧实现把 20 记进门槛后，RUNNING_LOW(10) 永远记不上；现判定与门槛无关
        assertFalse(App.shouldLogMemoryPressure(low, complete))
        assertTrue(App.shouldLogMemoryPressure(complete, low))
    }
}
