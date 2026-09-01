package com.cylonid.nativealpha.matrix

import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp

/**
 * 矩阵窗格运行态（五态机，规格 §4.3）。持久化只存 webappId/zoomPercent，
 * 其余是会话内瞬时状态。
 */
internal enum class MatrixCellUiState {
    /** 空占位：点击打开选择器 */
    PLACEHOLDER,

    /** 加载中：纯 Compose 轻指示器（不用 animal_walk，×4 即加载风暴） */
    LOADING,

    /** 活跃：工具条 + WebView 满格 */
    ACTIVE,

    /** 错误：渲染崩溃/加载失败，点击重新选择（手动重试重置退避） */
    ERROR,

    /** 容量受限：闸门拦截，点击重试（预算动态，退出其他应用后可进） */
    CAPACITY_LIMITED
}

/** 窗格 UI 快照（StateFlow 驱动，Compose 只读渲染零决策） */
internal data class MatrixCellUi(
    /** 会话内唯一身份（拖拽排序的 Compose key；磁盘不存，进程内自增） */
    val uid: Int = 0,
    val state: MatrixCellUiState = MatrixCellUiState.PLACEHOLDER,
    val webappId: Int = MatrixCellState.PLACEHOLDER_WEBAPP_ID,
    val title: String = "",
    /** 页面缩放 %（Q1 反转：格子视口小默认 80%，格内可调并持久化） */
    val zoomPercent: Int = MatrixCellState.DEFAULT_ZOOM_PERCENT,
    /** 字体缩放 %（相对站点 textZoom；默认 90%「小一级」） */
    val textZoomPercent: Int = MatrixCellState.DEFAULT_TEXT_ZOOM_PERCENT
) {
    /** 绑定站点快照（工具条标题/favicon 用；占位为 null） */
    val webapp: WebApp?
        get() = if (webappId == MatrixCellState.PLACEHOLDER_WEBAPP_ID) {
            null
        } else {
            DataManager.getInstance().getWebApp(webappId)
        }
}

/** 引擎一次性事件（UI 映射为 Snackbar 文案；extraBufferCapacity 防丢） */
internal enum class MatrixNotice { DEGRADED, CRASH_BACKOFF }
