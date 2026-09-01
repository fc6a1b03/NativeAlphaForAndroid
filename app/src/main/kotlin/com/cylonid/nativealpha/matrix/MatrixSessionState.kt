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
 * @property zoomPercent 页面缩放留存（默认 100% 与宿主同源；用户可在
 * 格内调节并持久化，CSS zoom 注入实现）
 * @property textZoomPercent 字体缩放留存（相对站点 textZoom 的百分比，
 * 默认 100% 同源，调节保留）
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

        /**
         * 渲染与宿主单屏同源（用户定调：矩阵单格显示效果要与单屏一致，
         * 大小差异靠格子尺寸+手动缩放调节）——默认 100%，不注入缩放；
         * 早先的 80%「小一级」默认会改变页面布局行为（zoom 下视口 CSS
         * 像素数变化，fixed/100vh 布局错乱，Kimi 输入框异常实测），作废。
         */
        const val DEFAULT_ZOOM_PERCENT = 100

        /** 字体同源：默认 100%（不相对站点 textZoom 额外缩小），调节保留 */
        const val DEFAULT_TEXT_ZOOM_PERCENT = 100

        /**
         * 缩放下限 50%：低于该值 WebView 被等比拉到超大测量尺寸（50% 即
         * 约 2 倍容器高）后 chromium 硬件合成会截断绘制（实测 44% 时网页
         * 只画上半、50% 完整），且文字已不可读——渲染可靠性优先收敛。
         * 「适应宽度」在 6 窗极端场景的 fit 值也由应用处 coerce 到此下限。
         */
        const val MIN_ZOOM_PERCENT = 50
        const val MAX_ZOOM_PERCENT = 150

        /** 缩放可靠区间钳制（恢复/调节/渲染统一口径） */
        fun clampZoomPercent(percent: Int): Int =
            percent.coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)
    }
}
