package com.cylonid.nativealpha.matrix

import androidx.annotation.Keep

/**
 * 多窗矩阵会话状态（P4 数据模型，规格 §4.2）。
 *
 * 持久化契约：经 [MatrixSessionCodec] 以 Gson JSON 落入自持 DataStore
 * `matrix_session`（不参与宿主备份）。@Keep 锁死字段名供 Gson 反射——
 * 改名 = 用户矩阵布局丢失，任何字段变更必须同步序列化契约单测。
 *
 * webappId 语义：宿主 DataManager 的 WebApp ID（数组下标）；
 * [MatrixCellState.PLACEHOLDER_WEBAPP_ID] 表示空占位格。
 */
@Keep
data class MatrixSessionState(
    /** 同屏窗口数（结构上限 6，设备呈现档位由资源探测动态决定）；越界值经 [normalized] 收敛 */
    val windowCount: Int = DEFAULT_WINDOW_COUNT,
    /** 窗格状态列表，长度与 windowCount 对齐（不足补占位、超出截断） */
    val cells: List<MatrixCellState> = emptyList()
) {

    /**
     * 结构归一化：windowCount 收敛到 2..6，cells 补占位/截断对齐。
     * 重启恢复管线第一步（DataStore 读出的历史/损坏数据在此收敛）。
     */
    fun normalized(): MatrixSessionState {
        val count = windowCount.coerceIn(MIN_WINDOW_COUNT, MAX_WINDOW_COUNT)
        val alignedCells = cells.take(count) + List((count - cells.size).coerceAtLeast(0)) {
            MatrixCellState()
        }
        return copy(windowCount = count, cells = alignedCells)
    }

    /**
     * 设备档位钳制（normalized 之后第二步）：高配机存的 6 窗会话在低配机
     * 恢复时收敛到本机 [MatrixCapacityGate.decideMaxWindows] 给出的上限
     * （≥2 保证结构合法；cells 随之截断/补位对齐）。
     */
    fun clampToMaxWindows(deviceMax: Int): MatrixSessionState {
        val count = windowCount.coerceIn(MIN_WINDOW_COUNT, deviceMax.coerceAtLeast(MIN_WINDOW_COUNT))
        if (count == windowCount) return this
        val alignedCells = cells.take(count) + List((count - cells.size).coerceAtLeast(0)) {
            MatrixCellState()
        }
        return copy(windowCount = count, cells = alignedCells)
    }

    /**
     * 已删站点过滤（规格 §4.2：isActiveEntry=false 的格自动回占位）。
     * 纯函数：传入仍存活的站点 ID 集合，失配格重置为占位。
     */
    fun filteredByActiveSites(activeSiteIds: Set<Int>): MatrixSessionState = copy(
        cells = cells.map { cell ->
            if (cell.isPlaceholder || cell.webappId in activeSiteIds) cell else MatrixCellState()
        }
    )

    companion object {
        const val MIN_WINDOW_COUNT = 2

        /**
         * 绝对结构上限（布局/数组/持久化收敛口径）。注意这不是设备呈现
         * 上限——实际 Slider 上限由 [MatrixCapacityGate.decideMaxWindows]
         * 按设备真实资源动态决定（3/4/6 三档），恢复时按设备二次钳制。
         */
        const val MAX_WINDOW_COUNT = 6
        const val DEFAULT_WINDOW_COUNT = 2
    }
}

/**
 * 单个矩阵窗格的持久化状态。
 *
 * @property webappId 绑定站点（-1=占位）；同站可选多窗（多窗对照）
 * @property zoomPercent 页面缩放留存（Q1 反转后启用：格子视口小，默认
 * 80% 起步，用户可在格内调节并持久化）
 * @property textZoomPercent 字体缩放留存（相对站点 textZoom 的百分比，
 * 默认 90%「小一级」——用户实测格子内默认字体偏大）
 */
@Keep
data class MatrixCellState(
    val webappId: Int = PLACEHOLDER_WEBAPP_ID,
    val zoomPercent: Int = DEFAULT_ZOOM_PERCENT,
    val textZoomPercent: Int = DEFAULT_TEXT_ZOOM_PERCENT
) {

    /** 是否为空占位格（未绑定站点） */
    val isPlaceholder: Boolean get() = webappId == PLACEHOLDER_WEBAPP_ID

    companion object {
        const val PLACEHOLDER_WEBAPP_ID = -1

        /** 格子视口小于全屏，页面等比渲染偏大——用户拍板默认 80% */
        const val DEFAULT_ZOOM_PERCENT = 80

        /** 字体「小一级」：相对站点 textZoom 的 90% */
        const val DEFAULT_TEXT_ZOOM_PERCENT = 90
    }
}
