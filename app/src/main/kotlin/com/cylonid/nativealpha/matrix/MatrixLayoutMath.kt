package com.cylonid.nativealpha.matrix

import kotlin.math.abs

/**
 * 矩阵布局几何纯函数集（无状态，可单测）：拖拽换位的相邻格映射、
 * 「适应宽度」fit 缩放、布局列数、主帧失败可转错误态的状态判定。
 */
internal object MatrixLayoutMath {

    /**
     * 目标格映射：direction(dx,dy 主方向) 下按当前布局的相邻格；越界返回 -1。
     *
     * @param windowCount 当前窗数（决定网格拓扑）
     * @param index 发起拖拽的格下标
     */
    fun neighborIndex(windowCount: Int, index: Int, dx: Float, dy: Float): Int {
        val horizontal = abs(dx) >= abs(dy)
        return when (windowCount) {
            // 2 窗=左右分栏（用户定调默认并排）：横向换位
            2 -> when {
                horizontal && dx > 0 -> if (index == 0) 1 else -1
                horizontal && dx < 0 -> if (index == 1) 0 else -1
                else -> -1
            }
            3 -> when {
                horizontal && dx > 0 -> if (index == 0) 1 else -1
                horizontal && dx < 0 -> if (index == 1) 0 else -1
                !horizontal && dy > 0 -> if (index <= 1) 2 else -1
                !horizontal && dy < 0 -> if (index == 2) 0 else -1
                else -> -1
            }
            // 5 = 上 3 下 2（上排行内 0-1-2、下排行内 3-4；纵向 0↔3、1↔4）
            5 -> when {
                horizontal && dx > 0 -> when (index) {
                    0 -> 1
                    1 -> 2
                    3 -> 4
                    else -> -1
                }
                horizontal && dx < 0 -> when (index) {
                    1 -> 0
                    2 -> 1
                    4 -> 3
                    else -> -1
                }
                !horizontal && dy > 0 -> when (index) {
                    0 -> 3
                    1 -> 4
                    else -> -1
                }
                !horizontal && dy < 0 -> when (index) {
                    3 -> 0
                    4 -> 1
                    else -> -1
                }
                else -> -1
            }
            // 4（2×2）与 6（2×3）同构：每行 2 列，纵向 ±一整行
            else -> when {
                horizontal && dx > 0 -> if (index % 2 == 0) index + 1 else -1
                horizontal && dx < 0 -> if (index % 2 == 1) index - 1 else -1
                !horizontal && dy > 0 -> if (index / 2 < windowCount / 2 - 1) index + 2 else -1
                !horizontal && dy < 0 -> if (index / 2 > 0) index - 2 else -1
                else -> -1
            }
        }
    }

    /**
     * 「适应宽度」缩放：格子物理宽度只有半屏时，width=device-width 页面按
     * 格子宽（~168px）布局导致挤压换行——缩小 zoom 让布局视口 ≈ 单屏宽，
     * 页面按单屏布局整体缩进格子（显示效果与单屏一致，代价是等比缩小的
     * 字号，用户可再调）。
     *
     * @param cellWidthCss 格子 CSS 宽（px）
     * @param hostWidthCss 宿主单屏 CSS 宽（px）
     */
    fun fitZoomPercent(cellWidthCss: Int, hostWidthCss: Int): Int {
        if (cellWidthCss <= 0 || hostWidthCss <= 0) return 100
        return (cellWidthCss.toFloat() / hostWidthCss * 100).toInt().coerceIn(30, 100)
    }

    /** 该格所在布局的列数（fit 计算用；3 窗为上 2 列/下 1 列特殊结构） */
    fun columnCountOf(windowCount: Int, cellIndex: Int): Int = when {
        windowCount == 3 -> if (cellIndex < 2) 2 else 1
        windowCount == 2 -> 2
        else -> 2 // 4=2×2、5=上3下2、6=2×3——均为 2 列网格
    }.coerceIn(1, windowCount)

    /**
     * 主帧加载失败可转错误态的状态集合：
     * LOADING=经典时序（error 先于 finished）；ACTIVE=缓存/重定向时序
     * （finished 先把格置 ACTIVE，主帧 error 后到——飞行模式实测复现）。
     * CAPACITY_LIMITED（闸门语义不覆盖）/占位/错误态不在列。
     */
    fun isFailureTransitional(state: MatrixCellUiState): Boolean =
        state == MatrixCellUiState.LOADING || state == MatrixCellUiState.ACTIVE
}
