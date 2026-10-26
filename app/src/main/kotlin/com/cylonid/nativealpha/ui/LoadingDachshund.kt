package com.cylonid.nativealpha.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 加载页腊肠狗（替换 v2.3.15 前的 4 帧几何拼接狗）：一只超长小短腿腊肠犬
 * 狂奔追前方的骨头，永远「差一点」——加载状态的趣味隐喻。
 *
 * 语义绑定加载时长（三态）：
 * - RUN（0-8s）：全速狂奔——果冻身体波浪、四腿高频交替、耳朵后飘、舌头甩动、扬尘
 * - SWEAT（8-15s）：还在跑但开始冒汗（页面变慢的信号，用户情绪出口）
 * - POOPED（>15s）：累趴——身体摊平、腿摊地、舌头拖地，骨头在前面原地打转等它
 *   （与宿主白屏检测/矩阵 20s 超时兜底同一量级的「真慢了」信号，但用笑声传达）
 *
 * 呆萌原则（用户定调：有趣有意义好笑、不能凶）：大圆眼高光、吐舌憨态、
 * 无犬齿无皱眉；暖橙棕配色延续旧狗，避免攻击感。
 *
 * 「移除动画」无障碍设置（动画时长缩放=0）退化为静态呆立帧，仍保留加载指示语义。
 */
@Composable
fun LoadingDachshund(
    active: Boolean,
    modifier: Modifier = Modifier,
    elapsedOverrideSec: Float? = null
) {
    // 帧驱动：active=false 时不产生重绘（加载动画隐藏期零开销）
    val elapsed by produceState(0f, active) {
        if (!active) {
            value = 0f
            return@produceState
        }
        val start = withFrameNanos { it }
        while (active) {
            withFrameNanos { frame -> value = (frame - start) / 1_000_000_000f }
        }
    }
    val shownElapsed = elapsedOverrideSec ?: elapsed
    val transition = rememberInfiniteTransition(label = "dachshund")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing)),
        label = "phase"
    )
    // 系统动画时长缩放=0（无障碍「移除动画」）→ 静态呆立帧
    val reducedMotion = isReduceMotionRequested()
    Canvas(modifier) {
        val stage = loadingStage(shownElapsed)
        val p = if (reducedMotion) 0.35f else phase
        drawScene(p, shownElapsed, stage)
    }
}

/** 加载动画三态：跑 / 冒汗 / 累趴（纯函数可单测） */
enum class LoadingStage { RUN, SWEAT, POOPED }

fun loadingStage(elapsedSec: Float): LoadingStage = when {
    elapsedSec < 8f -> LoadingStage.RUN
    elapsedSec < 15f -> LoadingStage.SWEAT
    else -> LoadingStage.POOPED
}

/** 一帧的完整姿态（纯函数，供绘制与单测共享；角度单位=度） */
data class DachshundPose(
    val legAngles: FloatArray,   // 四腿摆角 [前左,前右,后左,后右]
    val bodyWaveAmp: Float,      // 身体波幅（果冻感）
    val hopY: Float,             // 整体跳跃高度
    val squashY: Float,          // 纵向压扁/拉伸（1=原状）
    val tailSwing: Float,        // 尾巴摆幅
    val earFlap: Float,          // 耳朵飘动
    val tongueLen: Float,        // 舌头伸出长度
    val pant: Float              // 呼吸起伏（POOPED 用）
)

