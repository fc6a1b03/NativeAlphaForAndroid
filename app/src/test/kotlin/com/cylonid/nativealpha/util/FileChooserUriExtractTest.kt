package com.cylonid.nativealpha.util

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 选择结果 URI 提取单测（FileChooserDelegate.extractChooserUris）。
 * 核心回归锚点=实机 v2.3.10 导出错误日志实锤的根因：厂商 ROM 把所选 URI
 * 放 ClipData 而 getData() 为 null，framework parseResult 只认 getData()
 * 返回 null → 此前静默当「取消」，表现为「相册选截图不进输入框」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileChooserUriExtractTest {

    @Test
    fun `framework result via getData passes through`() {
        val uri = Uri.parse("content://media/external/images/9")
        val intent = Intent().apply { this.data = uri }
        assertArrayEquals(
            arrayOf(uri),
            FileChooserDelegate.extractChooserUris(Activity.RESULT_OK, intent)
        )
    }

    @Test
    fun `vendor clipdata-only intent is extracted`() {
        // 实锤形态：dataUri=null、clip=1、flags 带读权限——framework parseResult
        // 返回 null，必须从 ClipData 提取
        val uri = Uri.parse("content://com.vendor.gallery/images/42")
        val intent = Intent().apply {
            clipData = ClipData.newRawUri("photo", uri)
            // data 保持 null、type 保持 null（与实机日志一致）
        }
        assertNull(intent.data)
        assertArrayEquals(
            arrayOf(uri),
            FileChooserDelegate.extractChooserUris(Activity.RESULT_OK, intent)
        )
    }

    @Test
    fun `multi-item clipdata extracts all non-null uris`() {
        val u1 = Uri.parse("content://a/1")
        val u2 = Uri.parse("content://a/2")
        val clip = ClipData.newRawUri("f1", u1)
        clip.addItem(ClipData.Item(u2))
        val intent = Intent().apply { this.clipData = clip }
        assertArrayEquals(
            arrayOf(u1, u2),
            FileChooserDelegate.extractChooserUris(Activity.RESULT_OK, intent)
        )
    }

    @Test
    fun `ok with truly empty intent returns null`() {
        assertNull(
            FileChooserDelegate.extractChooserUris(Activity.RESULT_OK, Intent())
        )
    }

    @Test
    fun `canceled result returns null`() {
        val intent = Intent().apply {
            clipData = ClipData.newRawUri("photo", Uri.parse("content://a/1"))
        }
        assertNull(
            FileChooserDelegate.extractChooserUris(Activity.RESULT_CANCELED, intent)
        )
    }
}
