package com.cylonid.nativealpha.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 组合键串生成契约：大写归一与录入面板（ShortcutKeyEditor.buildCombo）
 * 格式对齐——历史版本拼接 keyCodeToChar 的小写返回值，大小写不一致
 * 致已绑定组合键永不命中（绑定存 "Ctrl+S"、运行时生成 "Ctrl+s"）。
 */
class ShortcutComboTest {

    @Test
    fun `uppercase normalization aligns with editor format`() {
        assertEquals("Ctrl+S", WebViewShortcutInjectHelper.comboString(true, false, false, "s"))
        assertEquals("Ctrl+S", WebViewShortcutInjectHelper.comboString(true, false, false, "S"))
    }

    @Test
    fun `modifier order is ctrl shift alt`() {
        assertEquals(
            "Ctrl+Shift+Alt+Enter",
            WebViewShortcutInjectHelper.comboString(true, true, true, "Enter")
        )
        assertEquals("Shift+A", WebViewShortcutInjectHelper.comboString(false, true, false, "A"))
        assertEquals("Alt+F5", WebViewShortcutInjectHelper.comboString(false, false, true, "F5"))
    }

    @Test
    fun `non letter keys keep original casing`() {
        assertEquals("Ctrl+F1", WebViewShortcutInjectHelper.comboString(true, false, false, "F1"))
        assertEquals(
            "Ctrl+Backspace",
            WebViewShortcutInjectHelper.comboString(true, false, false, "Backspace")
        )
    }

    @Test
    fun `modifiers only without key is rejected`() {
        assertNull(WebViewShortcutInjectHelper.comboString(false, false, false, "s"))
        assertNull(WebViewShortcutInjectHelper.comboString(true, true, true, null))
        assertNull(WebViewShortcutInjectHelper.comboString(false, false, false, null))
    }
}
