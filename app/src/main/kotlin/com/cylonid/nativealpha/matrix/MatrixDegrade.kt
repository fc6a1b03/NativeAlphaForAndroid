package com.cylonid.nativealpha.matrix

/**
 * 强制降级保留规则（D9，纯函数）。
 *
 * 2 窗网格只有槽位 0/1：槽位内活跃格保留（标题/绑定原样），其余一切
 * 格收敛为占位——含槽位 2+ 的活跃格（物理上无槽位可容纳）。
 * 确定性规则：同状态输入必得同结果。
 */
internal object MatrixDegrade {

    /** 降级后槽位 0..targetCount-1 的保留快照 */
    fun keptCells(cells: List<MatrixCellUi>, targetCount: Int): List<MatrixCellUi> =
        cells.take(targetCount).map { cell ->
            if (cell.state == MatrixCellUiState.ACTIVE) cell else MatrixCellUi()
        }

    /** 需要释放 WebView 的格下标（槽位外全部 + 槽位内非活跃） */
    fun releaseIndices(cells: List<MatrixCellUi>, targetCount: Int): List<Int> =
        cells.indices.filter { index ->
            index >= targetCount || cells[index].state != MatrixCellUiState.ACTIVE
        }
}
