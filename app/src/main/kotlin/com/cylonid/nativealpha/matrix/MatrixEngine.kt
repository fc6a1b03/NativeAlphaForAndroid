package com.cylonid.nativealpha.matrix

import android.content.ComponentCallbacks2
import android.content.Context
import android.webkit.WebView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.FeatureMetrics
import com.cylonid.nativealpha.util.LocaleUtils
import com.cylonid.nativealpha.util.WebViewSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val state: MatrixCellUiState = MatrixCellUiState.PLACEHOLDER,
    val webappId: Int = MatrixCellState.PLACEHOLDER_WEBAPP_ID,
    val title: String = ""
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
internal enum class MatrixNotice { DEGRADED, CRASH_BACKOFF, HTTP_BLOCKED }

/**
 * 多窗矩阵引擎（P4 编排层，activity 作用域）。
 *
 * 线程纪律（总纲决策）：
 * - WebView 创建/loadUrl/销毁一律主线程（WebView 是 UI 组件）
 * - PSS 采样/DataStore 读写走 Dispatchers.IO
 * - UI 只认五态机 StateFlow，闸门判定/降级/退避结论以状态到达
 *
 * 内存三层分工：闸门管事前（MatrixCapacityGate fail-open）、onTrimMemory
 * 守卫管事中、崩溃恢复管事后（D3/A 批量静默重载 + 退避）。
 *
 * 生命周期（D8）：Activity onDestroy 全量 releaseCell，进程内不驻留；
 * Cookie（D1）矩阵内全局共享零快照操作。
 */
