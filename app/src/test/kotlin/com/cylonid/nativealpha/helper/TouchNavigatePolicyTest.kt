package com.cylonid.nativealpha.helper

import com.cylonid.nativealpha.helper.WebViewTouchHandler
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多指左右滑导航判定穷举（表格误触修复）：
 * 页面可横向平移（AI 长表格/宽图）时内容滚动优先——双指滑绝不触发导航；
 * 不可平移时位移超阈值（100px）才触发。
 */
class TouchNavigatePolicyTest {

    /** 可横滚页面（长表格）：任意位移都不导航（主诉场景） */
    @Test
    fun neverNavigate_whenPageCanPanHorizontally() {
        assertFalse(WebViewTouchHandler.shouldNavigateOnSwipe(canPanHorizontally = true, dxPx = 101))
        assertFalse(WebViewTouchHandler.shouldNavigateOnSwipe(canPanHorizontally = true, dxPx = 1000))
        assertFalse(WebViewTouchHandler.shouldNavigateOnSwipe(canPanHorizontally = true, dxPx = -1000))
    }

    /** 不可横滚页面：超阈值触发（正常双指导航保留） */
    @Test
    fun navigate_whenOverThresholdAndNotPannable() {
        assertTrue(WebViewTouchHandler.shouldNavigateOnSwipe(canPanHorizontally = false, dxPx = 101))
        assertTrue(WebViewTouchHandler.shouldNavigateOnSwipe(canPanHorizontally = false, dxPx = -101))
    }

    /** 不可横滚页面：阈值内不触发（防轻微误触） */
    @Test
    fun noNavigate_whenWithinThresholdAndNotPannable() {
        assertFalse(WebViewTouchHandler.shouldNavigateOnSwipe(canPanHorizontally = false, dxPx = 100))
        assertFalse(WebViewTouchHandler.shouldNavigateOnSwipe(canPanHorizontally = false, dxPx = 0))
    }

    /** 阈值边界：恰好 100 不触发（严格大于，与历史语义一致） */
    @Test
    fun boundary_exactlyAtThresholdDoesNotNavigate() {
        assertFalse(WebViewTouchHandler.shouldNavigateOnSwipe(canPanHorizontally = false, dxPx = 100))
        assertTrue(WebViewTouchHandler.shouldNavigateOnSwipe(canPanHorizontally = false, dxPx = 100 + 1))
    }
}
