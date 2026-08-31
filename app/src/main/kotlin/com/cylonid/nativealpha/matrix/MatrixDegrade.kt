package com.cylonid.nativealpha.matrix

/**
 * 强制降级保留规则（D9，纯函数）。
 *
 * 槽位收缩后：槽位内活跃格保留（标题/绑定原样），其余一切格收敛为
 * 占位——含槽位外活跃格（物理上无槽位可容纳）。
 * 确定性规则：同状态输入必得同结果。
 */
internal object MatrixDegrade {

    /**
     * 强制降级目标窗数（D9「自动降 2 窗」按当前档位缩放）：6→4、5→3、
     * 4→2、3→2；已在下限（≤2）时返回 2（engine 侧 target≥当前值即不动作）。
     */
    fun degradeTarget(currentCount: Int): Int =
        (currentCount - 2).coerceAtLeast(MIN_TARGET)

    private const val MIN_TARGET = 2

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
