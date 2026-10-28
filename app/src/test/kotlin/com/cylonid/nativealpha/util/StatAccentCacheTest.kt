package com.cylonid.nativealpha.util

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.cylonid.nativealpha.model.WebApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * StatAccent.accentCached 缓存快速路径契约（主列表 Expressive 批次）：
 * - 未提取过 → null（调用方走 IO 异步，组合期零 Palette 开销）
 * - accent() 提取后 → 与提取结果同色（缓存命中同步显示）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatAccentCacheTest {

    private fun webApp(id: Int) = WebApp("https://example$id.com", id, id).apply {
        title = "Site$id"
    }

    /** 纯色位图：Palette 提取必有确定主色 */
    private fun solidBitmap(argb: Int): Bitmap =
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { it.eraseColor(argb) }

    @Test
    fun cachedBeforeExtractReturnsNull() {
        val app = webApp(9901)
        assertNull(StatAccent.accentCached(app))
    }

    @Test
    fun cachedAfterExtractMatchesAccent() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val app = webApp(9902)
        val accent = StatAccent.accent(context, app)
        assertEquals(accent, StatAccent.accentCached(app))
    }
}