/** 姿态求解（纯函数可单测）：phase∈[0,2π)，stage 决定夸张程度 */
fun dachshundPose(phase: Float, stage: LoadingStage): DachshundPose {
    val run = stage == LoadingStage.RUN || stage == LoadingStage.SWEAT
    val sprint = if (stage == LoadingStage.SWEAT) 0.85f else 1f // 冒汗期稍疲软
    val a = { off: Float -> sin(phase * 2f + off) }
    val legBase = if (run) 42f * sprint else 6f
    return DachshundPose(
        legAngles = floatArrayOf(
            legBase * a(0f), legBase * a(Math.PI.toFloat()),
            legBase * a(0.6f), legBase * a(0.6f + Math.PI.toFloat())
        ),
        bodyWaveAmp = if (run) 3.4f * sprint else 0.6f,
        hopY = if (run) abs(sin(phase)) * 5.5f * sprint else abs(sin(phase)) * 1.2f,
        squashY = if (run) 1f + cos(phase * 2f) * 0.06f else 1f + sin(phase) * 0.02f,
        tailSwing = if (run) 9f * sprint else 3f,
        earFlap = if (run) sin(phase * 2f + 1.2f) * 6f * sprint else sin(phase) * 2f,
        tongueLen = when (stage) {
            LoadingStage.RUN -> 10f
            LoadingStage.SWEAT -> 13f
            LoadingStage.POOPED -> 22f // 拖地
        },
        pant = if (stage == LoadingStage.POOPED) sin(phase) * 1.5f else 0f
    )
}

/** 骨头位置（纯函数可单测）：在前方上下弹跳，永远差一点被追上；POOPED 时原地自转 */
fun bonePose(phase: Float, stage: LoadingStage): Offset {
    val bob = when (stage) {
        LoadingStage.POOPED -> sin(phase * 0.6f) * 2f // 无奈小幅晃
        else -> sin(phase * 1.5f) * 7f
    }
    return Offset(168f, 48f + bob)
}

// ---------- 绘制 ----------

private val FurMain = Color(0xFFF5B971)     // 主毛色（延续旧狗）
private val FurDark = Color(0xFFE09E52)     // 深毛
private val FurPatch = Color(0xFFC77E3B)    // 背斑
private val Outline = Color(0xFF4A3420)     // 描边/五官
private val Tongue = Color(0xFFFF8FA3)      // 舌头
private val BoneCol = Color(0xFFF3EAD3)     // 骨头
private val Dust = Color(0x55B0A08C)        // 尘土
private val Sweat = Color(0xFF7EC8F5)       // 汗滴

private fun DrawScope.drawScene(phase: Float, elapsed: Float, stage: LoadingStage) {
    // 设计坐标空间 200x110 → 任意画布尺寸自适应（预览放大/正式 96dp 均正确）
    val sx = size.width / 200f
    val sy = size.height / 110f
    scale(scaleX = sx, scaleY = sy, pivot = Offset.Zero) {
        val pose = dachshundPose(phase, stage)
        drawGround(stage)
        drawDust(phase, stage)
        drawDog(pose, stage, phase)
        drawBone(phase, stage, elapsed)
        if (stage != LoadingStage.RUN) drawSweat(phase, stage)
    }
}

/** 地面：一条淡色弧线（趴态下更长更平，暗示时间漫长） */
private fun DrawScope.drawGround(stage: LoadingStage) {
    drawLine(
        color = Dust.copy(alpha = 0.5f),
        start = Offset(16f, 84f),
        end = Offset(184f, 84f),
        strokeWidth = 3f,
        cap = StrokeCap.Round
    )
}

/** 身后扬尘：6 粒循环喷出淡出（跑动时才扬尘） */
private fun DrawScope.drawDust(phase: Float, stage: LoadingStage) {
    if (stage == LoadingStage.POOPED) return
    repeat(6) { i ->
        val t = ((phase / (2f * Math.PI.toFloat())) * 1.6f + i * 0.37f) % 1f
        val x = 30f - t * 34f
        val y = 82f - sin(t * Math.PI.toFloat()) * 7f - (i % 3) * 2f
        drawCircle(Dust.copy(alpha = (1f - t) * 0.6f), radius = 3.5f - t * 1.8f, center = Offset(x, y))
    }
}

