package com.cylonid.nativealpha.matrix

import android.content.Context
import androidx.lifecycle.lifecycleScope
import com.cylonid.nativealpha.model.DataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 矩阵会话网关（D8 持久化边界）：会话恢复（进矩阵一次）+ 快照单写者
 * 落盘队列。引擎只暴露状态流写口，磁盘细节收在此处。
 */
internal class MatrixSessionGateway(private val engine: MatrixEngine) {

    private val appContext: Context get() = engine.appContext

    /** 持久化单写者队列（conflate：写期间的新快照合并，最新胜出且有序） */
    private val persistQueue = MutableStateFlow<MatrixSessionState?>(null)

    /** 引擎 init 调用：启动落盘队列消费（连续变更只保留最新快照且按序写） */
    fun startPersistCollector() {
        engine.mainScope.launch {
            persistQueue.collect { snapshot ->
                if (snapshot != null) {
                    withContext(Dispatchers.IO) {
                        MatrixSessionStore.write(appContext, snapshot)
                    }
                }
            }
        }
    }

    /**
     * 从 DataStore 恢复布局：归一化 → 已删站点过滤 → 窗格回填 → 绑定格
     * 自动重载（D8：退出即释放，再进「布局在、页面重载」——重载走
     * pickSite 完整闸门/错峰链路，不绕过容量守卫）。已删站点格自动回占位。
     * 恢复完成后跑 QB 入口预检（与闸门同一判定函数，fail-open）：预算连
     * 首窗边际都盖不住 → 整页劝退，不让用户见空网格后逐格碰壁。
     *
     * 竞态防护（release 实测）：站点过滤必须等 DataManager 首次加载完成
     * （revision>0）——App.onCreate 预热是后台线程，R8 下 DataStore 读取
     * 可能快于列表加载，拿空 items 过滤会把全部绑定格误清成占位。
     */
    fun restoreSession() {
        engine.mainScope.launch {
            val session = withContext(Dispatchers.IO) { MatrixSessionStore.read(appContext) }
            val loaded = DataManager.getInstance().webAppsFlow
                .first { it.revision > 0 }
            val activeIds = loaded.items
                .filter { it.isActiveEntry }
                .map { it.ID }
                .toSet()
            val restored = session.normalized()
                .clampToMaxWindows(engine.maxWindows)
                .filteredByActiveSites(activeIds)
            engine.windowCountInternal.value = restored.windowCount
            engine.cellsInternal.value = restored.cells.map { cell ->
                MatrixCellUi(
                    uid = engine.newCellUid(),
                    webappId = cell.webappId,
                    // 会话遗留的极端缩放值（历史误操作/旧版 fit）钳回可靠
                    // 区间：44% 这类值会让 chromium 绘制截断（只画上半）
                    zoomPercent = MatrixCellState.clampZoomPercent(cell.zoomPercent),
                    textZoomPercent = cell.textZoomPercent
                )
            }
            // QB 入口预检（边际未就绪时 readPerCellBytes 仍在异步载入——
            // fail-open 语义下预检放行，真正的拦截由首窗闸门兜底）
            val budget = withContext(Dispatchers.IO) { engine.readBudget() }
            if (MatrixCapacityGate.decide(0, budget) == MatrixCapacityGate.Decision.DeviceUnsupported) {
                engine.deviceUnsupportedInternal.value = true
                return@launch
            }
            restored.cells.forEachIndexed { index, cell ->
                if (!cell.isPlaceholder) engine.pickSite(index, cell.webappId)
            }
        }
    }

    /** 变更即写（D8）：单写者队列——各自发射独立协程在 IO 上不保序，
     * 旧快照可能覆盖新快照（实测踩坑：4 窗状态被前一档 3 窗快照回写） */
    fun persistSession() {
        val snapshot = MatrixSessionState(
            windowCount = engine.windowCountInternal.value,
            cells = engine.cellsInternal.value.map {
                MatrixCellState(it.webappId, it.zoomPercent, it.textZoomPercent)
            }
        )
        persistQueue.value = snapshot
    }
}
