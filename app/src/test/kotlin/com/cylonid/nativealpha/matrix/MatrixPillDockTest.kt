package com.cylonid.nativealpha.matrix

import com.cylonid.nativealpha.matrix.MatrixPillDock
import com.cylonid.nativealpha.matrix.matrixPillDockTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 可移动窗数胶囊吸边判定穷举（用户拍板的浮窗助手交互）：
 * 中心距左/右边缘小于阈值即吸该侧，居中区域与顶缘不吸
 * （v2.2.11 撤销顶置吸顶：与系统下拉通知栏手势区冲突）。
 */
class MatrixPillDockTest {

    private val containerW = 1000f
    private val containerH = 2000f
    private val threshold = 160f

    /** 中心贴左缘 → 吸左 */
    @Test
    fun dock_left_whenCenterNearLeftEdge() {
        assertEquals(
            MatrixPillDock.LEFT,
            matrixPillDockTarget(0f, containerH / 2f, containerW, containerH, threshold)
        )
        assertEquals(
            MatrixPillDock.LEFT,
            matrixPillDockTarget(threshold - 1f, containerH / 2f, containerW, containerH, threshold)
        )
    }

    /** 中心贴右缘 → 吸右 */
    @Test
    fun dock_right_whenCenterNearRightEdge() {
        assertEquals(
            MatrixPillDock.RIGHT,
            matrixPillDockTarget(containerW, containerH / 2f, containerW, containerH, threshold)
        )
        assertEquals(
            MatrixPillDock.RIGHT,
            matrixPillDockTarget(
                containerW - threshold + 1f, containerH / 2f, containerW, containerH, threshold
            )
        )
    }

    /** 贴顶缘 → 不吸（顶置收纳已撤销：与系统下拉手势冲突） */
    @Test
    fun noDock_whenCenterNearTopEdge() {
        assertNull(
            matrixPillDockTarget(containerW / 2f, 0f, containerW, containerH, threshold)
        )
        assertNull(
            matrixPillDockTarget(
                containerW / 2f, threshold - 1f, containerW, containerH, threshold
            )
        )
    }

    /** 角落位置（同时近顶与左）→ 吸左（顶缘无收纳，侧缘接管） */
    @Test
    fun dock_leftWinsAtTopCorner() {
        assertEquals(
            MatrixPillDock.LEFT,
            matrixPillDockTarget(10f, 10f, containerW, containerH, threshold)
        )
    }

    /** 居中区域松手 → 不吸（原地停留） */
    @Test
    fun noDock_whenCenterInMiddleZone() {
        assertNull(
            matrixPillDockTarget(
                containerW / 2f, containerH / 2f, containerW, containerH, threshold
            )
        )
        assertNull(
            matrixPillDockTarget(
                threshold, containerH / 2f, containerW, containerH, threshold
            )
        )
    }

    /** 阈值边界：恰好等于阈值处不吸（严格小于才吸） */
    @Test
    fun boundary_exactlyAtThresholdDoesNotDock() {
        assertNull(
            matrixPillDockTarget(
                containerW / 2f, threshold, containerW, containerH, threshold
            )
        )
    }
}
