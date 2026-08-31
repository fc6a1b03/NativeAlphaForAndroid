package com.cylonid.nativealpha.matrix

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.IconGenerator
import com.cylonid.nativealpha.util.UrlUtils
import com.cylonid.nativealpha.util.WebAppIconManager

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

    // BackHandler（规格：放大→网格→退出矩阵 两段语义，D4）
    BackHandler(enabled = true) {
        if (zoomedIndex != null) {
            engine.collapseZoom()
        } else {
            onBack()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        if (zoomedIndex == null) {
            MatrixTopBar(
                windowCount = windowCount,
                maxWindows = engine.maxWindows,
                onBack = onBack,
                onCountCommit = { target ->
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
                }
            )
            if (memoryWarning) {
                MemoryWarningBar(onDismiss = { engine.dismissMemoryWarning() })
            }
        }

        when {
            deviceUnsupported -> DeviceUnsupportedPage()
            // 原地放大（D4）：同一 WebView 实例单格铺满，顶栏隐藏；
            // 退出入口=工具条原放大按钮语义切换（放大↔收起），不另加控件
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

/** 顶栏（64dp）：返回 + 标题 + Slider（2..设备档位上限，~120dp）+ 数字实时值 */
@Composable
private fun MatrixTopBar(
    windowCount: Int,
    maxWindows: Int,
    onBack: () -> Unit,
    onCountCommit: (Int) -> Unit
) {
    var sliderValue by remember(windowCount) { mutableStateOf(windowCount.toFloat()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = stringResource(R.string.matrix_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = sliderValue.toInt().toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val windowCountDesc = stringResource(R.string.matrix_window_count)
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onCountCommit(sliderValue.toInt()) },
            valueRange = MatrixSessionState.MIN_WINDOW_COUNT.toFloat()..maxWindows.toFloat(),
            steps = maxWindows - MatrixSessionState.MIN_WINDOW_COUNT - 1,
            modifier = Modifier
                .width(120.dp)
                .padding(horizontal = 8.dp)
                .semantics {
                    contentDescription = windowCountDesc
                    stateDescription = sliderValue.toInt().toString()
                }
        )
    }
}

/** 内存警示条（onTrimMemory ≥ RUNNING_LOW；忽略后本会话不弹） */
@Composable
private fun MemoryWarningBar(onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
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

    val gap = 8.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .animateContentSize()
            .padding(top = gap),
        verticalArrangement = Arrangement.spacedBy(gap)
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
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    DragCell(engine, 0, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 1, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                }
            }
            3 -> {
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    DragCell(engine, 0, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 1, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                }
                DragCell(engine, 2, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f).fillMaxWidth())
            }
            4 -> {
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    DragCell(engine, 0, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 1, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                }
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    DragCell(engine, 2, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 3, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                }
            }
            5 -> {
                // 上 3 下 2（与 3 窗「上 2 下 1」同构的行分割）
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    DragCell(engine, 0, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 1, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 2, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                }
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    DragCell(engine, 3, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    DragCell(engine, 4, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                }
            }
            else -> {
                // 6 窗 = 2×3 网格（设备档位上限 6 时可用；配合原地放大单格细读）
                repeat(3) { row ->
                    Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        DragCell(engine, row * 2, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                        DragCell(engine, row * 2 + 1, ::cellAt, ::dragArgs, onPickRequest, onAdjustRequest, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 拖拽参数聚合（避免 6 参回调链） */
internal class MatrixCellDrag(
    val isDragging: Boolean,
    val dragOffsetPx: android.util.Pair<Float, Float>,
    val thresholdPx: Float,
    val onDragStart: (Int) -> Unit,
    val onDrag: (Float, Float) -> Unit,
    val onDragEnd: () -> Unit
)

/** 带 uid key 与拖拽/调节能力的窗格包装：交换时按 key 移动组合，WebView 实例复用 */
@Composable
private fun DragCell(
    engine: MatrixEngine,
    index: Int,
    cellAt: (Int) -> MatrixCellUi,
    dragArgs: (Int) -> MatrixCellDrag,
    onPickRequest: (Int) -> Unit,
    onAdjustRequest: (Int) -> Unit,
    modifier: Modifier
) {
    val cell = cellAt(index)
    val args = dragArgs(index)
    androidx.compose.runtime.key(cell.uid) {
        MatrixCell(
            engine = engine,
            cellIndex = index,
            cell = cell,
            modifier = modifier.graphicsLayer {
                if (args.isDragging) {
                    translationX = args.dragOffsetPx.first
                    translationY = args.dragOffsetPx.second
                    alpha = 0.85f
                } else {
                    translationX = 0f
                    translationY = 0f
                    alpha = 1f
                }
            },
            onPickRequest = onPickRequest,
            onAdjustRequest = onAdjustRequest,
            drag = args
        )
    }
}

/**
 * 窗格（五态，12dp 圆角）。LOADING/ACTIVE 共用 AndroidView 槽位保证
 * WebView 实例不因状态迁移重建；占位/错误/受限为纯 Compose 覆盖层。
 */
@Composable
private fun MatrixCell(
    engine: MatrixEngine,
    cellIndex: Int,
    cell: MatrixCellUi,
    modifier: Modifier = Modifier,
    onPickRequest: (Int) -> Unit = {},
    onAdjustRequest: (Int) -> Unit = {},
    drag: MatrixCellDrag? = null
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        when (cell.state) {
            MatrixCellUiState.PLACEHOLDER -> PlaceholderContent(
                onClick = { onPickRequest(cellIndex) }
            )
            MatrixCellUiState.LOADING -> LoadingContent(
                onCancel = { engine.cancelLoading(cellIndex) }
            )
            MatrixCellUiState.ACTIVE -> ActiveContent(engine, cellIndex, cell, onAdjustRequest, drag)
            MatrixCellUiState.ERROR -> ErrorContent(
                message = stringResource(R.string.matrix_window_crashed),
                onClick = { engine.retryCell(cellIndex) }
            )
            MatrixCellUiState.CAPACITY_LIMITED -> ErrorContent(
                message = stringResource(R.string.matrix_capacity_limit),
                onClick = { engine.retryCell(cellIndex) }
            )
        }
    }
}

@Composable
private fun PlaceholderContent(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.matrix_pick_app),
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.matrix_pick_app),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LoadingContent(onCancel: () -> Unit) {
    // 最低消耗加载指示（纯 Compose 绘制；不用宿主 animal_walk 动画）。
    // 工具条在 LOADING 态即渲染（用户定调：任何状态都可操作，白屏/
    // 卡死格必须始终有关闭出口——close=取消加载销毁回占位）
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.matrix_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.matrix_close_window),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

/** 活跃态：工具条（surfaceContainer 底）+ WebView 满格 */
@Composable
private fun ActiveContent(
    engine: MatrixEngine,
    cellIndex: Int,
    cell: MatrixCellUi,
    onAdjustRequest: (Int) -> Unit,
    drag: MatrixCellDrag?
) {
    val context = LocalContext.current
    val zoomedIndex by engine.zoomedCellIndex.collectAsStateWithLifecycle()
    val isZoomed = zoomedIndex == cellIndex
    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .then(
                        if (drag != null) {
                            Modifier.pointerInput(cell.uid) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { drag.onDragStart(cell.uid) },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        drag.onDrag(amount.x, amount.y)
                                    },
                                    onDragEnd = { drag.onDragEnd() },
                                    onDragCancel = { drag.onDragEnd() }
                                )
                            }
                        } else {
                            Modifier
                        }
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val webapp = cell.webapp
                val favicon = remember(cell.webappId) {
                    webapp?.let { WebAppIconManager.resolveIconCached(context, it) }
                }
                Favicon16(favicon, webapp)
                Text(
                    text = cell.title.ifBlank { webapp?.title ?: "" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // 触控目标 ≥48dp（§6 验收门）：IconButton 走 M3 默认最小
                // 命中尺寸（48dp），视觉图标 18dp——外扩命中不压视觉
                IconButton(onClick = { onAdjustRequest(cellIndex) }) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = stringResource(R.string.matrix_adjust),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 放大/收起同一按钮语义切换（避免放大态按钮成摆设——用户实测批评）
                if (isZoomed) {
                    IconButton(onClick = { engine.collapseZoom() }) {
                        Icon(
                            Icons.Default.CloseFullscreen,
                            contentDescription = stringResource(R.string.matrix_collapse),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    IconButton(onClick = { engine.enlargeCell(cellIndex) }) {
                        Icon(
                            Icons.Default.OpenInFull,
                            contentDescription = stringResource(R.string.matrix_enlarge),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { engine.closeCell(cellIndex) }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.matrix_close_window),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        // WebView 槽位：引擎持有的实例原地复用（放大往返不重载，D4）
        AndroidView(
            factory = { engine.attachCellWebView(cellIndex) },
            update = { },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** favicon 16dp（失败用 12dp 渐变首字母兜底，规格 §4.3） */
@Composable
private fun Favicon16(favicon: Bitmap?, webapp: WebApp?) {
    if (favicon != null) {
        Image(
            bitmap = favicon.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(16.dp)
        )
    } else {
        val letter = remember(webapp?.title, webapp?.baseUrl) {
            IconGenerator.generate(
                webapp?.title, UrlUtils.hostOf(webapp?.baseUrl ?: ""), 12, 6
            )
        }
        Image(
            bitmap = letter.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .padding(start = 4.dp)
                .size(12.dp)
        )
    }
}

/** 错误/容量受限共用视觉：⚠ + 提示，整格点击 */
@Composable
private fun ErrorContent(message: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

/**
 * CellPicker（ModalBottomSheet）：搜索过滤 + 站点列表（同站可选）+
 * 底部 Cookie 披露条（D1 内联披露）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CellPickerSheet(
    onDismiss: () -> Unit,
    onPicked: (Int) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val sites = remember { activeSites() }
    val filtered = remember(query, sites) {
        if (query.isBlank()) sites
        else sites.filter {
            it.title.contains(query, ignoreCase = true) ||
                (UrlUtils.hostOf(it.baseUrl) ?: "").contains(query.trim(), ignoreCase = true)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.matrix_pick_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.matrix_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.matrix_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(filtered, key = { it.ID }) { site ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .clickable { onPicked(site.ID) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val icon = remember(site.ID) {
                                WebAppIconManager.resolveIconCached(context, site)
                            }
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    bitmap = icon.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = site.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = UrlUtils.hostOf(site.baseUrl) ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            // D1 内联披露：矩阵共享 Cookie，退出恢复隔离
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.matrix_cookie_disclosure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

private fun activeSites(): List<WebApp> =
    com.cylonid.nativealpha.model.DataManager.getInstance().webAppsFlow.value.items
        .filter { it.isActiveEntry }
        .sortedBy { it.order }
