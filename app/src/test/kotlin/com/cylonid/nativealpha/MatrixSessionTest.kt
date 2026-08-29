package com.cylonid.nativealpha

import com.cylonid.nativealpha.matrix.MatrixCellState
import com.cylonid.nativealpha.matrix.MatrixSessionCodec
import com.cylonid.nativealpha.matrix.MatrixSessionState
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多窗矩阵数据层契约锁（P4，规格 §4.2）。
 *
 * MatrixSessionState 经 Gson 落入自持 DataStore matrix_session——字段名
 * 即持久化契约（@Keep 锁名 + 本测试防漂移），归一化/过滤是重启恢复管线
 * 的纯函数核心，行为在此穷举锁定。
 */
class MatrixSessionTest {

    private val gson = com.google.gson.Gson()

    /** 序列化字段名不漂移（windowCount/cells/webappId/zoomPercent） */
    @Test
    fun codecJsonFieldNames_stayStable() {
        val state = MatrixSessionState(
            windowCount = 3,
            cells = listOf(MatrixCellState(7), MatrixCellState(12, 150), MatrixCellState())
        )
        val obj = gson.fromJson(MatrixSessionCodec.encode(state), JsonObject::class.java)

        assertTrue(obj.has("windowCount"))
        assertTrue(obj.has("cells"))
        val cell0 = obj.getAsJsonArray("cells").get(0).asJsonObject
        val cell1 = obj.getAsJsonArray("cells").get(1).asJsonObject
        assertTrue(cell0.has("webappId"))
        assertTrue(cell0.has("zoomPercent"))
        assertEquals(7, cell0.get("webappId").asInt)
        assertEquals(150, cell1.get("zoomPercent").asInt)
    }

    /** 编解码往返无损（含占位格与非默认缩放） */
    @Test
    fun codecRoundTrip_preservesState() {
        val state = MatrixSessionState(
            windowCount = 4,
            cells = listOf(
                MatrixCellState(3), MatrixCellState(),
                MatrixCellState(9, 120), MatrixCellState(0)
            )
        )
        val restored = MatrixSessionCodec.decode(MatrixSessionCodec.encode(state))

        assertEquals(state, restored)
    }

    /** 损坏 JSON 解码抛 JsonParseException（Store 层捕获回退默认值） */
    @Test
    fun decodeCorruptJson_throwsForStoreFallback() {
        assertThrows(JsonParseException::class.java) {
            MatrixSessionCodec.decode("{{{not-json")
        }
    }

    /** 归一化：windowCount 收敛到 2..4（D2 Slider 边界，损坏数据兜底） */
    @Test
    fun normalized_clampsWindowCount() {
        assertEquals(4, MatrixSessionState(windowCount = 99).normalized().windowCount)
        assertEquals(2, MatrixSessionState(windowCount = 0).normalized().windowCount)
        assertEquals(2, MatrixSessionState(windowCount = -5).normalized().windowCount)
        assertEquals(3, MatrixSessionState(windowCount = 3).normalized().windowCount)
    }

    /** 归一化：cells 补占位对齐 windowCount（恢复时历史布局短于窗口数） */
    @Test
    fun normalized_padsCellsToWindowCount() {
        val state = MatrixSessionState(
            windowCount = 4,
            cells = listOf(MatrixCellState(5))
        ).normalized()

        assertEquals(4, state.cells.size)
        assertEquals(5, state.cells[0].webappId)
        assertTrue(state.cells[1].isPlaceholder)
        assertTrue(state.cells[2].isPlaceholder)
        assertTrue(state.cells[3].isPlaceholder)
    }

    /** 归一化：cells 截断对齐 windowCount（减窗后残留超长列表） */
    @Test
    fun normalized_trimsCellsToWindowCount() {
        val state = MatrixSessionState(
            windowCount = 2,
            cells = listOf(MatrixCellState(1), MatrixCellState(2), MatrixCellState(3))
        ).normalized()

        assertEquals(2, state.cells.size)
        assertEquals(1, state.cells[0].webappId)
        assertEquals(2, state.cells[1].webappId)
    }

    /** 已删站点过滤：失配格回占位，占位格与存活站格不动（规格 §4.2） */
    @Test
    fun filteredByActiveSites_resetsDeletedToPlaceholder() {
        val state = MatrixSessionState(
            windowCount = 3,
            cells = listOf(MatrixCellState(1), MatrixCellState(2), MatrixCellState(3))
        )

        val filtered = state.filteredByActiveSites(activeSiteIds = setOf(1, 3))

        assertEquals(1, filtered.cells[0].webappId) // 存活
        assertTrue(filtered.cells[1].isPlaceholder) // 已删 → 占位
        assertEquals(3, filtered.cells[2].webappId) // 存活
        assertEquals(MatrixCellState.DEFAULT_ZOOM_PERCENT, filtered.cells[1].zoomPercent)
    }

    /** 占位格默认值契约：webappId=-1（PLACEHOLDER）、页面缩放 80%、字体 90% */
    @Test
    fun placeholderCell_defaults() {
        val cell = MatrixCellState()

        assertTrue(cell.isPlaceholder)
        assertEquals(MatrixCellState.PLACEHOLDER_WEBAPP_ID, cell.webappId)
        assertEquals(MatrixCellState.DEFAULT_ZOOM_PERCENT, cell.zoomPercent)
        assertEquals(MatrixCellState.DEFAULT_TEXT_ZOOM_PERCENT, cell.textZoomPercent)
    }
}
