package com.cylonid.nativealpha.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * 动态图标生成器：favicon 拉取失败时的优雅兜底。
 *
 * 生成规则：
 * - 圆角矩形背景，使用站点域名 hash 从渐变池选色（同站点颜色稳定）
 * - 中央显示站点名首字母（大写，白色，粗体）
 * - 尺寸由调用方指定（快捷方式图标通常 192x192）
 */
object IconGenerator {

    /** 渐变池：现代 Material 风格双色渐变 */
    private val GRADIENTS = arrayOf(
        intArrayOf(0xFF4F46E5.toInt(), 0xFF7C3AED.toInt()), // indigo → purple
        intArrayOf(0xFF0EA5E9.toInt(), 0xFF2563EB.toInt()), // sky → blue
        intArrayOf(0xFF10B981.toInt(), 0xFF059669.toInt()), // emerald
        intArrayOf(0xFFF59E0B.toInt(), 0xFFEA580C.toInt()), // amber → orange
        intArrayOf(0xFFEF4444.toInt(), 0xFFDC2626.toInt()), // red
        intArrayOf(0xFFEC4899.toInt(), 0xFFDB2777.toInt()), // pink
        intArrayOf(0xFF14B8A6.toInt(), 0xFF0D9488.toInt()), // teal
        intArrayOf(0xFF8B5CF6.toInt(), 0xFF6D28D9.toInt())  // violet
    )

    /**
     * 生成兜底图标。
     *
     * @param siteName 站点名（取首字母显示；为空时显示 "?"）
     * @param domain   域名（用于稳定选色；为空时用默认色）
     * @param sizePx   图标尺寸（正方形，像素）
     * @param cornerRadiusPx 圆角半径（像素）
     */
    @JvmStatic
    fun generate(siteName: String?, domain: String?, sizePx: Int, cornerRadiusPx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 选色：按域名 hash 稳定取渐变
        val seed = (domain ?: "").hashCode().let { if (it == Int.MIN_VALUE) 0 else Math.abs(it) }
        val colors = GRADIENTS[seed % GRADIENTS.size]

        // 圆角背景 + 渐变
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), colors[0], colors[1], Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), cornerRadiusPx.toFloat(), cornerRadiusPx.toFloat(), bgPaint)

        // 首字母
        val letter = (siteName ?: "").trim().firstOrNull()?.uppercaseChar() ?: '?'
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = sizePx * 0.5f
            textAlign = Paint.Align.CENTER
        }

        // 垂直居中（考虑基线偏移）
        val baseline = (sizePx / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(letter.toString(), sizePx / 2f, baseline, textPaint)

        return bitmap
    }
}
