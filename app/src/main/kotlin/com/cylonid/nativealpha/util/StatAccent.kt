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

    /** 主色缓存（站点 ID → ARGB int；32 站覆盖全站列表绰绰有余） */
    private val cache = LruCache<Int, Int>(32)

    /**
     * 站点强调色（同步：Palette 同步提取在统计页进入时一次性执行，
     * 图标已由 WebAppIconManager 内存/磁盘缓存，无解码放大风险）。
     */
    fun accent(context: Context, webApp: WebApp): androidx.compose.ui.graphics.Color {
        val cached = cache.get(webApp.ID)
        if (cached != null) return androidx.compose.ui.graphics.Color(cached)
        val argb = extract(context, webApp) ?: fallbackArgb()
        cache.put(webApp.ID, argb)
        return androidx.compose.ui.graphics.Color(argb)
    }

    /**
     * 热力图 5 档色阶：强调色按 alpha 梯度生成（低→高活跃）。
     * 统一视觉语言：色阶只由强调色派生，不引入第二色相。
     */
    fun heatScale(base: androidx.compose.ui.graphics.Color): List<androidx.compose.ui.graphics.Color> =
        listOf(0x1A, 0x40, 0x66, 0x99, 0xFF).map { base.copy(alpha = it / 255f) }

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
