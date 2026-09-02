package com.cylonid.nativealpha.helper

import android.os.SystemClock
import android.view.KeyEvent
import com.cylonid.nativealpha.WebViewActivity
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.util.StatsRecorder
import java.util.Locale

/**
 * 组合键注入处理器（v2.2.0 P3 第三刀，自 WebViewActivity 逐行迁移，零语义变更）。
 *
 * 职责：快捷键字符串解析后的两路注入——JS 合成 KeyboardEvent（主方案）+
 * 真实 KeyEvent 派发（补充）；键码映射工具；绑定快捷键判定。
 *
 * parseShortcut 留在 WebViewActivity companion（CoreLogicTest 直接调用，
 * 单测契约不动）；本类通过 WebViewActivity.parseShortcut 复用。
 */
class WebViewShortcutInjectHelper(private val activity: WebViewActivity) {

    companion object {
        /**
         * 组合键串生成（纯函数可单测）：主键单字符统一大写，与录入面板
         * ShortcutKeyEditor.buildCombo 的大写格式对齐——历史版本直接拼
         * keyCodeToChar 的小写返回值，大小写不一致致已绑定组合键永不命中。
         * 返回 null=非候选（无修饰键/主键不可识别）。
         */
        internal fun comboString(
            ctrl: Boolean,
            shift: Boolean,
            alt: Boolean,
            key: String?
        ): String? {
            if (key == null) return null
            // 仅捕获组合键（Ctrl/Shift/Alt 单独按下不处理）
            if (!ctrl && !shift && !alt) return null
            val normalized = if (key.length == 1) key.uppercase() else key
            val sb = StringBuilder()
            if (ctrl) sb.append("Ctrl+")
            if (shift) sb.append("Shift+")
            if (alt) sb.append("Alt+")
            sb.append(normalized)
            return sb.toString()
        }
    }

