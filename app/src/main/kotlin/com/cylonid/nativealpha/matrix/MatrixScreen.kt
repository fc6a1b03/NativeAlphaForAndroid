package com.cylonid.nativealpha.matrix

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cylonid.nativealpha.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 多窗矩阵 Compose 界面（规格 §4.3）。
 *
 * UI 零决策：引擎 StateFlow 驱动，五态机渲染；所有判定（闸门/降级/
 * 退避）结论以状态/事件到达，UI 只画图与转发交互。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MatrixScreen(
    engine: MatrixEngine,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val cells by engine.cells.collectAsStateWithLifecycle()
    val windowCount by engine.windowCount.collectAsStateWithLifecycle()
    val memoryWarning by engine.memoryWarningVisible.collectAsStateWithLifecycle()
    val deviceUnsupported by engine.deviceUnsupported.collectAsStateWithLifecycle()
    val zoomedIndex by engine.zoomedCellIndex.collectAsStateWithLifecycle()

    var pendingReduceTarget by remember { mutableStateOf<Int?>(null) }
    var pickerCellIndex by remember { mutableStateOf<Int?>(null) }
    var adjustCellIndex by remember { mutableStateOf<Int?>(null) }
    var chromeContainer by remember { mutableStateOf(IntSize.Zero) }

    // BackHandler（规格：放大→网格→退出矩阵 两段语义，D4）
    BackHandler(enabled = true) {
        if (zoomedIndex != null) {
            engine.collapseZoom()
        } else {
            onBack()
        }
    }

    // 顶栏撤除（用户定调：占空间的是全局顶栏，非每格标题条）。可移动
    // 窗数胶囊：点击原地扩开小菜单（窗数步进+退出矩阵，透明遮罩防误触，
    // 点遮罩收回）；左右缘吸边隐藏；2s 无操作自动吸顶收纳（用户定调）
    Box(
        modifier = modifier
            .fillMaxSize()
            // 四边零边距（用户定调：矩阵窗口上下左右必须撑满；Activity
            // 侧全屏沉浸已隐藏系统栏，格子铺满物理屏幕）
            .onSizeChanged { chromeContainer = it }
    ) {
        when {
            deviceUnsupported -> DeviceUnsupportedPage()
            // 原地放大（D4）：同一 WebView 实例单格铺满；退出入口=工具条
            // 放大按钮语义切换（放大↔收起）+ 系统返回
            zoomedIndex != null -> {
                val index = zoomedIndex ?: 0
                if (index < windowCount) {
                    MatrixCell(
                        engine = engine,
                        cellIndex = index,
                        cell = cells.getOrNull(index) ?: MatrixCellUi(),
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            else -> MatrixGrid(
                engine = engine,
                cells = cells,
                windowCount = windowCount,
                onPickRequest = { pickerCellIndex = it },
                onAdjustRequest = { adjustCellIndex = it }
            )
        }
        // 可移动窗数胶囊：扩开小菜单的窗数步进/退出在此收口（引擎闸门、
        // 减窗确认弹窗仍归 MatrixScreen）
        if (!deviceUnsupported && zoomedIndex == null) {
            MatrixWindowCountPill(
                windowCount = windowCount,
                maxWindows = engine.maxWindows,
                containerSize = chromeContainer,
                onChangeWindowCount = { target ->
                    when {
                        target > windowCount -> repeat(target - windowCount) { engine.addWindow() }
                        target < windowCount -> {
                            // 尾部全占位直接减；任一有内容则弹一次确认
                            val needsConfirm = cells.drop(target)
                                .any { it.state != MatrixCellUiState.PLACEHOLDER }
                            if (needsConfirm) {
                                pendingReduceTarget = target
                            } else {
                                repeat(windowCount - target) { engine.reduceWindowConfirmed() }
                            }
                        }
                    }
                },
                onExit = onBack,
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
        // 内存警示：浮层不占布局、常驻显示（重要提示不可无声消失）
        if (memoryWarning && !deviceUnsupported) {
            MemoryWarningBar(
                onDismiss = { engine.dismissMemoryWarning() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 44.dp)
            )
        }
    }

    pendingReduceTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingReduceTarget = null },
            title = { Text(stringResource(R.string.matrix_reduce_confirm_title)) },
            text = { Text(stringResource(R.string.matrix_reduce_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingReduceTarget = null
                    repeat(windowCount - target) { engine.reduceWindowConfirmed() }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingReduceTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    pickerCellIndex?.let { index ->
        CellPickerSheet(
            onDismiss = { pickerCellIndex = null },
            onPicked = { webappId ->
                pickerCellIndex = null
                engine.pickSite(index, webappId)
            }
        )
    }

    adjustCellIndex?.let { index ->
        CellAdjustSheet(
            engine = engine,
            cellIndex = index,
            onDismiss = { adjustCellIndex = null }
        )
    }
}

/** 内存警示条（onTrimMemory ≥ RUNNING_LOW；忽略后本会话不弹；浮层样式） */
@Composable
private fun MemoryWarningBar(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 2.dp,
        modifier = modifier.fillMaxWidth(0.94f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.matrix_memory_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    }
}

/** 设备不足整页劝退（QB 预检/闸门首窗拦截；不硬开功能） */
@Composable
private fun DeviceUnsupportedPage() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.matrix_device_underpowered),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp)
        )
    }
}

/** 网格布局：2=上下分栏；3=上 2 下 1（下格高 50%）；4=2×2（固定结构，非 Lazy） */
@Composable
private fun MatrixGrid(
    engine: MatrixEngine,
    cells: List<MatrixCellUi>,
    windowCount: Int,
    onPickRequest: (Int) -> Unit,
    onAdjustRequest: (Int) -> Unit
) {
    // 拖拽排序（按住标题换位）：手势在容器层检测（格交换时组合移动不断手势）；
    // 拖动位移超阈值 → 与方向相邻格 swap；性能红线：手势 tick 只更新 offset，
    // 数据交换一次一换、WebView 实例复用不重载
    var dragUid by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(android.util.Pair(0f, 0f)) }
    var dragOrigin by remember { mutableStateOf(-1) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dragThresholdPx = remember { with(density) { 130.dp.toPx() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .animateContentSize(),
        // 格间零间隙紧贴（用户定调：可利用空间全给矩阵，窗口间无缝）
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        fun cellAt(index: Int) = cells.getOrElse(index) { MatrixCellUi() }
        fun dragArgs(index: Int): MatrixCellDrag =
            MatrixCellDrag(
                isDragging = dragUid == cellAt(index).uid,
                dragOffsetPx = dragOffset,
                thresholdPx = dragThresholdPx,
                onDragStart = { uid ->
                    dragUid = uid
                    dragOrigin = index
                    dragOffset = android.util.Pair(0f, 0f)
                },
                onDrag = { dx, dy ->
                    if (dragOrigin >= 0) {
                        val t = dragOffset
                        dragOffset = android.util.Pair(t.first + dx, t.second + dy)
                        val tx = dragOffset.first
                        val ty = dragOffset.second
                        if (kotlin.math.abs(tx) > dragThresholdPx) {
                            val n = engine.neighborIndex(dragOrigin, tx, 0f)
                            if (n >= 0) {
                                engine.swapCells(dragOrigin, n)
                                dragOrigin = n
                                dragOffset = android.util.Pair(0f, 0f)
                            } else {
                                dragOffset = android.util.Pair(0f, ty)
                            }
                        } else if (kotlin.math.abs(ty) > dragThresholdPx) {
                            val n = engine.neighborIndex(dragOrigin, 0f, ty)
                            if (n >= 0) {
                                engine.swapCells(dragOrigin, n)
                                dragOrigin = n
                                dragOffset = android.util.Pair(0f, 0f)
                            } else {
                                dragOffset = android.util.Pair(tx, 0f)
                            }
                        }
                    }
                },
                onDragEnd = {
                    dragUid = null
                    dragOrigin = -1
                    dragOffset = android.util.Pair(0f, 0f)
                }
            )

        when (windowCount) {
            2 -> {
                // 左右分栏（用户定调：双窗默认并排——视频等高内容场景
                // 每格全高优于全宽半高）；拖拽换位随邻接表改横向
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    DragCell(engine, 0, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 1, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                }
            }
            3 -> {
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    DragCell(engine, 0, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 1, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                }
                DragCell(engine, 2, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f).fillMaxWidth())
            }
            4 -> {
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    DragCell(engine, 0, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 1, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                }
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    DragCell(engine, 2, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 3, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                }
            }
            5 -> {
                // 上 3 下 2（与 3 窗「上 2 下 1」同构的行分割）
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    DragCell(engine, 0, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 1, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 2, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                }
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    DragCell(engine, 3, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 4, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                }
            }
            else -> {
                // 6 窗 = 2×3 网格（设备档位上限 6 时可用；配合原地放大单格细读）
                repeat(3) { row ->
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        DragCell(engine, row * 2, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                        DragCell(engine, row * 2 + 1, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

