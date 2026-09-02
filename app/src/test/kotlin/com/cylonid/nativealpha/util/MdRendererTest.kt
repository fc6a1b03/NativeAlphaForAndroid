package com.cylonid.nativealpha.util

import android.content.Context
import android.text.Spanned
import androidx.test.core.app.ApplicationProvider
import com.cylonid.nativealpha.util.MdRenderer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Markdown 渲染方言测试：对齐 GitHub（GFM）行为——单换行渲染为硬换行。
 * 回归背景：release notes 在 GitHub 显示正常换行，进 App 后挤成一行
 * （CommonMark 默认把单换行渲染为空格）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MdRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `single newline renders as line break like GitHub`() {
        val result: Spanned = MdRenderer.render(context, "第一行\n第二行")
        val text = result.toString()
        // 单换行必须保留为换行（GFM breaks），不得折叠为空格
        assertTrue("expected line break, got: [$text]", text.contains("\n"))
        assertFalse("soft break must not collapse to space", text.contains("第一行 第二行"))
    }

    @Test
    fun `blank input renders empty`() {
        assertTrue(MdRenderer.render(context, null).isEmpty())
        assertTrue(MdRenderer.render(context, "  \n  ").isEmpty())
    }

    @Test
    fun `gfm features still render after breaks enabled`() {
        val result = MdRenderer.render(context, "# 标题\n**粗体** and `code`")
        val text = result.toString()
        assertTrue(text.contains("标题"))
        assertTrue(text.contains("粗体"))
        assertTrue(text.contains("code"))
    }
}
