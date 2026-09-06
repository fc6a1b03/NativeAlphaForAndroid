package com.cylonid.nativealpha.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 文件选择返回 URI 归一化单测（FileChooserUriNormalizer）：厂商文件管理器
 * 转发断链修复的回执层回归锚点。三分支——可读 content 直通 / file 物化 /
 * 物理不可读透传（拷贝必失败，透传让内核再试+日志取证）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileChooserUriNormalizerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `readable content uri passes through untouched`() {
        val uri = Uri.parse("content://media/external/images/151")
        shadowOf(context.contentResolver).registerInputStream(
            uri, ByteArrayInputStream(byteArrayOf(1, 2, 3))
        )
        val out = FileChooserUriNormalizer.normalize(context, listOf(uri))
        assertEquals(arrayOf(uri), out.uris)
        assertTrue(out.degraded.isEmpty())
    }

    @Test
    fun `physically unreadable uri passes through and is reported degraded`() {
        // openInputStream 抛异常=物理不可读（grant 断链的等价形态）：拷贝必失败，
        // 透传原 URI 并记入 degraded（调用方提示用户+错误日志取证）。
        // 注意 ShadowContentResolver 对未注册 content URI 返回空流（恒可读），
        // content 分支的断链形态只能用不存在的 file 路径等价覆盖。
        val uri = Uri.parse("file:///nonexistent/broken/screenshot.png")
        val out = FileChooserUriNormalizer.normalize(context, listOf(uri))
        assertEquals(arrayOf(uri), out.uris)
        assertEquals(listOf(uri), out.degraded)
    }

    @Test
    fun `file uri with readable bytes gets copied into chooser cache dir`() {
        // 物化分支的「拷贝落盘」部分可环境无关地验证；FileProvider.getUriForFile
        // 的 root 匹配依赖真实 Linux 文件系统语义（Robolectric/Windows 下
        // canonical path 不一致），URI 生成本身由真机场景日志覆盖
        val src = File(context.cacheDir, "materialize_src.png").apply {
            writeBytes(byteArrayOf(9, 8, 7))
        }
        val uri = Uri.fromFile(src)
        val out = FileChooserUriNormalizer.normalize(context, listOf(uri))!!
        assertEquals(1, out.uris!!.size)
        // 拷贝落盘是环境无关的核心行为
        val copiedDir = File(context.cacheDir, "chooser")
        assertTrue(copiedDir.isDirectory)
        val copied = copiedDir.listFiles()!!.first()
        assertEquals(3, copied.length())
        assertEquals("png", copied.extension) // MIME→扩展名保留
        // URI 分支取决于运行环境 FileProvider root 解析（真机/CI Linux 成功返回
        // content URI；Robolectric/Windows canonical path 差异失败则透传+降级），
        // 两分支均为合法语义，不硬编码
        if (out.degraded.isEmpty()) {
            assertEquals("content", out.uris!![0].scheme)
        } else {
            assertEquals(listOf(uri), out.degraded)
            assertEquals(uri, out.uris!![0])
        }
    }

    @Test
    fun `null and empty inputs normalize to empty result`() {
        assertEquals(null, FileChooserUriNormalizer.normalize(context, null).uris)
        assertEquals(null, FileChooserUriNormalizer.normalize(context, emptyList()).uris)
    }
}
