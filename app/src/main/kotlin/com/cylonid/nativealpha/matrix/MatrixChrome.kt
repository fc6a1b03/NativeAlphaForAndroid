package com.cylonid.nativealpha.matrix


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role

/**
 * 可移动窗数胶囊（矩阵全局 chrome，用户逐条拍板的浮窗助手交互）：
 * - 默认顶部居中完整显示；拖动自由摆放（纵向全程完整可见，不裁切）
 * - 拖到左右缘吸边隐藏（露把手，手势排除区防误触）
 * - 点击完整胶囊 → 原地**扩开悬浮小菜单**（窗数步进 + 退出矩阵），全屏
 *   透明遮罩防误触，点遮罩收回
 * - 点击吸边把手 → 弹回顶部居中完整态
 * 所有形态切换走动画（淡入淡出 + 中心缩放 + 位移缓动），只动
 * graphicsLayer 不触发重布局。
 *
 * 不做顶缘吸顶收纳（v2.2.11 用户定调撤销）：顶部把手与系统下拉通知栏
 * 手势区物理重叠，实机拉出把手总被下拉菜单截胡，且 App 无权排除顶部
 * 系统手势（systemGestureExclusion 仅覆盖左右 back 边缘）。
 */

internal val PILL_WIDTH = 52.dp
internal val PILL_HEIGHT = 32.dp

/** 吸边后屏幕内露出的把手尺寸（其余被屏幕边缘裁掉） */
internal val PILL_DOCK_PEEK = 10.dp

/** 松手时胶囊中心距某边缘小于该值即吸向该侧 */
internal val PILL_DOCK_THRESHOLD = 60.dp

internal enum class MatrixPillDock { LEFT, RIGHT }

/** 吸边判定（纯函数可单测）：胶囊中心距左右边缘的距离取最小者，
 * 超过阈值不吸；顶缘不吸（顶部收纳与系统下拉手势冲突，已撤销） */
internal fun matrixPillDockTarget(
    centerX: Float,
    centerY: Float,
    containerWidth: Float,
    containerHeight: Float,
    threshold: Float
): MatrixPillDock? {
    val distLeft = centerX
    val distRight = containerWidth - centerX
    return when {
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
        p.y.coerceIn(0f, (containerSize.height - pillH).coerceAtLeast(0f))
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

    val isDockedSide: () -> Boolean = {
        val x = pos.value.x
        x <= -pillW + peek + 1f || x >= containerSize.width - peek - 1f
    }

    // 吸边把手贴屏幕左右缘（系统返回手势区）：把胶囊窗口坐标声明为
    // 手势排除区，拉把手不致被系统截胡成「返回」。把手 32dp 高 << 系统
    // 200dp 单侧排除上限，合规；只影响把手自身区域，其余边缘手势不动。
    // 走 View API（Modifier.systemGestureExclusion 各包不存在该符号）。
    val androidView = LocalView.current
    DisposableEffect(androidView) {
        onDispose { androidView.systemGestureExclusionRects = emptyList() }
    }

    val desc = stringResource(R.string.matrix_window_count)
    val exitDesc = stringResource(R.string.matrix_exit)

    Box(modifier.fillMaxSize()) {
        // 胶囊本体：菜单展开时淡出（被遮罩接管触摸）
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(16.dp),
            shadowElevation = 2.dp,
            modifier = Modifier
                .alpha(if (positioned.value && !expanded) 1f else 0f)
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
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            scope.launch { pos.snapTo(clampPos(pos.value + amount)) }
                        },
                        onDragEnd = {
                            dragging = false
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
                        if (isDockedSide()) {
                            // 吸边把手 → 弹回顶部居中完整态
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
                        }
                    }
            )
        }

        // 悬浮小菜单：以胶囊中心为锚原地扩开（中心缩放 + 淡入），
        // 居中覆盖胶囊（用户定调），整体钳回屏幕内。
        // 注：M3 DropdownMenu 锚定只认组合位置，无法感知 offset 绝对
        // 定位浮层（实测弹点失控），故保留手写定位。
        AnimatedVisibility(
            visible = expanded && positioned.value,
            enter = fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                scaleIn(initialScale = 0.55f, animationSpec = tween(220, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.55f, animationSpec = tween(150)),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            val menuW = with(density) { MENU_WIDTH.toPx() }
            val menuH = with(density) { MENU_HEIGHT_ESTIMATE_DP.dp.toPx() }
            val menuOrigin = Offset(
                (pos.value.x + pillW / 2f - menuW / 2f).coerceIn(
                    0f, (containerSize.width - menuW).coerceAtLeast(0f)
                ),
                (pos.value.y + pillH / 2f - menuH / 2f).coerceIn(
                    0f, (containerSize.height - menuH).coerceAtLeast(0f)
                )
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 3.dp,
                modifier = Modifier
                    .offset { IntOffset(menuOrigin.x.roundToInt(), menuOrigin.y.roundToInt()) }
                    .width(MENU_WIDTH)
                    .pointerInput(Unit) { detectTapGestures { } }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 标题行：窗数是菜单的视觉主体
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Dashboard,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.matrix_window_count_value, windowCount, windowCount
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    // 步进行：tonal 圆钮有实体感，边界态自然降透明
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        FilledTonalIconButton(
                            onClick = { onChangeWindowCount(windowCount - 1) },
                            enabled = windowCount > MatrixSessionState.MIN_WINDOW_COUNT
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = null)
                        }
                        FilledTonalIconButton(
                            onClick = { onChangeWindowCount(windowCount + 1) },
                            enabled = windowCount < maxWindows
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    // 退出行：tonal 胶囊菜单项（icon+label 整行可点，非裸链接）
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                role = Role.Button,
                                onClick = {
                                    expanded = false
                                    onExit()
                                }
                            )
                            .semantics { contentDescription = exitDesc }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.matrix_exit),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 菜单宽度（固定，保证步进行 + 退出按钮排版稳定） */
internal val MENU_WIDTH = 192.dp

/** 菜单高度估算（屏幕内钳制用；实际 wrap 内容） */
internal const val MENU_HEIGHT_ESTIMATE_DP = 158

