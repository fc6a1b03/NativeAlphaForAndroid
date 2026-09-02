package com.cylonid.nativealpha.util

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cylonid.nativealpha.util.WebAppDataFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * WebAppDataFetcher 图标解析测试：
 * 覆盖 ICO 容器解析（fanyi.baidu.com 场景）与异常输入防护。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebAppDataFetcherTest {

    /** 真实 PNG 字节（应用自带图标，Robolectric 可读 res） */
    private fun realPng(): ByteArray {
        val res = Resources.getSystem()
        val id = res.getIdentifier("webnative", "mipmap", "com.cylonid.nativealpha")
        if (id != 0) {
            val bmp = BitmapFactory.decodeResource(res, id)
            if (bmp != null) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                return out.toByteArray()
            }
        }
        // 兜底：1x1 已知合法 PNG（base64）
        return Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
        )
    }

    /** 构造一个 ICO：头 + 1 个条目（内嵌真实 PNG） */
    private fun buildIco(pngBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        // ICONDIR: reserved(2) + type(2)=1 + count(2)=1
        out.write(byteArrayOf(0, 0, 1, 0, 1, 0))
        // ICONDIRENTRY: w=32, h=32, colors=0, reserved=0, planes=1, bpp=32
        out.write(byteArrayOf(32, 32, 0, 0, 1, 0, 32, 0))
        // bytesInRes(4 LE) + imageOffset(4 LE)=22
        val size = pngBytes.size
        out.write(byteArrayOf(
            (size and 0xFF).toByte(),
            ((size shr 8) and 0xFF).toByte(),
            ((size shr 16) and 0xFF).toByte(),
            ((size shr 24) and 0xFF).toByte(),
            22, 0, 0, 0
        ))
        out.write(pngBytes)
        return out.toByteArray()
    }

    @Test
    fun `decodeIco extracts embedded png`() {
        val ico = buildIco(realPng())
        val bmp = WebAppDataFetcher.decodeIco(ico)
        assertNotNull("ICO 内嵌 PNG 应被解码", bmp)
        assertNotNull(bmp)
    }

    @Test
    fun `decodeIco rejects non-ico header`() {
        val fake = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertNull(WebAppDataFetcher.decodeIco(fake))
    }

    @Test
    fun `decodeIco rejects truncated data`() {
        assertNull(WebAppDataFetcher.decodeIco(byteArrayOf(0, 0, 1, 0, 2, 0, 32, 32)))
    }

    @Test
    fun `decodeIco rejects empty`() {
        assertNull(WebAppDataFetcher.decodeIco(ByteArray(0)))
    }

    @Test
    fun `loadBitmap returns null for blank url`() {
        assertNull(WebAppDataFetcher.loadBitmap(""))
        assertNull(WebAppDataFetcher.loadBitmap(null))
    }

    @Test
    fun `isUsableIconUrl filters invalid urls`() {
        // 有效：http/https
        assertTrue(WebAppDataFetcher.isUsableIconUrl("https://example.com/favicon.ico"))
        assertTrue(WebAppDataFetcher.isUsableIconUrl("http://example.com/icon.png"))
        // 无效：空 / data:（example.com 的 data:, 空图标声明）
        assertFalse(WebAppDataFetcher.isUsableIconUrl(""))
        assertFalse(WebAppDataFetcher.isUsableIconUrl("data:,"))
        assertFalse(WebAppDataFetcher.isUsableIconUrl("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(WebAppDataFetcher.isUsableIconUrl("javascript:void(0)"))
        assertFalse(WebAppDataFetcher.isUsableIconUrl(null))
        // 大小写敏感：http 开头（URL 协议规范小写）
        assertFalse(WebAppDataFetcher.isUsableIconUrl("HTTP://EXAMPLE.COM/icon.png"))
    }
}
