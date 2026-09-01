package com.cylonid.nativealpha.matrix


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role

/**
 * 可移动窗数胶囊（矩阵全局 chrome，用户逐条拍板的浮窗助手交互）：
 * - 默认顶部居中完整显示（进页面不吸附）；拖动自由摆放
 * - 拖到左右缘吸边隐藏（露把手，手势排除区防误触）；拖到顶缘吸顶收纳
 * - **2s 无操作自动吸顶**收纳（已吸左右视为明确收纳意图，保持不动）
 * - 点击完整胶囊 → 原地**扩开悬浮小菜单**（窗数步进 + 退出矩阵），全屏
 *   透明遮罩防误触，点遮罩收回
 * - 点击吸边/吸顶把手 → 弹回顶部居中完整态
 * 所有形态切换走动画（淡入淡出 + 中心缩放 + 位移缓动），只动
 * graphicsLayer 不触发重布局。
 */

internal val PILL_WIDTH = 52.dp
internal val PILL_HEIGHT = 32.dp

/** 吸边/吸顶后屏幕内露出的把手尺寸（其余被屏幕边缘裁掉） */
internal val PILL_DOCK_PEEK = 10.dp

/** 松手时胶囊中心距某边缘小于该值即吸向该侧 */
internal val PILL_DOCK_THRESHOLD = 60.dp

/** 无操作多久后自动吸顶收纳 */
internal const val PILL_AUTO_DOCK_IDLE_MS = 2_000L

internal enum class MatrixPillDock { LEFT, RIGHT, TOP }

/** 吸边判定（纯函数可单测）：胶囊中心距各边缘的距离取最小者，超过阈值不吸 */
internal fun matrixPillDockTarget(
    centerX: Float,
    centerY: Float,
    containerWidth: Float,
    containerHeight: Float,
    threshold: Float
): MatrixPillDock? {
    val distLeft = centerX
    val distRight = containerWidth - centerX
    val distTop = centerY
    return when {
        distTop < threshold -> MatrixPillDock.TOP
        distLeft < threshold -> MatrixPillDock.LEFT
        distRight < threshold -> MatrixPillDock.RIGHT
        else -> null
    }
}

/**
 * 可移动窗数胶囊。containerSize 为所在全屏 Box 尺寸（调用方 onSizeChanged
 * 传入）；窗数步进与退出经回调交调用方执行（引擎闸门/减窗确认仍在
 * MatrixScreen 侧收口）。
 */
