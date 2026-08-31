package com.cylonid.nativealpha

import com.cylonid.nativealpha.matrix.MatrixCapacityGate
import com.cylonid.nativealpha.matrix.MatrixCellState
import com.cylonid.nativealpha.matrix.MatrixCellUi
import com.cylonid.nativealpha.matrix.MatrixCellUiState
import com.cylonid.nativealpha.matrix.MatrixCrashBackoff
import com.cylonid.nativealpha.matrix.MatrixDegrade
import com.cylonid.nativealpha.matrix.MatrixEngine
import com.cylonid.nativealpha.matrix.MatrixSessionState
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

    // ===== 设备呈现档位（动态窗口数上限） =====

    private val gb = 1024L * 1024 * 1024

    /** 低端收缩：官方低内存标志或 MemTotal < 3.5GB → 3 窗（预读机器资源决定呈现） */
    @Test
    fun maxWindows_lowRamShrinksTo3() {
        assertEquals(3, MatrixCapacityGate.decideMaxWindows(totalRamBytes = 2 * gb, isLowRamDevice = false))
        assertEquals(3, MatrixCapacityGate.decideMaxWindows(totalRamBytes = 3584L * 1024 * 1024 - 1, isLowRamDevice = false))
        assertEquals(3, MatrixCapacityGate.decideMaxWindows(totalRamBytes = 8 * gb, isLowRamDevice = true))
    }

    /** 基准 4 窗：3.5GB..7GB 宽档（4GB 机型 ~3.7GB、6GB 机型 ~5.5GB 均落此档，留余量） */
    @Test
    fun maxWindows_baselineAndExtended() {
        assertEquals(4, MatrixCapacityGate.decideMaxWindows(totalRamBytes = 3584L * 1024 * 1024, isLowRamDevice = false))
        assertEquals(4, MatrixCapacityGate.decideMaxWindows(totalRamBytes = 4 * gb, isLowRamDevice = false))
        assertEquals(4, MatrixCapacityGate.decideMaxWindows(totalRamBytes = 6 * gb, isLowRamDevice = false))
        // 6.9GB（MemTotal 刻度）不进扩展档——阈值留余量不压线
        assertEquals(4, MatrixCapacityGate.decideMaxWindows(totalRamBytes = 7 * gb - 1, isLowRamDevice = false))
        assertEquals(6, MatrixCapacityGate.decideMaxWindows(totalRamBytes = 7 * gb, isLowRamDevice = false))
        assertEquals(6, MatrixCapacityGate.decideMaxWindows(totalRamBytes = 16 * gb, isLowRamDevice = false))
    }

    /** 探测无效（读数失败/非法值）：保守取基准 4 窗，不上浮不误缩 */
    @Test
    fun maxWindows_invalidProbeFallsBackToBaseline() {
        assertEquals(4, MatrixCapacityGate.decideMaxWindows(totalRamBytes = 0, isLowRamDevice = false))
        assertEquals(4, MatrixCapacityGate.decideMaxWindows(totalRamBytes = -1, isLowRamDevice = false))
    }

    /** 强制降级目标：「自动降 2 窗」按当前档位缩放 */
    @Test
    fun degradeTarget_scalesWithCurrentCount() {
        assertEquals(4, MatrixDegrade.degradeTarget(6))
        assertEquals(3, MatrixDegrade.degradeTarget(5))
        assertEquals(2, MatrixDegrade.degradeTarget(4))
        assertEquals(2, MatrixDegrade.degradeTarget(3))
        assertEquals(2, MatrixDegrade.degradeTarget(2))
    }

    /** 会话恢复设备钳制：高配机存的 6 窗在低配机收敛到本机档位 */
    @Test
    fun sessionClampToDeviceMaxWindows() {
        val highEndSaved = MatrixSessionState(windowCount = 6, cells = List(6) { MatrixCellState() })
        val clamped = highEndSaved.clampToMaxWindows(4)
        assertEquals(4, clamped.windowCount)
        assertEquals(4, clamped.cells.size)

        // 档位内会话原样返回（不重建对象）
        val within = MatrixSessionState(windowCount = 3, cells = List(3) { MatrixCellState() })
        assertTrue(within.clampToMaxWindows(6) === within)
    }

    // ===== 适应宽度缩放（fit zoom） =====

    /** 半格适配：168px 格子 / 360px 单屏 → 47%→收敛 30 下限之上取整口径 */
    @Test
    fun fitZoom_halfCellOnFullWidth() {
        assertEquals(46, MatrixEngine.fitZoomPercent(cellWidthCss = 168, hostWidthCss = 360))
    }

    /** 全宽格（放大态/单列布局）：fit=100 与宿主同源；窄到极限收敛 30 下限 */
    @Test
    fun fitZoom_clampsBothEnds() {
        assertEquals(100, MatrixEngine.fitZoomPercent(cellWidthCss = 360, hostWidthCss = 360))
        assertEquals(100, MatrixEngine.fitZoomPercent(cellWidthCss = 400, hostWidthCss = 360))
        assertEquals(30, MatrixEngine.fitZoomPercent(cellWidthCss = 90, hostWidthCss = 360))
    }

    /** 非法输入回退 100（同源），不产生 0/负缩放 */
    @Test
    fun fitZoom_invalidInputsFallBackToSameSource() {
        assertEquals(100, MatrixEngine.fitZoomPercent(cellWidthCss = 0, hostWidthCss = 360))
        assertEquals(100, MatrixEngine.fitZoomPercent(cellWidthCss = 168, hostWidthCss = -1))
    }

    /** 列数判定：2 窗/4-6 窗均 2 列；3 窗上 2 列下 1 列 */
    @Test
    fun columnCount_layoutAware() {
        assertEquals(2, MatrixEngine.columnCountOf(2, 0))
        assertEquals(2, MatrixEngine.columnCountOf(3, 0))
        assertEquals(1, MatrixEngine.columnCountOf(3, 2))
        assertEquals(2, MatrixEngine.columnCountOf(4, 3))
        assertEquals(2, MatrixEngine.columnCountOf(6, 5))
    }

    // ===== 主帧加载失败转错误态（onCellLoadFailed 状态机） =====

    /** LOADING/ACTIVE 都可转 ERROR（缓存/重定向时序下 finished 先置 ACTIVE，error 后到） */
    @Test
    fun failureTransitional_coversLoadingAndActive() {
        assertTrue(MatrixEngine.isFailureTransitional(MatrixCellUiState.LOADING))
        assertTrue(MatrixEngine.isFailureTransitional(MatrixCellUiState.ACTIVE))
    }

    /** 占位/容量受限/错误态不可转：闸门语义不被覆盖，错误态幂等 */
    @Test
    fun failureTransitional_excludesPlaceholderCapacityAndError() {
        assertFalse(MatrixEngine.isFailureTransitional(MatrixCellUiState.PLACEHOLDER))
        assertFalse(MatrixEngine.isFailureTransitional(MatrixCellUiState.CAPACITY_LIMITED))
        assertFalse(MatrixEngine.isFailureTransitional(MatrixCellUiState.ERROR))
    }
}