private fun DrawScope.drawDog(pose: DachshundPose, stage: LoadingStage, phase: Float) {
    val pooped = stage == LoadingStage.POOPED
    translate(top = -pose.hopY - pose.pant) {
        scale(scaleX = 2f - pose.squashY, scaleY = pose.squashY, pivot = Offset(64f, 70f)) {
            drawTail(pose)
            drawBody(pose)
            drawLegs(pose, pooped)
            drawHead(pose, stage, phase)
        }
    }
}

/** 尾巴：三条透明度递减的贝塞尔残影（摇成风扇的观感） */
private fun DrawScope.drawTail(pose: DachshundPose) {
    val base = Offset(22f, 52f)
    repeat(3) { ghost ->
        val sway = pose.tailSwing * (1f - ghost * 0.35f)
        val tip = Offset(base.x - 13f, base.y - 14f + sin(sway) * 6f)
        drawPath(
            Path().apply {
                moveTo(base.x, base.y)
                quadraticBezierTo(base.x - 8f, base.y - 12f + sway * 0.4f, tip.x, tip.y)
            },
            color = FurDark.copy(alpha = 1f - ghost * 0.4f),
            style = Stroke(width = 4f - ghost, cap = StrokeCap.Round)
        )
    }
}

/** 身体：三段胶囊链各自相位差地起伏（果冻波浪传导）+ 背斑 */
private fun DrawScope.drawBody(pose: DachshundPose) {
    val segments = listOf(
        Triple(34f, 24f, 0f),   // 后躯（半径 rx, ry, 相位差）
        Triple(58f, 27f, 0.9f), // 中躯
        Triple(80f, 25f, 1.8f)  // 前躯
    )
    segments.forEach { (cx, r, off) ->
        val dy = sin(pose.legAngles[0] / 6f - off) * pose.bodyWaveAmp
        drawRoundRect(
            color = FurMain,
            topLeft = Offset(cx - r, 50f + dy - 14f),
            size = Size(r * 2f, 30f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 15f)
        )
    }
    // 背部深色斑（呆萌不规则的圆角斑块）
    drawRoundRect(
        color = FurPatch,
        topLeft = Offset(44f, 40f + sin(-0.5f) * pose.bodyWaveAmp),
        size = Size(26f, 12f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
    )
}

/** 四条小短腿：髋部为轴摆动圆柱；POOPED 摊平成 85°（瘫在地面） */
private fun DrawScope.drawLegs(pose: DachshundPose, pooped: Boolean) {
    val hips = listOf(Offset(36f, 68f), Offset(46f, 68f), Offset(74f, 68f), Offset(84f, 68f))
    hips.forEachIndexed { i, hip ->
        val angle = if (pooped) 82f + (i - 1.5f) * 6f else pose.legAngles[i]
        rotateOn(angle, hip) {
            drawRoundRect(
                color = FurDark,
                topLeft = Offset(hip.x - 3f, hip.y),
                size = Size(6f, 17f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
            )
            // 圆脚掌
            drawCircle(FurDark, radius = 4f, center = Offset(hip.x, hip.y + 18f))
        }
    }
}

/** 头：圆头+吻部+大圆眼（呆萌核心）+飘耳+甩舌；POOPED 换「-- 眼」 */
private fun DrawScope.drawHead(pose: DachshundPose, stage: LoadingStage, phase: Float) {
    val pooped = stage == LoadingStage.POOPED
    val headC = Offset(96f, 44f)
    // 长耳向后飘（速度感）
    val earPath = Path().apply {
        moveTo(headC.x - 4f, headC.y - 8f)
        quadraticBezierTo(
            headC.x - 22f + pose.earFlap, headC.y - 6f,
            headC.x - 20f, headC.y + 6f + pose.earFlap * 0.5f
        )
        quadraticBezierTo(headC.x - 10f, headC.y + 4f, headC.x - 6f, headC.y - 2f)
        close()
    }
    drawPath(earPath, FurDark)
    // 头圆
    drawCircle(FurMain, radius = 16f, center = headC)
    // 吻部
    drawRoundRect(
        color = FurMain,
        topLeft = Offset(headC.x + 10f, headC.y - 4f),
        size = Size(14f, 11f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(5f, 5f)
    )
    // 鼻头（黑亮小球）
    drawCircle(Outline, radius = 3.2f, center = Offset(headC.x + 23f, headC.y - 1f))
    // 舌头：从嘴前甩出，随相位弹动；POOPED 拖地弯曲
    val tonguePath = Path().apply {
        val len = pose.tongueLen
        val wob = sin(phase * 2f) * 2.5f
        moveTo(headC.x + 20f, headC.y + 5f)
        if (pooped) {
            quadraticBezierTo(
                headC.x + 20f + len * 0.6f, headC.y + 10f,
                headC.x + 20f + len, headC.y + 22f
            )
        } else {
            quadraticBezierTo(
                headC.x + 20f + len * 0.5f, headC.y + 7f + wob,
                headC.x + 22f + len, headC.y + 6f + wob * 1.6f
            )
        }
    }
    drawPath(tonguePath, Tongue, style = Stroke(width = 5f, cap = StrokeCap.Round))
    // 眼睛：呆萌大圆眼+高光；POOPED 用「--」疲惫眼
    if (pooped) {
        drawLine(Outline, Offset(headC.x + 2f, headC.y - 6f), Offset(headC.x + 8f, headC.y - 5f), 2.2f, StrokeCap.Round)
    } else {
        drawCircle(Color.White, radius = 5.2f, center = Offset(headC.x + 4f, headC.y - 5f))
        drawCircle(Outline, radius = 3.4f, center = Offset(headC.x + 5f, headC.y - 5f))
        drawCircle(Color.White, radius = 1.3f, center = Offset(headC.x + 6.2f, headC.y - 6.2f))
    }
}

/** 骨头：两端双球+骨杆；POOPED 时慢速自转（在前面无聊等着） */
private fun DrawScope.drawBone(phase: Float, stage: LoadingStage, elapsed: Float) {
    val c = bonePose(phase, stage)
    val spin = if (stage == LoadingStage.POOPED) phase * 30f else sin(phase * 1.5f) * 14f
    rotateOn(spin, c) {
        drawRoundRect(BoneCol, Offset(c.x - 11f, c.y - 3f), Size(22f, 6f), androidx.compose.ui.geometry.CornerRadius(3f, 3f))
        drawCircle(BoneCol, 4.6f, Offset(c.x - 11f, c.y - 4f))
        drawCircle(BoneCol, 4.6f, Offset(c.x - 11f, c.y + 4f))
        drawCircle(BoneCol, 4.6f, Offset(c.x + 11f, c.y - 4f))
        drawCircle(BoneCol, 4.6f, Offset(c.x + 11f, c.y + 4f))
    }
}

/** 汗滴：头侧两滴循环下滑淡出（SWEAT 两滴，POOPED 三滴更狼狈但仍好笑） */
private fun DrawScope.drawSweat(phase: Float, stage: LoadingStage) {
    val count = if (stage == LoadingStage.POOPED) 3 else 2
    repeat(count) { i ->
        val t = ((phase / (2f * Math.PI.toFloat())) * 1.3f + i * 0.5f) % 1f
        val x = 100f + i * 5f
        val y = 30f + t * 12f
        drawCircle(Sweat.copy(alpha = (1f - t) * 0.9f), radius = 2.8f, center = Offset(x, y))
    }
}

/** 以 pivot 为轴的旋转绘制（角度制） */
private fun DrawScope.rotateOn(degrees: Float, pivot: Offset, block: DrawScope.() -> Unit) {
    rotate(degrees = degrees, pivot = pivot) { block() }
}

@Composable
private fun isReduceMotionRequested(): Boolean {
    val ctx = LocalContext.current
    return android.provider.Settings.Global.getFloat(
        ctx.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f
}
