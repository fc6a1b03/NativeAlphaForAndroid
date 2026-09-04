package com.cylonid.nativealpha.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * 统计页动效封装（A6）：CountUp 数字滚动 / 卡片 stagger 进入。
 * 统一动效口径——时长与缓动只在本文件定义，各卡片零动效参数。
 *
 * 无障碍契约：系统动画缩放为 0（开发者选项关动画/减少动效）时全部动效
 * 直接落终值，不产中间帧；循环装饰动画禁止（循环仅限 loading，UX 规范）。
 */

/** 数字滚动时长（ms）——视觉语言统一常量，仅此一处定义（internal 供同包图表复用） */
internal const val COUNT_UP_MS = 400

/** 卡片进入 stagger 步长（ms）——仅此一处 */
private const val STAGGER_STEP_MS = 30L

/**
 * 系统动画总开关（组合期读取一次；ANIMATOR_DURATION_SCALE 为系统缓存值，
 * 非阻塞读）。缩放为 0 = 用户要求减少动效。
 */
@Composable
private fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) > 0f
}

/**
 * CountUp 数字滚动：从 0 动画到 [target]（仪表盘进入手感）。
 * 动画关闭时直接返回终值（animateIntAsState 的 0 时长跳帧语义）。
 */
@Composable
fun rememberCountUp(target: Int): Int {
    val enabled = animationsEnabled()
    val state by animateIntAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = if (enabled) COUNT_UP_MS else 0),
        label = "statsCountUp"
    )
    return state
}

/**
 * 卡片 stagger 进入：按 [index] 递增延迟的淡入（一次性播放，无循环）。
 * 动画关闭时原样返回（零开销，无状态创建）。
 */
@Composable
fun Modifier.statsEnter(index: Int): Modifier {
    if (!animationsEnabled()) return this
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * STAGGER_STEP_MS)
        alpha.animateTo(1f, tween(durationMillis = COUNT_UP_MS))
    }
    return graphicsLayer { this.alpha = alpha.value }
}
