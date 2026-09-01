package com.cylonid.nativealpha.matrix

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.webkit.WebView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.FeatureMetrics
import com.cylonid.nativealpha.util.SiteReconnectSupervisor
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
import kotlinx.coroutines.flow.first
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
        MutableStateFlow(List(MatrixSessionState.DEFAULT_WINDOW_COUNT) { MatrixCellUi(uid = newCellUid()) })
    val cells: StateFlow<List<MatrixCellUi>> = _cells.asStateFlow()

    /** 宿主窗格持有的 WebView（下标对齐 cells；占位为 null） */
    private val cellWebViews = arrayOfNulls<WebView>(MatrixSessionState.MAX_WINDOW_COUNT)

    /** 错峰串行加载任务（格释放/窗口收缩时取消，防竞态） */
    private val staggeredLoadJobs = arrayOfNulls<Job>(MatrixSessionState.MAX_WINDOW_COUNT)

    /**
     * 设备呈现档位（本机 Slider 上限）：按真实资源探测一次——RAM < 4GB
     * 或官方低内存设备收缩 3 窗，≥ 8GB 放开 6 窗，其余基准 4 窗（阈值
     * 纪律见 [MatrixCapacityGate.decideMaxWindows]）。这是「预读机器资源
     * 做矩阵呈现计算」的呈现侧：加窗/恢复钳制都以此为准。
     */
    val maxWindows: Int = run {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        MatrixCapacityGate.decideMaxWindows(info.totalMem, am != null && am.isLowRamDevice)
    }

    /** 容量闸门输入：每窗边际成本 EMA（onPageFinished 回采校准） */
    private var perCellBytes = 0L

    /** 断线自动恢复监督者（连接治理：探测可达后自动 pickSite 重载） */
    private val reconnectSupervisors =
        arrayOfNulls<SiteReconnectSupervisor>(MatrixSessionState.MAX_WINDOW_COUNT)

    /** 启动指定格的断线探测（目标=站点 baseUrl，恢复=pickSite 完整链路） */
    internal fun startCellReconnect(cellIndex: Int) {
        val cell = _cells.value.getOrNull(cellIndex) ?: return
        val webappId = cell.webappId
        // 占位=PLACEHOLDER_WEBAPP_ID(-1)；webappId=0 是合法 ID（首个站点）
        if (webappId < 0) return
        val baseUrl = DataManager.getInstance().getWebApp(webappId)?.baseUrl ?: return
        stopCellReconnect(cellIndex)
        val supervisor = SiteReconnectSupervisor(appContext, mainScope)
        reconnectSupervisors[cellIndex] = supervisor
        supervisor.start(baseUrl) {
            mainScope.launch {
                if (_cells.value.getOrNull(cellIndex)?.state == MatrixCellUiState.ERROR) {
                    pickSite(cellIndex, webappId)
                }
            }
        }
    }

    internal fun stopCellReconnect(cellIndex: Int) {
        reconnectSupervisors.getOrNull(cellIndex)?.stop()
        reconnectSupervisors[cellIndex] = null
    }

    /** 崩溃退避（D3/A） */
    private val crashBackoff = MatrixCrashBackoff()

    /** 崩溃风暴去抖任务（同一渲染进程死亡的多格回调合并为一次事件） */
    private var burstSettleJob: Job? = null

    /** 格会话身份自增（拖拽排序的稳定 Compose key） */
    private var cellUidSeq = 0

    private fun newCellUid(): Int = ++cellUidSeq

    /** 警示条本会话已弹标记（忽略后本会话不弹） */
    private var memoryWarningShown = false

    /** 持久化单写者队列（conflate：写期间的新快照合并，最新胜出且有序） */
    private val persistQueue = MutableStateFlow<MatrixSessionState?>(null)

    init {
        // QB 前置：跨会话载入已校准的每窗边际成本（首装 ≤0 → 预检 fail-open）
        mainScope.launch {
            perCellBytes = withContext(Dispatchers.IO) {
                MatrixSessionStore.readPerCellBytes(appContext)
            }
        }
        mainScope.launch {
            persistQueue.collect { snapshot ->
                if (snapshot != null) {
                    withContext(Dispatchers.IO) {
                        MatrixSessionStore.write(appContext, snapshot)
                    }
                }
            }
        }
    }
    /** 主线程调度（引擎全部可变状态只在主线程触碰） */
    private val mainScope get() = lifecycleOwner.lifecycleScope

    // ===== 会话恢复（进入矩阵调用一次） =====

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
        mainScope.launch {
            val session = withContext(Dispatchers.IO) { MatrixSessionStore.read(appContext) }
            val loaded = DataManager.getInstance().webAppsFlow
                .first { it.revision > 0 }
            val activeIds = loaded.items
                .filter { it.isActiveEntry }
                .map { it.ID }
                .toSet()
            val restored = session.normalized()
                .clampToMaxWindows(maxWindows)
                .filteredByActiveSites(activeIds)
            _windowCount.value = restored.windowCount
            _cells.value = restored.cells.map { cell ->
                MatrixCellUi(
                    uid = newCellUid(),
                    webappId = cell.webappId,
                    // 会话遗留的极端缩放值（历史误操作/旧版 fit）钳回可靠
                    // 区间：44% 这类值会让 chromium 绘制截断（只画上半）
                    zoomPercent = MatrixCellState.clampZoomPercent(cell.zoomPercent),
                    textZoomPercent = cell.textZoomPercent
                )
            }
            // QB 入口预检（边际未就绪时 readPerCellBytes 仍在异步载入——
            // fail-open 语义下预检放行，真正的拦截由首窗闸门兜底）
            val budget = withContext(Dispatchers.IO) { readBudget() }
            if (MatrixCapacityGate.decide(0, budget) == MatrixCapacityGate.Decision.DeviceUnsupported) {
                _deviceUnsupported.value = true
                return@launch
            }
            restored.cells.forEachIndexed { index, cell ->
                if (!cell.isPlaceholder) pickSite(index, cell.webappId)
            }
        }
    }

    // ===== 顶栏 Slider：增减窗（D2） =====

    /**
     * 增窗：尾部追加占位格（UI animateContentSize 过渡）。
     * 不做容量预检——占位格不占渲染内存，加载时才过闸门。
     */
    fun addWindow() {
        if (_windowCount.value >= maxWindows) return
        _windowCount.value += 1
        _cells.value = _cells.value + MatrixCellUi(uid = newCellUid())
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
        // HTTP 明文站点矩阵内默认放行（用户定调：不弹「未允许打开 HTTP
        // 页面」提示）——明文能力本就由 usesCleartextTraffic 应用级开关
        // 放开，宿主的逐站确认属单屏交互，矩阵内不做二次拦截
        _cells.value = _cells.value.mapIndexed { i, cell ->
            if (i == cellIndex) {
                cell.copy(uid = cell.uid.takeIf { it != 0 } ?: newCellUid(),
                    state = MatrixCellUiState.LOADING, webappId = webappId)
            } else {
                cell
            }
        }
        // 选站即落盘（D8 变更即写）：选器确认是布局变更，缺失会导致强停后
        // 恢复丢失绑定（release 实测：pick 后强停，磁盘从未记录绑定 → 恢复
        // 全占位——debug 此前未暴露因历史测试恰好有增窗操作代写）
        persistSession()
        mainScope.launch {
            val budget = withContext(Dispatchers.IO) { readBudget() }
            when (MatrixCapacityGate.decide(countBusyCells(), budget)) {
                MatrixCapacityGate.Decision.Allow -> loadCell(cellIndex, webapp)
                MatrixCapacityGate.Decision.DeviceUnsupported -> {
                    _deviceUnsupported.value = true
                    _cells.value = _cells.value.mapIndexed { i, cell ->
                        if (i == cellIndex) MatrixCellUi(uid = newCellUid()) else cell
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
            if (i == cellIndex) MatrixCellUi(uid = newCellUid()) else cell
        }
        persistSession()
    }

    /** 关闭窗格回占位（工具条 close）：releaseCell 确定性释放 */
    fun closeCell(cellIndex: Int) = resetCell(cellIndex)

    /**
     * 加载取消（用户点「取消加载」）：销毁在途 WebView 回占位，可重新
     * 选站。LOADING 守卫防与 onPageFinished 竞态——完成先到则格子已
     * ACTIVE、按钮已消失；取消先到则 finished 回调被状态检查挡住且
     * releaseCell 已清 cellWebViews（evaluateJavascript 空引用安全）。
     * 错峰任务由 releaseCell 一并取消，不存在「取消后延迟到点又开窗」。
     */
    fun cancelLoading(cellIndex: Int) {
        if (_cells.value.getOrNull(cellIndex)?.state == MatrixCellUiState.LOADING) {
            resetCell(cellIndex)
        }
    }

    // ===== 拖拽排序（按住标题换位） =====

    /**
     * 交换两格的语义位置：cells 数据、WebView 实例、挂起加载任务三者
     * 同步交换——UI 以 cell.uid 为 Compose key，交换后组合按 key 移动/
     * 重建，AndroidView 重新 attach 交换后的实例（WebView 不销毁不重载，
     * 仅换宿主位置——拖拽性能红线：手势期间零 IO，松手一次交换）。
     */
    fun swapCells(a: Int, b: Int) {
        val count = _windowCount.value
        if (a == b || a !in 0 until count || b !in 0 until count) return
        val list = _cells.value.toMutableList()
        val tmpUi = list[a]
        list[a] = list[b]
        list[b] = tmpUi
        _cells.value = list

        val tmpWv = cellWebViews[a]
        cellWebViews[a] = cellWebViews[b]
        cellWebViews[b] = tmpWv

        val tmpJob = staggeredLoadJobs[a]
        staggeredLoadJobs[a] = staggeredLoadJobs[b]
        staggeredLoadJobs[b] = tmpJob

        persistSession()
    }

    /** 目标格映射：direction(dx,dy 主方向) 下按当前布局的相邻格；越界返回 -1 */
    fun neighborIndex(index: Int, dx: Float, dy: Float): Int {
        val count = _windowCount.value
        val horizontal = kotlin.math.abs(dx) >= kotlin.math.abs(dy)
        return when (count) {
            // 2 窗=左右分栏（用户定调默认并排）：横向换位
            2 -> when {
                horizontal && dx > 0 -> if (index == 0) 1 else -1
                horizontal && dx < 0 -> if (index == 1) 0 else -1
                else -> -1
            }
            3 -> when {
                horizontal && dx > 0 -> if (index == 0) 1 else -1
                horizontal && dx < 0 -> if (index == 1) 0 else -1
                !horizontal && dy > 0 -> if (index <= 1) 2 else -1
                !horizontal && dy < 0 -> if (index == 2) 0 else -1
                else -> -1
            }
            // 5 = 上 3 下 2（上排行内 0-1-2、下排行内 3-4；纵向 0↔3、1↔4）
            5 -> when {
                horizontal && dx > 0 -> when (index) {
                    0 -> 1
                    1 -> 2
                    3 -> 4
                    else -> -1
                }
                horizontal && dx < 0 -> when (index) {
                    1 -> 0
                    2 -> 1
                    4 -> 3
                    else -> -1
                }
                !horizontal && dy > 0 -> when (index) {
                    0 -> 3
                    1 -> 4
                    else -> -1
                }
                !horizontal && dy < 0 -> when (index) {
                    3 -> 0
                    4 -> 1
                    else -> -1
                }
                else -> -1
            }
            // 4（2×2）与 6（2×3）同构：每行 2 列，纵向 ±一整行
            else -> when {
                horizontal && dx > 0 -> if (index % 2 == 0) index + 1 else -1
                horizontal && dx < 0 -> if (index % 2 == 1) index - 1 else -1
                !horizontal && dy > 0 -> if (index / 2 < count / 2 - 1) index + 2 else -1
                !horizontal && dy < 0 -> if (index / 2 > 0) index - 2 else -1
                else -> -1
            }
        }
    }

    /** 格内显示调节（工具条调节 sheet 实时应用 + 持久化） */
    fun applyCellAdjust(cellIndex: Int, zoomPercent: Int, textZoomPercent: Int) {
        val old = _cells.value.getOrNull(cellIndex) ?: return
        _cells.value = _cells.value.mapIndexed { i, cell ->
            if (i == cellIndex) cell.copy(zoomPercent = MatrixCellState.clampZoomPercent(zoomPercent), textZoomPercent = textZoomPercent) else cell
        }
        // 实时应用：字体 textZoom 相对站点基准；页面缩放走 View 级适配
        // （UI 层按 zoomPercent 缩放渲染 + WebView 布局宽等比放大）——
        // CSS zoom 注入已废弃：实测它只缩放渲染不改变布局视口
        // （innerWidth 恒为格子宽，fixed/100vh 布局错乱且无法「按单屏布局」）
        cellWebViews.getOrNull(cellIndex)?.let { webview ->
            val webapp = old.webapp
            webview.settings.textZoom =
                ((webapp?.textZoom ?: 100) * textZoomPercent / 100f).toInt().coerceIn(50, 300)
        }
        persistSession()
    }

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
        // 矩阵格关闭离屏预栅格化（preraster ×4 = 离屏栅格/合成压力放大，
        // 实测 4 窗满载滚动 92.6% 卡顿帧 vs 单窗 10.6%——总纲定参条款落地）；
        // 不写全局 Cookie 接受开关（矩阵 D1 只读共享，防污染宿主站点）
        WebViewSetup.applySiteSettings(
            webview, webapp,
            enablePreRaster = false, configureGlobalCookieAccept = false
        )
        // Q1 反转（用户实测格子视口小）：启用捏合缩放；字体按格子留存值
        // 相对站点 textZoom 缩放（默认 90%「小一级」）
        webview.settings.setSupportZoom(true)
        webview.settings.builtInZoomControls = true
        webview.settings.displayZoomControls = false
        val cell = _cells.value.getOrNull(cellIndex)
        if (cell != null) {
            webview.settings.textZoom =
                ((webapp.textZoom) * cell.textZoomPercent / 100f).toInt().coerceIn(50, 300)
        }
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
        // 重载成功：断线探测闭环完成，停止监视（失败页的 finished 走
        // onCellLoadFailed 已转 ERROR，不会进入这里）
        stopCellReconnect(cellIndex)
        // 页面缩放为 View 级（MatrixScreen 按 zoomPercent 缩放渲染），新文档
        // 无需重放注入；textZoom 是 WebSettings 属性，导航后自动生效
        mainScope.launch {
            val pss = withContext(Dispatchers.IO) {
                MatrixMemorySampler.rendererPssBytes(appContext)
            }
            if (pss > 0) {
                perCellBytes = MatrixCapacityGate.calibratePerCell(
                    perCellBytes, pss / countBusyCells().coerceAtLeast(1)
                )
                // 校准值落盘（QB：跨会话供入口预检使用）
                withContext(Dispatchers.IO) {
                    MatrixSessionStore.writePerCellBytes(appContext, perCellBytes)
                }
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

    /**
     * 主框架加载失败（非崩溃）：格转错误态（点击重新选择）。
     * LOADING 与 ACTIVE 都要转：Chromium 对缓存命中/重定向链会先回调
     * onPageFinished（把格置 ACTIVE）再回调主帧 onReceivedError——只认
     * LOADING 会让真实失败被状态守卫挡掉，WebView 内置错误页残留在格内
     * （飞行模式实测复现）。子资源错误在 client 层已被 isForMainFrame
     * 过滤，ACTIVE 态到达必是页面级失败；CAPACITY_LIMITED 是闸门语义
     * 不覆盖，占位/错误态幂等不动。
     */
    internal fun onCellLoadFailed(cellIndex: Int) {
        val state = _cells.value.getOrNull(cellIndex)?.state ?: return
        if (isFailureTransitional(state)) {
            releaseCell(cellIndex)
            markCellError(cellIndex)
            // 放大格失败：退出放大视图让错误态正常渲染（与减窗路径同款联动）
            if (_zoomedCellIndex.value == cellIndex) _zoomedCellIndex.value = null
        }
    }

    /**
     * 渲染进程崩溃（D3/A）：共享渲染进程全窗同死是平台事实——每个格的
     * client 各收到一次回调，per-cell 只清理自己。**崩溃风暴去抖**：一次
     * 渲染进程死亡会引发 N 格串行回调（毫秒级连环到达），若逐回调计数会
     * 把「一次崩溃 ×4 窗」误判成 4 次崩溃、瞬间触发退避——故以
     * [CRASH_BURST_SETTLE_MS] 静默期合并为一次崩溃事件，静默期后统一
     * 登记退避 + 批量静默恢复全部错误格。
     */
    internal fun onRenderGone(cellIndex: Int) {
        releaseCell(cellIndex)
        markCellError(cellIndex)
        burstSettleJob?.cancel()
        burstSettleJob = mainScope.launch {
            delay(CRASH_BURST_SETTLE_MS)
            if (crashBackoff.onCrash(System.currentTimeMillis())) {
                reloadErrorCells()
            } else {
                _notices.tryEmit(MatrixNotice.CRASH_BACKOFF)
            }
        }
    }

    /** 批量静默恢复全部错误格（逐格错峰；恢复前复核状态防用户先动手） */
    private fun reloadErrorCells() {
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
     * ≥ COMPLETE 自动降档 + Snackbar 告知（系统行为不询问）。
     */
    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            forceDegrade()
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
     * 强制降级（D9）：内存告急自动减 2 窗（6→4/5→3/4→2/3→2，纯函数
     * [MatrixDegrade.degradeTarget]）——减窗幅度与原「4→2」决策同源，
     * 按当前档位缩放。槽位收缩后：槽位内活跃格保留，其余格（含槽位外
     * 活跃格，物理上无槽位可容纳）释放收敛为占位。保留/释放规则抽为
     * 纯函数 [MatrixDegrade.keptCells]/[MatrixDegrade.releaseIndices]
     * （确定性，可单测）。
     */
    private fun forceDegrade() {
        val target = MatrixDegrade.degradeTarget(_windowCount.value)
        if (target >= _windowCount.value) return
        val current = _cells.value
        MatrixDegrade.releaseIndices(current, target)
            .forEach { releaseCell(it) }
        _windowCount.value = target
        _cells.value = MatrixDegrade.keptCells(current, target)
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
        stopCellReconnect(cellIndex)
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
            cells = _cells.value.map { MatrixCellState(it.webappId, it.zoomPercent, it.textZoomPercent) }
        )
        // 单写者队列：连续变更（如 Slider 一次跨两档）只保留最新快照且按序
        // 落盘——各自发射独立协程在 Dispatchers.IO 上不保序，旧快照可能
        // 覆盖新快照（实测踩坑：4 窗状态被前一档 3 窗快照回写）
        persistQueue.value = snapshot
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

        /** 崩溃风暴静默期：N 格回调在此窗口内到达视为同一渲染进程死亡 */
        const val CRASH_BURST_SETTLE_MS = 600L

        /**
         * 主帧加载失败可转错误态的状态集合（纯函数，可单测）：
         * LOADING=经典时序（error 先于 finished）；ACTIVE=缓存/重定向时序
         * （finished 先把格置 ACTIVE，主帧 error 后到——飞行模式实测复现）。
         * CAPACITY_LIMITED（闸门语义不覆盖）/占位/错误态不在列。
         */
        internal fun isFailureTransitional(state: MatrixCellUiState): Boolean =
            state == MatrixCellUiState.LOADING || state == MatrixCellUiState.ACTIVE

        /**
         * 「适应宽度」缩放（纯函数，可单测）：格子物理宽度只有半屏时，
         * width=device-width 页面按格子宽（~168px）布局导致挤压换行——
         * 缩小 zoom 让布局视口 ≈ 单屏宽，页面按单屏布局整体缩进格子
         * （显示效果与单屏一致，代价是等比缩小的字号，用户可再调）。
         *
         * @param cellWidthCss 格子 CSS 宽（px）
         * @param hostWidthCss 宿主单屏 CSS 宽（px）
         */
        internal fun fitZoomPercent(cellWidthCss: Int, hostWidthCss: Int): Int {
            if (cellWidthCss <= 0 || hostWidthCss <= 0) return 100
            return (cellWidthCss.toFloat() / hostWidthCss * 100).toInt().coerceIn(30, 100)
        }

        /** 该格所在布局的列数（fit 计算用；3 窗为上 2 列/下 1 列特殊结构） */
        internal fun columnCountOf(windowCount: Int, cellIndex: Int): Int = when {
            windowCount == 3 -> if (cellIndex < 2) 2 else 1
            windowCount == 2 -> 2
            else -> 2 // 4=2×2、5=上3下2、6=2×3——均为 2 列网格
        }.coerceIn(1, windowCount)
    }
}