    /** 快捷键注入入口：JS 合成 + KeyEvent 双路（原实现逐行对应） */
    fun sendShortcutToPage(shortcut: String?) {
        if (activity.wv == null || shortcut.isNullOrEmpty()) return
        // 统计：记录发送次数（面板/统计页反馈）
        StatsRecorder.recordShortcutSent(activity.webappID, shortcut)
        // 解析组合键 → keyCode + metaState（按字面量 "+" 分割，避免正则 crash）
        val parsed = WebViewActivity.parseShortcut(shortcut) ?: return
        val keyCode = keyCodeOf(parsed.key)
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return
        val metaState = (if (parsed.ctrl) KeyEvent.META_CTRL_ON else 0) or
            (if (parsed.shift) KeyEvent.META_SHIFT_ON else 0) or
            (if (parsed.alt) KeyEvent.META_ALT_ON else 0)

        // 方案一：JS 合成 KeyboardEvent（主方案——kimi code 源码确认不校验 isTrusted）
        // 带 code 字段（CodeMirror 类编辑器按 e.code 匹配）+ 聚焦输入框（target 正确）
        injectJsWithFocus(parsed.ctrl, parsed.shift, parsed.alt, parsed.key)
        // 方案二：注入真实 KeyEvent（补充——对校验 isTrusted 的站点生效）
        // 保持当前页面焦点（不强行聚焦输入框——兼容多种网页）
        try {
            activity.wv!!.requestFocus()
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState)
            val up = KeyEvent(now, now + 50, KeyEvent.ACTION_UP, keyCode, 0, metaState)
            activity.wv!!.dispatchKeyEvent(down)
            activity.wv!!.dispatchKeyEvent(up)
        } catch (ignored: Exception) {
            // KeyEvent 注入失败静默（JS 合成已发）
        }
    }

    /**
     * JS 合成 + 聚焦输入框：先聚焦页面输入框（kimi code 的 inject 需输入框有焦点），
     * 再向 activeElement 派发带 code 的 KeyboardEvent（CodeMirror 按 e.code 匹配）。
     */
    private fun injectJsWithFocus(ctrl: Boolean, shift: Boolean, alt: Boolean, key: String) {
        // 聚焦脚本：当前焦点是 body 时聚焦第一个输入框（textarea/contenteditable/input）
        val focusJs = "(function(){var t=document.activeElement;" +
            "if(!t||t===document.body){" +
            "var els=document.querySelectorAll('textarea,[contenteditable=true],input[type=text],input:not([type])');" +
            "if(els.length>0)els[0].focus();" +
            "}return true;})()"
        try {
            activity.wv!!.evaluateJavascript(focusJs) { _ ->
                injectJsFallback(ctrl, shift, alt, key)
            }
        } catch (ignored: Exception) {
            injectJsFallback(ctrl, shift, alt, key)
        }
    }

    /** JS 合成 KeyboardEvent（kimi code 等不校验 isTrusted，合成事件可收到） */
    private fun injectJsFallback(ctrl: Boolean, shift: Boolean, alt: Boolean, key: String) {
        val jsKey = if (shift) key.uppercase(Locale.US) else key.lowercase(Locale.US)
        // code 字段：CodeMirror 类编辑器按 e.code（KeyS）匹配，必须带上
        val jsCode = keyCodeToJsCode(key)
        val js = "var t=document.activeElement||document.body;" +
            "t.dispatchEvent(new KeyboardEvent('keydown',{key:'" + jsKey + "',code:'" + jsCode +
            "',ctrlKey:" + ctrl + ",shiftKey:" + shift + ",altKey:" + alt +
            ",bubbles:true,cancelable:true}));" +
            "t.dispatchEvent(new KeyboardEvent('keyup',{key:'" + jsKey + "',code:'" + jsCode +
            "',ctrlKey:" + ctrl + ",shiftKey:" + shift + ",altKey:" + alt +
            ",bubbles:true,cancelable:true}));"
        try {
            activity.wv?.evaluateJavascript(js, null)
        } catch (ignored: Exception) {
            // JS 注入失败静默
        }
    }

    /** 主键 → JS KeyboardEvent.code（KeyA..KeyZ / Digit0..9 / F1..F12 / Enter / Space / Tab / Backspace） */
    private fun keyCodeToJsCode(key: String): String {
        if (key.length == 1) {
            val c = key[0]
            if (c in 'A'..'Z') return "Key" + c
            if (c in 'a'..'z') return "Key" + c.uppercaseChar()
            if (c in '0'..'9') return "Digit" + c
        }
        return when (key) {
            "Enter" -> "Enter"
            "Space" -> "Space"
            "Tab" -> "Tab"
            "Backspace" -> "Backspace"
            "F1", "F2", "F3", "F4", "F5", "F6",
            "F7", "F8", "F9", "F10", "F11", "F12" -> key
            else -> ""
        }
    }

    /** 组合键主键字符串 → Android KeyCode（A-Z / 0-9 / F1-F12 / Enter / Space / Tab / Backspace） */
    private fun keyCodeOf(key: String): Int {
        if (key.length == 1) {
            val c = key[0]
            if (c in 'A'..'Z') return KeyEvent.KEYCODE_A + (c - 'A')
            if (c in 'a'..'z') return KeyEvent.KEYCODE_A + (c - 'a')
            if (c in '0'..'9') return KeyEvent.KEYCODE_0 + (c - '0')
        }
        return when (key) {
            "Enter" -> KeyEvent.KEYCODE_ENTER
            "Space" -> KeyEvent.KEYCODE_SPACE
            "Tab" -> KeyEvent.KEYCODE_TAB
            "Backspace" -> KeyEvent.KEYCODE_DEL
            "F1" -> KeyEvent.KEYCODE_F1
            "F2" -> KeyEvent.KEYCODE_F2
            "F3" -> KeyEvent.KEYCODE_F3
            "F4" -> KeyEvent.KEYCODE_F4
            "F5" -> KeyEvent.KEYCODE_F5
            "F6" -> KeyEvent.KEYCODE_F6
            "F7" -> KeyEvent.KEYCODE_F7
            "F8" -> KeyEvent.KEYCODE_F8
            "F9" -> KeyEvent.KEYCODE_F9
            "F10" -> KeyEvent.KEYCODE_F10
            "F11" -> KeyEvent.KEYCODE_F11
            "F12" -> KeyEvent.KEYCODE_F12
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
    }

    /** 是否已绑定的组合键 */
    fun isBoundShortcut(shortcut: String): Boolean {
        val w = DataManager.getInstance().getWebAppIgnoringGlobalOverride(activity.webappID, true)
        return w != null && w.keyShortcuts.contains(shortcut)
    }

    /**
     * dispatchKeyEvent 组合键拦截（重构时自 WebViewActivity 逐行迁移，零语义变更）：
     * 已绑定组合键拦截发送（不触发浏览器默认），管理在设置页点选录入。
     * 返回 true 表示已消费（Activity 不再下传）。
     */
    fun tryInterceptShortcutKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val key = keyCodeToChar(event.keyCode, event.isShiftPressed)
        val combo = comboString(
            event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, key
        ) ?: return false
        // 已绑定快捷键：拦截发送（不触发浏览器默认）
        if (!isBoundShortcut(combo)) return false
        sendShortcutToPage(combo)
        return true
    }

    /** keyCode → 字符（字母/数字/功能键） */
    fun keyCodeToChar(keyCode: Int, shift: Boolean): String? {
        if (keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            val c = 'a' + (keyCode - KeyEvent.KEYCODE_A)
            return if (shift) c.uppercaseChar().toString() else c.toString()
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            return ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_F1 -> "F1"
            KeyEvent.KEYCODE_F2 -> "F2"
            KeyEvent.KEYCODE_F3 -> "F3"
            KeyEvent.KEYCODE_F4 -> "F4"
            KeyEvent.KEYCODE_F5 -> "F5"
            KeyEvent.KEYCODE_F6 -> "F6"
            KeyEvent.KEYCODE_F7 -> "F7"
            KeyEvent.KEYCODE_F8 -> "F8"
            KeyEvent.KEYCODE_F9 -> "F9"
            KeyEvent.KEYCODE_F10 -> "F10"
            KeyEvent.KEYCODE_F11 -> "F11"
            KeyEvent.KEYCODE_F12 -> "F12"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_SPACE -> " "
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            else -> null
        }
    }
}
