package com.cylonid.nativealpha.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import androidx.core.graphics.toColorInt
import androidx.palette.graphics.Palette
import com.cylonid.nativealpha.model.WebApp

/**
 * 统计页强调色门面（A5）：从站点图标提取主色，驱动英雄卡渐变/热力图色阶/
 * 柱状图主色。调用方只感知 [accent] 与 [heatScale]，不接触 Palette 细节。
 *
 * 回退契约：图标缺失/提取失败一律回退 M3 primary 语义色——永不让页面无色，
 * 失败细节不外泄（门面边界）。
 *
 * 内存纪律（R11）：Palette 结果 LruCache（上限 32 站）——统计页滑动不重复
 * 解码；Bitmap 复用 WebAppIconManager 既有缓存，本类不持位图引用。
 */
internal object StatAccent {

    /** 回退色：M3 靛蓝 seed（#4F46E5，README 品牌色）——非动态主题下的稳定兜底 */
    private const val FALLBACK_COLOR_HEX = "#4F46E5"

    /** 饱和度门槛：低于此值（黑白/灰图标）视为无有效主色，回退品牌色 */
    private const val MIN_SATURATION = 0.15f

    /** 主色缓存（站点 ID → ARGB int；32 站覆盖全站列表绰绰有余） */
    private val cache = LruCache<Int, Int>(32)

    /**
     * 站点强调色（同步：Palette 同步提取在统计页进入时一次性执行，
     * 图标已由 WebAppIconManager 内存/磁盘缓存，无解码放大风险）。
     */
    fun accent(context: Context, webApp: WebApp): androidx.compose.ui.graphics.Color {
        val cached = cache.get(webApp.ID)
        if (cached != null) return androidx.compose.ui.graphics.Color(cached)
        val extracted = extract(context, webApp)
        // 主流做法（Material 跟随色源同类处理）：无彩色源（黑白灰图标）不硬用灰——
        // 回退品牌靛蓝，保证统计页始终有色感
        val argb = if (extracted != null && isColorful(extracted)) extracted else fallbackArgb()
        cache.put(webApp.ID, argb)
        return androidx.compose.ui.graphics.Color(argb)
    }

    /** 有效性：HSV 饱和度 ≥ 门槛才算「有颜色」（灰白图标不算） */
    private fun isColorful(argb: Int): Boolean {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        return hsv[1] >= MIN_SATURATION
    }

    /**
     * 热力图 5 档色阶：低→高活跃。档位取值对齐主流参照（GitHub contributions：
     * 最低活跃档即明显可辨，仅空态用容器色）——纯低 alpha 在浅色底会淡到不可辨，
     * 故低档 35% 起步、高档全饱和。色相仍只由强调色派生（不引入第二色相）。
     */
    fun heatScale(base: androidx.compose.ui.graphics.Color): List<androidx.compose.ui.graphics.Color> =
        listOf(0x59, 0x8C, 0xB8, 0xE0, 0xFF).map { base.copy(alpha = it / 255f) }

    /** Palette 提取：优先 Vibrant，退而求其次取任意有代表性行；失败返回 null 走兜底 */
    private fun extract(context: Context, webApp: WebApp): Int? {
        val bitmap = resolveBitmap(context, webApp) ?: return null
        return try {
            val palette = Palette.from(bitmap).maximumColorCount(12).generate()
            palette.getVibrantColor(Color.TRANSPARENT).takeIf { it != Color.TRANSPARENT }
                ?: palette.getMutedColor(Color.TRANSPARENT).takeIf { it != Color.TRANSPARENT }
                ?: palette.getDominantColor(Color.TRANSPARENT).takeIf { it != Color.TRANSPARENT }
        } catch (ignored: Exception) {
            null
        }
    }

    /** 图标位图解析（WebAppIconManager 唯一图标源；生成的字母图标同样可提取） */
    private fun resolveBitmap(context: Context, webApp: WebApp): Bitmap? =
        try {
            WebAppIconManager.resolveIconCached(context, webApp)
        } catch (ignored: Exception) {
            null
        }

    private fun fallbackArgb(): Int = FALLBACK_COLOR_HEX.toColorInt()
}
