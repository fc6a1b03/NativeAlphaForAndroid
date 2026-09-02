package com.cylonid.nativealpha.helper

import com.cylonid.nativealpha.helper.WebViewGestureHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * 双击手势判定契约测试：JS 结果解析归一化 + 弹菜单决策 + 脚本内容契约。
 * buildLongPressJs 的内容断言保证「JS 返回语义」与「Kotlin 消费常量」同源不漂移。
 */
class WebGestureHelperTest {

    // ---------- parseLongPressResult ----------

    @Test
    fun `parse strips quotes from js string result`() {
        assertEquals("blank", WebViewGestureHelper.parseLongPressResult("\"blank\""))
        assertEquals("input", WebViewGestureHelper.parseLongPressResult("\"input\""))
        assertEquals("text", WebViewGestureHelper.parseLongPressResult("\"text\""))
        assertEquals("interactive", WebViewGestureHelper.parseLongPressResult("\"interactive\""))
    }

    @Test
    fun `parse maps null empty and js-null to blank`() {
        assertEquals("blank", WebViewGestureHelper.parseLongPressResult(null))
        assertEquals("blank", WebViewGestureHelper.parseLongPressResult(""))
        assertEquals("blank", WebViewGestureHelper.parseLongPressResult("null"))
        assertEquals("blank", WebViewGestureHelper.parseLongPressResult("\"\""))
    }

    // ---------- shouldShowMenuOnDoubleTap ----------

    @Test
    fun `menu shows on blank and input`() {
        assertTrue(WebViewGestureHelper.shouldShowMenuOnDoubleTap("blank"))
        assertTrue(WebViewGestureHelper.shouldShowMenuOnDoubleTap("input"))
    }

    @Test
    fun `menu stays hidden on text media interactive and unknown values`() {
        assertFalse(WebViewGestureHelper.shouldShowMenuOnDoubleTap("text"))
        assertFalse(WebViewGestureHelper.shouldShowMenuOnDoubleTap("media"))
        assertFalse(WebViewGestureHelper.shouldShowMenuOnDoubleTap("interactive"))
        assertFalse(WebViewGestureHelper.shouldShowMenuOnDoubleTap(""))
        assertFalse(WebViewGestureHelper.shouldShowMenuOnDoubleTap("unknown-value"))
    }

    // ---------- buildLongPressJs 内容契约 ----------

    @Test
    fun `script keeps coordinate placeholders and survives format`() {
        val script = WebViewGestureHelper.buildLongPressJs()
        assertTrue(script.contains("%1\$f"))
        assertTrue(script.contains("%2\$f"))
        val formatted = String.format(Locale.US, script, 120.5f, 240.5f)
        assertTrue(formatted.contains("120.5"))
        assertTrue(formatted.contains("240.5"))
    }

    @Test
    fun `script covers all five semantic returns`() {
        val script = WebViewGestureHelper.buildLongPressJs()
        assertTrue(script.contains("return 'blank'"))
        assertTrue(script.contains("return 'input'"))
        assertTrue(script.contains("return 'text'"))
        assertTrue(script.contains("return 'media'"))
        assertTrue(script.contains("return 'interactive'"))
    }

    @Test
    fun `script routes whitelisted input types to input via generated chain`() {
        val script = WebViewGestureHelper.buildLongPressJs()
        WebViewGestureHelper.MENU_INPUT_TYPES.forEach { type ->
            assertTrue("missing type check for '$type'", script.contains("ty==='$type'"))
        }
        // 缺省 type 属性按 text 处理的回退表达式必须存在
        assertTrue(script.contains("?it.getAttribute('type'):'')||'text'"))
    }

    @Test
    fun `script treats textarea textbox and contenteditable as input`() {
        val script = WebViewGestureHelper.buildLongPressJs()
        assertTrue(script.contains("if(t2==='textarea')return 'input';"))
        assertTrue(script.contains("if(role==='textbox')return 'input';"))
        assertTrue(script.contains("if(it.isContentEditable)return 'input';"))
    }

    @Test
    fun `script keeps functional controls interactive unchanged`() {
        val script = WebViewGestureHelper.buildLongPressJs()
        // 功能型控件归属不变：checkbox/radio/searchbox 等仍交还网页
        assertTrue(script.contains("if(t2==='button'||t2==='a'||t2==='select'"))
        assertTrue(script.contains("role==='checkbox'"))
        assertTrue(script.contains("role==='searchbox'"))
    }
}
