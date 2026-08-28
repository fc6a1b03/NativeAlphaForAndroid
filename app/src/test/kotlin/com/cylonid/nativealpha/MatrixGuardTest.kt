package com.cylonid.nativealpha

import com.cylonid.nativealpha.matrix.MatrixCapacityGate
import com.cylonid.nativealpha.matrix.MatrixCellUi
import com.cylonid.nativealpha.matrix.MatrixCellUiState
import com.cylonid.nativealpha.matrix.MatrixCrashBackoff
import com.cylonid.nativealpha.matrix.MatrixDegrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 矩阵守卫层纯逻辑穷举（P4 计划测试清单 L1 部分）：
 * 容量闸门（含 fail-open 路径）/崩溃退避状态机/强制降级保留规则。
 */
class MatrixGuardTest {

    // ===== 容量闸门（D7） =====

    /** fail-open：预算缺失（采样失败）与边际未知（尚无实测）一律放行 */
    @Test
    fun gate_failOpen_whenBudgetOrMarginalUnknown() {
        assertEquals(
            MatrixCapacityGate.Decision.Allow,
            MatrixCapacityGate.decide(activeCellCount = 2, budget = null)
        )
        assertEquals(
            MatrixCapacityGate.Decision.Allow,
            MatrixCapacityGate.decide(
                activeCellCount = 2,
                budget = MatrixCapacityGate.Budget(totalBytes = 100_000_000, perCellBytes = 0)
            )
        )
        assertEquals(
            MatrixCapacityGate.Decision.Allow,
            MatrixCapacityGate.decide(
                activeCellCount = 0,
                budget = MatrixCapacityGate.Budget(totalBytes = -1, perCellBytes = 50_000_000)
            )
        )
    }

    /** 有确切证据且预算充足：放行 */
    @Test
    fun gate_allows_whenBudgetFits() {
        val budget = MatrixCapacityGate.Budget(totalBytes = 150, perCellBytes = 50)
        assertEquals(MatrixCapacityGate.Decision.Allow, MatrixCapacityGate.decide(2, budget))
        assertEquals(MatrixCapacityGate.Decision.Allow, MatrixCapacityGate.decide(0, budget))
    }

    /** 预算不够且已有活跃窗：本格容量受限（点击重试，预算动态） */
    @Test
    fun gate_limitsCell_whenBudgetExceededWithActiveCells() {
        val budget = MatrixCapacityGate.Budget(totalBytes = 100, perCellBytes = 50)
        assertEquals(
            MatrixCapacityGate.Decision.LimitCell,
            MatrixCapacityGate.decide(activeCellCount = 2, budget = budget)
        )
    }

    /** 预算连首窗边际都盖不住：设备不足，如实劝退（不硬开功能） */
    @Test
    fun gate_reportsDeviceUnsupported_whenFirstCellUnaffordable() {
        val budget = MatrixCapacityGate.Budget(totalBytes = 30, perCellBytes = 50)
        assertEquals(
            MatrixCapacityGate.Decision.DeviceUnsupported,
            MatrixCapacityGate.decide(activeCellCount = 0, budget = budget)
        )
    }

    /** 边际校准：无效采样原样返回；首测直接采纳；其后 3:1 EMA 平滑 */
    @Test
    fun calibratePerCell_emaProgression() {
        assertEquals(40L, MatrixCapacityGate.calibratePerCell(previous = 40L, measured = -1))
        assertEquals(60L, MatrixCapacityGate.calibratePerCell(previous = 0L, measured = 60))
        assertEquals(115L, MatrixCapacityGate.calibratePerCell(previous = 100L, measured = 160))
    }

    // ===== 崩溃退避（D3/A） =====

    /** 窗口内首次崩溃允许自动恢复；达到阈值（2 次/30s）触发退避 */
    @Test
    fun backoff_stopsAutoReloadAfterThreshold() {
        val backoff = MatrixCrashBackoff()
        assertTrue(backoff.onCrash(nowMs = 0))
        assertFalse(backoff.onCrash(nowMs = 1_000))
    }

    /** 窗口外旧崩溃滑出：退避自动解除 */
    @Test
    fun backoff_releasesAfterWindowPasses() {
        val backoff = MatrixCrashBackoff()
        backoff.onCrash(0)
        assertFalse(backoff.onCrash(1_000))
        assertTrue(backoff.onCrash(60_000))
    }

    /** 手动点击重试重置退避计数（D3 决策：给一次新机会） */
    @Test
    fun backoff_manualRetryResets() {
        val backoff = MatrixCrashBackoff()
        backoff.onCrash(0)
        backoff.onCrash(1_000)
        backoff.onManualRetry()
        assertTrue(backoff.onCrash(2_000))
    }

    // ===== 强制降级保留规则（D9） =====

    private fun active(id: Int) = MatrixCellUi(
        state = MatrixCellUiState.ACTIVE, webappId = id, title = "site$id"
    )

    /** 槽位内活跃格保留（标题/绑定原样），非活跃槽位收敛占位 */
    @Test
    fun degrade_keepsActiveInSlots() {
        val cells = listOf(
            active(3), MatrixCellUi(state = MatrixCellUiState.ERROR, webappId = 5), active(7), active(9)
        )
        val kept = MatrixDegrade.keptCells(cells, targetCount = 2)

        assertEquals(2, kept.size)
        assertEquals(active(3), kept[0])
        assertEquals(MatrixCellUi(), kept[1])
    }

    /** 释放集合=槽位外全部 + 槽位内非活跃（规则纯函数，与 targetCount 无关地自洽） */
    @Test
    fun degrade_releasesOutOfSlotAndInactive() {
        val cells = listOf(
            active(3), MatrixCellUi(state = MatrixCellUiState.LOADING, webappId = 5), active(7), active(9)
        )
        assertEquals(listOf(1, 2, 3), MatrixDegrade.releaseIndices(cells, targetCount = 2))
        // 规则一致性：非活跃槽位无论 target 取值都在释放集（LOADING 格不豁免）
        assertEquals(listOf(1), MatrixDegrade.releaseIndices(cells, targetCount = 4))
    }
}