internal class MatrixEngine(
    private val lifecycleOwner: LifecycleOwner,
    private val appContext: Context
) {

    /** Activity Context（对话框/Intent 用；matrix 格的 siteContext） */
    internal val activityContext: Context get() = lifecycleOwner as Context

    /** 顶栏警示条可见性（onTrimMemory ≥ RUNNING_LOW，忽略后本会话不弹） */
    private val _memoryWarningVisible = MutableStateFlow(false)
    val memoryWarningVisible: StateFlow<Boolean> = _memoryWarningVisible.asStateFlow()

    /** 一次性告知事件（强制降级/崩溃退避/明文拦截） */
    private val _notices = MutableSharedFlow<MatrixNotice>(extraBufferCapacity = 8)
    val notices: SharedFlow<MatrixNotice> = _notices.asSharedFlow()

    /** 设备不足（QB 入口预检/闸门首窗拦截）：整页劝退提示 */
    private val _deviceUnsupported = MutableStateFlow(false)
    val deviceUnsupported: StateFlow<Boolean> = _deviceUnsupported.asStateFlow()

    /** 原地放大的窗格下标（D4：null=网格态；放大态顶栏隐藏由 UI 处理） */
    private val _zoomedCellIndex = MutableStateFlow<Int?>(null)
    val zoomedCellIndex: StateFlow<Int?> = _zoomedCellIndex.asStateFlow()

    private val _windowCount = MutableStateFlow(MatrixSessionState.DEFAULT_WINDOW_COUNT)
    val windowCount: StateFlow<Int> = _windowCount.asStateFlow()

    private val _cells =
        MutableStateFlow(List(MatrixSessionState.DEFAULT_WINDOW_COUNT) { MatrixCellUi() })
    val cells: StateFlow<List<MatrixCellUi>> = _cells.asStateFlow()

    /** 宿主窗格持有的 WebView（下标对齐 cells；占位为 null） */
    private val cellWebViews = arrayOfNulls<WebView>(MatrixSessionState.MAX_WINDOW_COUNT)

    /** 错峰串行加载任务（格释放/窗口收缩时取消，防竞态） */
    private val staggeredLoadJobs = arrayOfNulls<Job>(MatrixSessionState.MAX_WINDOW_COUNT)

    /** 容量闸门输入：每窗边际成本 EMA（onPageFinished 回采校准） */
    private var perCellBytes = 0L

    /** 崩溃退避（D3/A） */
    private val crashBackoff = MatrixCrashBackoff()

    /** 警示条本会话已弹标记（忽略后本会话不弹） */
    private var memoryWarningShown = false

    /** 主线程调度（引擎全部可变状态只在主线程触碰） */
    private val mainScope get() = lifecycleOwner.lifecycleScope

    // ===== 会话恢复（进入矩阵调用一次） =====

    /**
     * 从 DataStore 恢复布局：归一化 → 已删站点过滤 → 窗格回填。
     * 已删站点格自动回占位（规格 §4.2）；恢复只还原布局，页面统一重载（D8）。
     */
    fun restoreSession() {
        mainScope.launch {
            val session = withContext(Dispatchers.IO) { MatrixSessionStore.read(appContext) }
            val restored = session.normalized().filteredByActiveSites(activeSiteIds())
            _windowCount.value = restored.windowCount
            _cells.value = restored.cells.map { cell ->
                if (cell.isPlaceholder) MatrixCellUi() else MatrixCellUi(
                    webappId = cell.webappId
                )
            }
        }
    }

    // ===== 顶栏 Slider：增减窗（D2） =====

    /**
     * 增窗：尾部追加占位格（UI animateContentSize 过渡）。
     * 不做容量预检——占位格不占渲染内存，加载时才过闸门。
     */
    fun addWindow() {
        if (_windowCount.value >= MatrixSessionState.MAX_WINDOW_COUNT) return
        _windowCount.value += 1
        _cells.value = _cells.value + MatrixCellUi()
        persistSession()
    }

    /**
     * 减窗申请：尾部格为占位直接减；有内容由 UI 弹确认后调 [reduceWindowConfirmed]。
     * @return true=已直接执行；false=需 UI 确认（规格：减窗尾部有内容弹 AlertDialog）
     */
    fun requestReduceWindow(): Boolean {
        if (_windowCount.value <= MatrixSessionState.MIN_WINDOW_COUNT) return true
        val tail = _cells.value[_windowCount.value - 1]
        return if (tail.state == MatrixCellUiState.PLACEHOLDER) {
            reduceWindowConfirmed()
            true
        } else {
            false
        }
    }

    /** 确认减窗：释放尾部格再收缩（releaseCell 四步不可省） */
    fun reduceWindowConfirmed() {
        if (_windowCount.value <= MatrixSessionState.MIN_WINDOW_COUNT) return
        val tailIndex = _windowCount.value - 1
        releaseCell(tailIndex)
        _windowCount.value -= 1
        _cells.value = _cells.value.dropLast(1)
        if (_zoomedCellIndex.value == tailIndex) _zoomedCellIndex.value = null
        persistSession()
    }

    // ===== 窗格选择、重试与释放 =====

    /**
     * 选择器确认：闸门比对（D7）→ 放行则窗格进加载态并错峰加载。
     * 闸门只拦 WebView 不拦 UI——窗格立即显示加载态，比对在错峰窗口内完成。
     */
    fun pickSite(cellIndex: Int, webappId: Int) {
        if (cellIndex >= _windowCount.value) return
        val webapp = DataManager.getInstance().getWebApp(webappId) ?: return
        if (webapp.baseUrl.startsWith("http://") && !webapp.isAllowHttp) {
            // 明文站点未授权：矩阵内不静默放宽安全设置（与宿主 loadURL 同源语义）
            _cells.value = _cells.value.mapIndexed { i, cell ->
                if (i == cellIndex) MatrixCellUi() else cell
            }
            _notices.tryEmit(MatrixNotice.HTTP_BLOCKED)
            return
        }
        _cells.value = _cells.value.mapIndexed { i, cell ->
            if (i == cellIndex) {
                MatrixCellUi(state = MatrixCellUiState.LOADING, webappId = webappId)
            } else {
                cell
            }
        }
        mainScope.launch {
            val budget = withContext(Dispatchers.IO) { readBudget() }
            when (MatrixCapacityGate.decide(countBusyCells(), budget)) {
                MatrixCapacityGate.Decision.Allow -> loadCell(cellIndex, webapp)
                MatrixCapacityGate.Decision.DeviceUnsupported -> {
                    _deviceUnsupported.value = true
                    _cells.value = _cells.value.mapIndexed { i, cell ->
                        if (i == cellIndex) MatrixCellUi() else cell
                    }
                }
                MatrixCapacityGate.Decision.LimitCell -> {
                    _cells.value = _cells.value.mapIndexed { i, cell ->
                        if (i == cellIndex) {
                            MatrixCellUi(
                                state = MatrixCellUiState.CAPACITY_LIMITED,
                                webappId = webappId
                            )
                        } else {
                            cell
                        }
                    }
                }
            }
        }
    }

    /** 容量受限/错误格点击：错误=回占位重选（手动重试重置退避）；受限=重过闸门 */
    fun retryCell(cellIndex: Int) {
        val cell = _cells.value.getOrNull(cellIndex) ?: return
        when (cell.state) {
            MatrixCellUiState.ERROR -> {
                crashBackoff.onManualRetry()
                resetCell(cellIndex)
            }
            MatrixCellUiState.CAPACITY_LIMITED -> {
                val webappId = cell.webappId
                _cells.value = _cells.value.mapIndexed { i, c ->
                    if (i == cellIndex) MatrixCellUi(state = MatrixCellUiState.LOADING, webappId = webappId) else c
                }
                mainScope.launch {
                    val budget = withContext(Dispatchers.IO) { readBudget() }
                    val webapp = DataManager.getInstance().getWebApp(webappId)
                    if (webapp == null) {
                        resetCell(cellIndex)
                        return@launch
                    }
                    when (MatrixCapacityGate.decide(countBusyCells(), budget)) {
                        MatrixCapacityGate.Decision.Allow -> loadCell(cellIndex, webapp)
                        MatrixCapacityGate.Decision.DeviceUnsupported -> {
                            _deviceUnsupported.value = true
                            resetCell(cellIndex)
                        }
                        MatrixCapacityGate.Decision.LimitCell -> {
                            _cells.value = _cells.value.mapIndexed { i, c ->
                                if (i == cellIndex) c.copy(state = MatrixCellUiState.CAPACITY_LIMITED) else c
                            }
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    /** 错误/受限格显式回占位（规格迁移：错误→占位） */
    fun resetCell(cellIndex: Int) {
        releaseCell(cellIndex)
        _cells.value = _cells.value.mapIndexed { i, cell ->
            if (i == cellIndex) MatrixCellUi() else cell
        }
        persistSession()
    }

    /** 关闭窗格回占位（工具条 close）：releaseCell 确定性释放 */
    fun closeCell(cellIndex: Int) = resetCell(cellIndex)

    // ===== 原地放大（D4） =====

    fun enlargeCell(cellIndex: Int) {
        if (_cells.value.getOrNull(cellIndex)?.state == MatrixCellUiState.ACTIVE) {
            _zoomedCellIndex.value = cellIndex
        }
    }

    fun collapseZoom() {
        _zoomedCellIndex.value = null
    }

    /**
     * UI 挂载点（AndroidView factory）：返回该格 WebView 实例；跨槽位
     * 复用（放大/网格切换布局重建）先脱离旧父容器，同实例不重载。
     * 兜底空 View：仅命中状态竞态的瞬时空窗（正常路径 ACTIVE 必有实例）。
     */
    internal fun attachCellWebView(cellIndex: Int): android.view.View {
        val webview = cellWebViews.getOrNull(cellIndex)
            ?: return android.view.View(appContext)
        (webview.parent as? android.view.ViewGroup)?.removeView(webview)
        return webview
    }

    // ===== 加载执行（主线程 WebView 操作 + Q8 错峰） =====

    /**
     * 创建 WebView 并错峰 loadUrl（多窗同时就绪时 ~150ms 间隔串行，
     * 兼任闸门比对窗口；Q8 参数实测定参入口 [STAGGER_LOAD_MS]）。
     */
    private fun loadCell(cellIndex: Int, webapp: WebApp) {
        staggeredLoadJobs.getOrNull(cellIndex)?.cancel()
        staggeredLoadJobs[cellIndex] = mainScope.launch {
            // 错峰：忙格数 × 间隔（首窗立即；闸门比对已在此窗口内完成）
            delay(countBusyCells() * STAGGER_LOAD_MS)
            if (_cells.value.getOrNull(cellIndex)?.state != MatrixCellUiState.LOADING) {
                return@launch // 期间被关闭/重置：放弃本次加载
            }
            val webview = createCellWebView(cellIndex, webapp)
            cellWebViews[cellIndex] = webview
            FeatureMetrics.count("matrix", "cell_load")
            webview.loadUrl(webapp.baseUrl, buildLoadHeaders(webapp))
        }
    }

    private fun createCellWebView(cellIndex: Int, webapp: WebApp): WebView {
        val webview = WebView(appContext)
        WebViewSetup.applySiteSettings(webview, webapp)
        webview.webViewClient = MatrixCellClient(MatrixCellContext(this, cellIndex, webapp.ID))
        webview.webChromeClient = MatrixCellChromeClient(this, cellIndex)
        return webview
    }

    /** 页面开始加载（client 回调）：重置格标题（新导航） */
    internal fun onCellLoadStarted(cellIndex: Int) {
        _cells.value = _cells.value.mapIndexed { i, cell ->
            if (i == cellIndex) cell.copy(title = "") else cell
        }
    }

    /** 页面完成（client 回调）：活跃态；PSS 回采校准（IO，粗值可接受） */
    internal fun onCellPageFinished(cellIndex: Int) {
        val current = _cells.value.getOrNull(cellIndex) ?: return
        if (current.state != MatrixCellUiState.LOADING) return
        _cells.value = _cells.value.mapIndexed { i, cell ->
            if (i == cellIndex) cell.copy(state = MatrixCellUiState.ACTIVE) else cell
        }
        mainScope.launch {
            val pss = withContext(Dispatchers.IO) {
                MatrixMemorySampler.rendererPssBytes(appContext)
            }
            if (pss > 0) {
                perCellBytes = MatrixCapacityGate.calibratePerCell(
                    perCellBytes, pss / countBusyCells().coerceAtLeast(1)
                )
            }
        }
    }

    /** 页面标题（chrome client 回调）：活跃/加载格更新工具条标题 */
    internal fun onCellTitle(cellIndex: Int, title: String) {
        if (title.isBlank()) return
        _cells.value = _cells.value.mapIndexed { i, cell ->
            if (i == cellIndex) cell.copy(title = title) else cell
        }
    }

    /** 主框架加载失败（非崩溃）：格回错误态（点击重新选择） */
    internal fun onCellLoadFailed(cellIndex: Int) {
        if (_cells.value.getOrNull(cellIndex)?.state == MatrixCellUiState.LOADING) {
            releaseCell(cellIndex)
            markCellError(cellIndex)
        }
    }

    /**
     * 渲染进程崩溃（D3/A）：共享渲染进程全窗同死是平台事实——每个格的
     * client 各收到一次回调，per-cell 只清理自己；批量静默恢复错误格；
     * 退避生效时停手保持错误态 + 事件告知。
     */
    internal fun onRenderGone(cellIndex: Int) {
        releaseCell(cellIndex)
        markCellError(cellIndex)
        if (crashBackoff.onCrash(System.currentTimeMillis())) {
            _cells.value.forEachIndexed { index, cell ->
                if (cell.state == MatrixCellUiState.ERROR &&
                    cell.webappId != MatrixCellState.PLACEHOLDER_WEBAPP_ID
                ) {
                    mainScope.launch {
                        delay(STAGGER_LOAD_MS)
                        // 期间可能已被用户重置：复核再恢复
                        if (_cells.value.getOrNull(index)?.state == MatrixCellUiState.ERROR) {
                            pickSite(index, cell.webappId)
                        }
                    }
                }
            }
        } else {
            _notices.tryEmit(MatrixNotice.CRASH_BACKOFF)
        }
    }

    private fun markCellError(cellIndex: Int) {
        _cells.value = _cells.value.mapIndexed { i, cell ->
            if (i == cellIndex) cell.copy(state = MatrixCellUiState.ERROR) else cell
        }
    }

    // ===== 内存守卫（事中） =====

    /**
     * onTrimMemory 分级响应（规格 §4.2）：
     * ≥ RUNNING_LOW 顶栏警示条（忽略后本会话不弹）；
     * ≥ COMPLETE 自动降 2 窗 + Snackbar 告知（系统行为不询问）。
     */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            forceDegradeToTwoWindows()
        } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            if (!memoryWarningShown) {
                memoryWarningShown = true
                _memoryWarningVisible.value = true
            }
        }
    }

    /** 用户点掉警示条：本会话不再弹 */
    fun dismissMemoryWarning() {
        _memoryWarningVisible.value = false
    }

    /**
     * 强制降级（D9）：2 窗网格只有槽位 0/1——槽位内的活跃格保留，其余
     * 一切格（含槽位 2+ 的活跃格，物理上无槽位可容纳）释放收敛为占位。
     * 保留/释放规则抽为纯函数 [MatrixDegrade.keptCells]/[MatrixDegrade.releaseIndices]
     * （确定性，可单测）。
     */
    private fun forceDegradeToTwoWindows() {
        if (_windowCount.value <= MatrixSessionState.MIN_WINDOW_COUNT) return
        val current = _cells.value
        MatrixDegrade.releaseIndices(current, MatrixSessionState.MIN_WINDOW_COUNT)
            .forEach { releaseCell(it) }
        _windowCount.value = MatrixSessionState.MIN_WINDOW_COUNT
        _cells.value = MatrixDegrade.keptCells(current, MatrixSessionState.MIN_WINDOW_COUNT)
        _zoomedCellIndex.value = null
        _memoryWarningVisible.value = false
        _notices.tryEmit(MatrixNotice.DEGRADED)
        persistSession()
    }

    // ===== 生命周期（D8） =====

    /** onPause：全窗 onPause()（页面级暂停） */
    fun onPauseCells() {
        cellWebViews.forEach { it?.onPause() }
    }

    /** onResume：全窗对称恢复 */
    fun onResumeCells() {
        cellWebViews.forEach { it?.onResume() }
    }

    /** onStop：pauseTimers 全局暂停（矩阵整页后台语义一致） */
    fun stopCellTimers() {
        cellWebViews.forEach { it?.pauseTimers() }
    }

    /** onStart：全局对称恢复 */
    fun resumeCellTimers() {
        cellWebViews.forEach { it?.resumeTimers() }
    }

    /** onDestroy：全量 releaseCell，进程内不驻留 WebView（D8） */
    fun releaseAll() {
        staggeredLoadJobs.forEach { it?.cancel() }
        cellWebViews.forEachIndexed { index, _ -> releaseCell(index) }
    }

    // ===== 内部工具 =====

    /**
     * releaseCell 四步（确定性释放，触发点：close/减窗/降级/崩溃清理/
     * onDestroy）：cancel 挂起加载 → stopLoading → 层级移除 → onPause →
     * destroy。重用一律新实例，不做对象池（总纲「不做预热池」）。
     */
    private fun releaseCell(cellIndex: Int) {
        staggeredLoadJobs.getOrNull(cellIndex)?.cancel()
        staggeredLoadJobs[cellIndex] = null
        val webview = cellWebViews.getOrNull(cellIndex) ?: return
        cellWebViews[cellIndex] = null
        try {
            webview.stopLoading()
            (webview.parent as? android.view.ViewGroup)?.removeView(webview)
            webview.onPause()
            webview.destroy()
        } catch (ignored: Exception) {
            // 销毁竞态（层级/内核态异常）：释放纪律优先，不阻断其余格
        }
    }

    private fun countBusyCells(): Int = _cells.value.count {
        it.state == MatrixCellUiState.ACTIVE || it.state == MatrixCellUiState.LOADING
    }

    private fun activeSiteIds(): Set<Int> =
        DataManager.getInstance().webAppsFlow.value.items
            .filter { it.isActiveEntry }
            .map { it.ID }
            .toSet()

    private fun readBudget(): MatrixCapacityGate.Budget? {
        val avail = MatrixMemorySampler.availMemBytes(appContext)
        val threshold = MatrixMemorySampler.lowMemoryThresholdBytes(appContext)
        if (avail <= 0 || threshold <= 0) return null // 系统读数失败 → fail-open
        return MatrixCapacityGate.Budget(
            totalBytes = avail - threshold,
            perCellBytes = perCellBytes
        )
    }

    private fun persistSession() {
        val snapshot = MatrixSessionState(
            windowCount = _windowCount.value,
            cells = _cells.value.map { MatrixCellState(it.webappId) }
        )
        mainScope.launch {
            withContext(Dispatchers.IO) { MatrixSessionStore.write(appContext, snapshot) }
        }
    }

    /** 窗格加载头（与宿主 initCustomHeaders 同源：DNT/UA 清标/语言/省流） */
    private fun buildLoadHeaders(webapp: WebApp): Map<String, String> {
        val headers = HashMap<String, String>()
        headers["DNT"] = "1"
        headers["X-REQUESTED-WITH"] = ""
        headers["Accept-Language"] = LocaleUtils.acceptLanguage
        if (webapp.isSendSavedataRequest) headers["Save-Data"] = "on"
        return headers
    }

    companion object {
        /** Q8 错峰间隔（~150ms 规格值；实测定参入口） */
        const val STAGGER_LOAD_MS = 150L
    }
}
