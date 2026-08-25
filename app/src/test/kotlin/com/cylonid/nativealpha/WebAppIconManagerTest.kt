package com.cylonid.nativealpha

import android.graphics.Bitmap
import android.content.Context
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.WebAppIconManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

/**
 * WebAppIconManager 单测：统一源（iconPath 存 App 文件目录）的保存/加载/删除闭环。
 * 不依赖 UI：验证数据流正确（存储 -> 更新字段 -> 读取）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebAppIconManagerTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private fun fakeBitmap(): Bitmap =
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    @Test
    fun saveIcon_storesFileAndUpdatesPath() {
        val webApp = WebApp("https://example.com", 999)
        assertNull("初始 iconPath 应为 null", webApp.iconPath)

        val ok = WebAppIconManager.saveIcon(context, webApp, fakeBitmap())

        assertTrue("保存应成功", ok)
        assertNotNull("iconPath 应更新", webApp.iconPath)
        // 文件真实存在
        assertTrue("图标文件应存在", java.io.File(webApp.iconPath!!).exists())
    }

    @Test
    fun loadIcon_returnsBitmapFromPath() {
        val webApp = WebApp("https://example.com", 998)
        WebAppIconManager.saveIcon(context, webApp, fakeBitmap())

        val loaded = WebAppIconManager.loadIcon(context, webApp)
        assertNotNull("加载应返回 bitmap", loaded)
        assertEquals("尺寸应一致", 64, loaded!!.width)
    }

    @Test
    fun deleteIcon_removesFileAndClearsPath() {
        val webApp = WebApp("https://example.com", 997)
        WebAppIconManager.saveIcon(context, webApp, fakeBitmap())
        val path = webApp.iconPath!!

        WebAppIconManager.deleteIcon(context, webApp)

        assertNull("iconPath 应清空", webApp.iconPath)
        assertTrue("旧文件应删除", !java.io.File(path).exists())
    }

    @Test
    fun loadIcon_noPath_returnsNull() {
        val webApp = WebApp("https://example.com", 996)
        assertNull("无 iconPath 应返回 null", WebAppIconManager.loadIcon(context, webApp))
    }

    @Test
    fun resolveIconCached_noPath_returnsLetterFallbackWithoutNetwork() {
        val webApp = WebApp("https://example.com", 995)
        // iconPath 为 null：应直接返回字母渐变兜底（不发网络、不持久化 iconPath）
        val bmp = WebAppIconManager.resolveIconCached(context, webApp)
        assertNotNull("无图标时应返回字母兜底", bmp)
        assertNull("resolveIconCached 不得触发网络持久化", webApp.iconPath)
    }

    @Test
    fun resolveIconCached_withPath_returnsSavedIcon() {
        val webApp = WebApp("https://example.com", 994)
        WebAppIconManager.saveIcon(context, webApp, fakeBitmap())

        val bmp = WebAppIconManager.resolveIconCached(context, webApp)
        assertNotNull("有 iconPath 应返回已存图标", bmp)
        assertEquals("尺寸应一致", 64, bmp.width)
    }
}
