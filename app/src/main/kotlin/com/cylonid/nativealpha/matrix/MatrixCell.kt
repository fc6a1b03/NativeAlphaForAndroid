package com.cylonid.nativealpha.matrix

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.IconGenerator
import com.cylonid.nativealpha.util.UrlUtils
import com.cylonid.nativealpha.util.WebAppIconManager
import androidx.compose.runtime.getValue

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
internal fun DragCell(
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
 * 窗格（五态，直角满铺无缝）。LOADING/ACTIVE 共用 AndroidView 槽位保证
 * WebView 实例不因状态迁移重建；占位/错误/受限为纯 Compose 覆盖层。
 */
@Composable
internal fun MatrixCell(
    engine: MatrixEngine,
    cellIndex: Int,
    cell: MatrixCellUi,
    modifier: Modifier = Modifier,
    onPickRequest: (Int) -> Unit = {},
    onAdjustRequest: (Int) -> Unit = {},
    drag: MatrixCellDrag? = null
) {
    // 直角满铺（格间无缝后圆角会露假缝，用户定调紧贴）
    Box(
        modifier = modifier
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
internal fun PlaceholderContent(onClick: () -> Unit) {
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
internal fun LoadingContent(onCancel: () -> Unit) {
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

/**
 * 活跃态：工具条（surfaceContainer 底，常驻占布局——用户定调保留）+
 * WebView 满格。WebView 槽位：引擎持有的实例原地复用（放大往返不重载，
 * D4）。页面缩放为 View 级适配：FitFrameLayout 把 WebView 按 1/ratio
 * 测量布局（页面真实按更宽的布局视口渲染，innerWidth 随之变宽），再由
 * View scale 缩回格子——CSS zoom 只缩渲染不改布局视口（实测 innerWidth
 * 恒为格子宽），无法做到「按单屏宽度布局」故废弃。
 */
@Composable
internal fun ActiveContent(
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
        AndroidView(
            factory = { ctx ->
                FitFrameLayout(ctx).apply {
                    clipChildren = false
                    clipToPadding = false
                    addView(
                        engine.attachCellWebView(cellIndex),
                        android.widget.FrameLayout.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
            },
            update = { container ->
                val webview = container.getChildAt(0) ?: return@AndroidView
                // UI 防御层：任何来源的极端缩放都在渲染前钳回可靠区间
                val ratio = MatrixCellState.clampZoomPercent(cell.zoomPercent) / 100f
                if (container.fitRatio != ratio) {
                    container.fitRatio = ratio
                    webview.scaleX = ratio
                    webview.scaleY = ratio
                    webview.pivotX = 0f
                    webview.pivotY = 0f
                    container.requestLayout()
                }
            }
        )
    }
}

/** favicon 16dp（失败用 12dp 渐变首字母兜底，规格 §4.3） */
@Composable
internal fun Favicon16(favicon: Bitmap?, webapp: WebApp?) {
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
internal fun ErrorContent(message: String, onClick: () -> Unit) {
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
 * WebView 的 View 级缩放容器：按 fitRatio（zoomPercent/100）以 1/ratio
 * 尺寸测量子 WebView（页面布局视口真实变宽，innerWidth 随之变大=「按
 * 单屏宽度布局」），子 View 自身 scaleX/scaleY=ratio 缩回格子渲染。
 *
 * 关键实现点：Compose AndroidView 每个布局 pass 都会用格子约束强制
 * re-measure 本容器，因此比例逻辑必须放在 onMeasure/onLayout 覆写里
 * （update 里手动 measure 会被下一 pass 覆盖导致布局闪烁回跳）；
 * ratio>=1 时走 FrameLayout 原路径（放大态/同源 100% 零开销直通）。
 */
internal class FitFrameLayout(context: android.content.Context) :
    android.widget.FrameLayout(context) {

    var fitRatio = 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val pw = android.view.View.MeasureSpec.getSize(widthMeasureSpec)
        val ph = android.view.View.MeasureSpec.getSize(heightMeasureSpec)
        if (childCount == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        if (fitRatio >= 0.999f) {
            // 同比例直通也必须显式 EXACTLY：走 super 会把 AT_MOST 传给
            // WebView，而 chromium 在 AT_MOST 下按网页内容高度自测（实测
            // 746 < 可用 1346，底部大片露背景）。容器尺寸即网页视口，
            // 测量决定权收归 FitFrameLayout（与 1/ratio 分支同一哲学）。
            getChildAt(0).measure(
                android.view.View.MeasureSpec.makeMeasureSpec(pw, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(ph, android.view.View.MeasureSpec.EXACTLY)
            )
            setMeasuredDimension(pw, ph)
            return
        }
        val child = getChildAt(0)
        val cw = (pw / fitRatio).toInt()
        val ch = (ph / fitRatio).toInt()
        setMeasuredDimension(pw, ph)
        child.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(cw, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(ch, android.view.View.MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (fitRatio >= 0.999f || childCount == 0) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        val child = getChildAt(0)
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
    }
}