@Composable
internal fun MatrixWindowCountPill(
    windowCount: Int,
    maxWindows: Int,
    containerSize: IntSize,
    onChangeWindowCount: (Int) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pillW = with(density) { PILL_WIDTH.toPx() }
    val pillH = with(density) { PILL_HEIGHT.toPx() }
    val peek = with(density) { PILL_DOCK_PEEK.toPx() }
    val dockThreshold = with(density) { PILL_DOCK_THRESHOLD.toPx() }
    // 默认顶部居中（顶边留 4dp 视觉边距）；纵向钳在容器内
    val defaultTop = with(density) { 4.dp.toPx() }

    fun defaultPos(): Offset = Offset((containerSize.width - pillW) / 2f, defaultTop)

    fun clampPos(p: Offset): Offset = Offset(
        p.x.coerceIn(-pillW + peek, (containerSize.width - peek).coerceAtLeast(peek)),
        p.y.coerceIn(-(pillH - peek), (containerSize.height - peek).coerceAtLeast(peek))
    )

    val pos = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val positioned = remember { mutableStateOf(false) }
    LaunchedEffect(containerSize) {
        if (containerSize != IntSize.Zero) {
            if (!positioned.value) {
                pos.snapTo(defaultPos())
                positioned.value = true
            } else {
                // 旋转等尺寸变化后重新收进边界（否则胶囊可能悬在新边界
                // 之外不可见/不可点）
                pos.snapTo(clampPos(pos.value))
            }
        }
    }

    var dragging by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    // 交互戳：任意拖动/点击重置自动吸顶计时
    var idleEpoch by remember { mutableIntStateOf(0) }

    val isDockedSide: () -> Boolean = {
        val x = pos.value.x
        x <= -pillW + peek + 1f || x >= containerSize.width - peek - 1f
    }

    // 2s 无操作自动吸顶（拖动中/菜单展开时不吸；已吸左右=明确收纳意图，保持）
    LaunchedEffect(idleEpoch, dragging, expanded, containerSize) {
        if (!dragging && !expanded) {
            delay(PILL_AUTO_DOCK_IDLE_MS)
            if (!dragging && !expanded && !isDockedSide()) {
                val dest = Offset(
                    pos.value.x.coerceIn(0f, (containerSize.width - pillW).coerceAtLeast(0f)),
                    -(pillH - peek)
                )
                pos.animateTo(dest, tween(250, easing = FastOutSlowInEasing))
            }
        }
    }

    // 吸边把手贴屏幕左右缘（系统返回手势区）：把胶囊窗口坐标声明为
    // 手势排除区，拉把手不致被系统截胡成「返回」。把手 32dp 高 << 系统
    // 200dp 单侧排除上限，合规；只影响把手自身区域，其余边缘手势不动。
    // 走 View API（Modifier.systemGestureExclusion 各包不存在该符号）。
    val androidView = LocalView.current
    DisposableEffect(androidView) {
        onDispose { androidView.systemGestureExclusionRects = emptyList() }
    }

    // 扩开菜单动画（中心缩放 + 淡入，遮罩独立淡入）
    val menuAlpha by animateFloatAsState(
        if (expanded) 1f else 0f,
        tween(200, easing = FastOutSlowInEasing), label = "menuAlpha"
    )
    val menuScale by animateFloatAsState(
        if (expanded) 1f else 0.55f,
        tween(220, easing = FastOutSlowInEasing), label = "menuScale"
    )

    val desc = stringResource(R.string.matrix_window_count)
    val exitDesc = stringResource(R.string.matrix_exit)

    Box(modifier.fillMaxSize()) {
        // 胶囊本体：菜单展开时淡出（被遮罩接管触摸）
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 2.dp,
            modifier = Modifier
                .alpha(if (positioned.value) (1f - menuAlpha) else 0f)
                .offset { IntOffset(pos.value.x.roundToInt(), pos.value.y.roundToInt()) }
                .size(PILL_WIDTH, PILL_HEIGHT)
                .onGloballyPositioned { coords ->
                    // exclusionRects 坐标系=宿主 View：用 localToRoot
                    // （窗口坐标会差一个状态栏高度导致排除区错位失效）；
                    // Compose 1.11 已移除 positionInWindow/boundsInWindow。
                    // 四周外扩一圈：贴边把手紧邻系统手势缓冲区，仅覆盖
                    // 把手自身时边缘首击可能被吞（实测「先点别处再点
                    // 把手才响应」），外扩后首击稳定进 App。
                    // 配额 48dp << 系统 200dp 单侧上限。
                    val origin = coords.localToRoot(Offset.Zero)
                    val sz = coords.size
                    val m = with(density) { 8.dp.roundToPx() }
                    androidView.systemGestureExclusionRects = listOf(
                        android.graphics.Rect(
                            origin.x.roundToInt() - m, origin.y.roundToInt() - m,
                            (origin.x + sz.width).roundToInt() + m,
                            (origin.y + sz.height).roundToInt() + m
                        )
                    )
                }
                .semantics { contentDescription = desc }
                .pointerInput(containerSize) {
                    detectDragGestures(
                        onDragStart = {
                            dragging = true
                            idleEpoch++
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            scope.launch { pos.snapTo(clampPos(pos.value + amount)) }
                        },
                        onDragEnd = {
                            dragging = false
                            idleEpoch++
                            val dock = matrixPillDockTarget(
                                pos.value.x + pillW / 2f,
                                pos.value.y + pillH / 2f,
                                containerSize.width.toFloat(),
                                containerSize.height.toFloat(),
                                dockThreshold
                            )
                            val dest = when (dock) {
                                MatrixPillDock.LEFT -> Offset(-pillW + peek, pos.value.y)
                                MatrixPillDock.RIGHT ->
                                    Offset(containerSize.width - peek, pos.value.y)
                                MatrixPillDock.TOP -> Offset(
                                    pos.value.x.coerceIn(
                                        0f,
                                        (containerSize.width - pillW).coerceAtLeast(0f)
                                    ),
                                    -(pillH - peek)
                                )
                                null -> clampPos(pos.value)
                            }
                            scope.launch {
                                pos.animateTo(
                                    dest, tween(200, easing = FastOutSlowInEasing)
                                )
                            }
                        }
                    )
                }
                .pointerInput(containerSize) {
                    detectTapGestures(onTap = {
                        if (isDockedSide() || pos.value.y <= -(pillH - peek) + 1f) {
                            // 吸边/吸顶把手 → 弹回顶部居中完整态
                            idleEpoch++
                            scope.launch {
                                pos.animateTo(
                                    defaultPos(), tween(250, easing = FastOutSlowInEasing)
                                )
                            }
                        } else {
                            expanded = true
                        }
                    })
                }
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Dashboard,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = windowCount.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // 全屏透明遮罩：菜单展开时拦截一切误触，点遮罩收回
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            expanded = false
                            idleEpoch++
                        }
                    }
            )
        }

        // 悬浮小菜单：以胶囊位置为锚点原地扩开（中心缩放 + 淡入）
        if (positioned.value) {
            val menuW = with(density) { MENU_WIDTH.toPx() }
            val menuH = with(density) { MENU_HEIGHT_ESTIMATE_DP.dp.toPx() }
            val menuOrigin = Offset(
                pos.value.x.coerceIn(0f, (containerSize.width - menuW).coerceAtLeast(0f)),
                pos.value.y.coerceIn(
                    0f,
                    (containerSize.height - menuH).coerceAtLeast(0f)
                )
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 3.dp,
                modifier = Modifier
                    .alpha(menuAlpha)
                    .offset { IntOffset(menuOrigin.x.roundToInt(), menuOrigin.y.roundToInt()) }
                    .width(MENU_WIDTH)
                    .graphicsLayer {
                        scaleX = menuScale
                        scaleY = menuScale
                        transformOrigin = TransformOrigin(0.5f, 0.35f)
                    }
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onChangeWindowCount(windowCount - 1) },
                            enabled = windowCount > MatrixSessionState.MIN_WINDOW_COUNT
                        ) {
                            Icon(
                                Icons.Default.Remove,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = windowCount.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.width(40.dp),
                            textAlign = TextAlign.Center
                        )
                        IconButton(
                            onClick = { onChangeWindowCount(windowCount + 1) },
                            enabled = windowCount < maxWindows
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    HorizontalDivider()
                    TextButton(
                        onClick = {
                            expanded = false
                            onExit()
                        },
                        modifier = Modifier.semantics { contentDescription = exitDesc }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.matrix_exit))
                    }
                }
            }
        }
    }
}

/** 菜单宽度（固定，保证步进行 + 退出按钮排版稳定） */
internal val MENU_WIDTH = 184.dp

/** 菜单高度估算（clamp 用；实际 wrap 内容） */
internal const val MENU_HEIGHT_ESTIMATE_DP = 132
