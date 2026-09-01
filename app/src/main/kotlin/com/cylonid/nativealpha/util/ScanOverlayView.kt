package com.cylonid.nativealpha.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.cylonid.nativealpha.R
import kotlin.math.min

/**
 * 扫码取景遮罩（C-扫码，View 体系自绘）：
 * - 全屏半透明黑幕 + 中央圆角方形取景窗（透明镂空）
 * - 四角括号 + 渐变扫描线（1.8s 循环——「正在工作」的语义动效，非装饰）
 * - 提示语绘在取景窗下方（本地化文案，白 78% 满足暗底对比度）
 *
 * 性能：纯 Canvas 几何绘制（几枚矩形+路径），附窗口期才驱动动画帧。
 */
internal class ScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    /** 取景窗边长（可用短边的 72%，上限 300dp） */
    private var cutoutSize = 0f
    private var cutout = RectF()

    private val scrimPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.scan_scrim)
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.scan_bracket)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = CornerPathEffect(4f * density)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scan_hint_text)
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
    }

    private val hint: String = context.getString(R.string.scan_hint)

    /** 扫描线纵向位置（0..1 在取景窗内往复） */
    private var sweepFraction = 0f

    private val sweepAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1_800
        repeatCount = android.animation.ValueAnimator.INFINITE
        repeatMode = android.animation.ValueAnimator.REVERSE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener {
            sweepFraction = it.animatedValue as Float
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = min(min(w, h) * 0.72f, 300f * density)
        val cx = w / 2f
        // 取景窗略高于几何中心（视觉重心 + 让位下方提示/手电）
        val cy = h / 2f - 24f * density
        cutout = RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f)
        cutoutSize = size
        linePaint.shader = LinearGradient(
            cutout.left, 0f, cutout.right, 0f,
            0xFF4F46E5.toInt(), 0xFF7C3AED.toInt(), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cutoutSize <= 0f) return
        // 黑幕镂空：整屏黑幕 + CLEAR 圆角窗（saveLayer 隔离离屏合成）
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        val radius = 24f * density
        canvas.drawRoundRect(cutout, radius, radius, clearPaint)
        canvas.restoreToCount(layer)
        drawBrackets(canvas)
        drawSweepLine(canvas)
        drawHint(canvas)
    }

    /** 四角括号（不整框——视线聚焦且更轻盈） */
    private fun drawBrackets(canvas: Canvas) {
        val len = 28f * density
        val r = 24f * density
        val inset = bracketPaint.strokeWidth / 2f
        val l = cutout.left + inset
        val t = cutout.top + inset
        val rt = cutout.right - inset
        val b = cutout.bottom - inset
        // 左上/右上/左下/右下（沿圆角外沿走弧，与取景窗圆角同心）
        canvas.drawArc(l, t, l + 2 * r, t + 2 * r, 180f, 90f, false, bracketPaint)
        canvas.drawLine(l + r, t, l + r + len, t, bracketPaint)
        canvas.drawLine(l, t + r, l, t + r + len, bracketPaint)
        canvas.drawArc(rt - 2 * r, t, rt, t + 2 * r, 270f, 90f, false, bracketPaint)
        canvas.drawLine(rt - r - len, t, rt - r, t, bracketPaint)
        canvas.drawLine(rt, t + r, rt, t + r + len, bracketPaint)
        canvas.drawArc(l, b - 2 * r, l + 2 * r, b, 90f, 90f, false, bracketPaint)
        canvas.drawLine(l, b - r - len, l, b - r, bracketPaint)
        canvas.drawLine(l + r, b, l + r + len, b, bracketPaint)
        canvas.drawArc(rt - 2 * r, b - 2 * r, rt, b, 0f, 90f, false, bracketPaint)
        canvas.drawLine(rt - r - len, b, rt - r, b, bracketPaint)
        canvas.drawLine(rt, b - r - len, rt, b - r, bracketPaint)
    }

    /** 扫描线：取景窗内横向往复，渐隐两端（Gradient 端点即渐隐） */
    private fun drawSweepLine(canvas: Canvas) {
        val inset = 14f * density
        val y = cutout.top + inset + (cutout.height() - 2 * inset) * sweepFraction
        canvas.drawLine(cutout.left + inset, y, cutout.right - inset, y, linePaint)
    }

    /** 提示语：取景窗下方 28dp 居中 */
    private fun drawHint(canvas: Canvas) {
        val textY = cutout.bottom + 28f * density + hintPaint.textSize
        canvas.drawText(hint, width / 2f, textY, hintPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sweepAnimator.start()
    }

    override fun onDetachedFromWindow() {
        sweepAnimator.cancel()
        super.onDetachedFromWindow()
    }
}
