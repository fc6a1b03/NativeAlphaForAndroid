package com.cylonid.nativealpha.util

import android.content.ComponentCallbacks2
import com.cylonid.nativealpha.util.WebviewRecycleRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 后台 WebView 分级回收策略穷举（ComponentCallbacks2 分级语义）：
 * 级别越深（后台 LRU 越靠后、进程越濒死）回收越激进；前台运行级只让出
 * 最旧 1 个；无后台页/无压力时不回收。
 */
class WebviewRecyclePolicyTest {

    private val runningModerate = ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
    private val runningLow = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    private val runningCritical = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
    private val uiHidden = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
    private val background = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
    private val moderate = ComponentCallbacks2.TRIM_MEMORY_MODERATE
    private val complete = ComponentCallbacks2.TRIM_MEMORY_COMPLETE

    /** 前台温和/低内存级：不回收（当前页在用，不打扰） */
    @Test
    fun noRecycle_onMildRunningLevels() {
        assertEquals(0, WebviewRecycleRegistry.recycleCount(runningModerate, 5))
        assertEquals(0, WebviewRecycleRegistry.recycleCount(runningLow, 5))
        assertEquals(0, WebviewRecycleRegistry.recycleCount(uiHidden, 5))
    }

    /** 前台内存告急（CRITICAL）：只让出最旧 1 个后台页 */
    @Test
    fun recycleOne_onRunningCritical() {
        assertEquals(1, WebviewRecycleRegistry.recycleCount(runningCritical, 5))
        assertEquals(1, WebviewRecycleRegistry.recycleCount(runningCritical, 1))
    }

    /** 后台 LRU：渐进激进（40=一半、60=2/3、80=全部） */
    @Test
    fun progressive_onBackgroundLevels() {
        assertEquals(2, WebviewRecycleRegistry.recycleCount(background, 5))
        assertEquals(3, WebviewRecycleRegistry.recycleCount(moderate, 5))
        assertEquals(5, WebviewRecycleRegistry.recycleCount(complete, 5))
    }

    /** 边界：无后台页不回收；单页轻压保守不清（40 级 floor=0） */
    @Test
    fun bounds_neverExceedAvailable() {
        assertEquals(0, WebviewRecycleRegistry.recycleCount(complete, 0))
        assertEquals(0, WebviewRecycleRegistry.recycleCount(background, 1))
        assertEquals(1, WebviewRecycleRegistry.recycleCount(moderate, 2))
    }
}
