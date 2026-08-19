package com.cylonid.nativealpha.util

import androidx.appcompat.app.AppCompatDelegate
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.DataManager

/**
 * UI 模式工具：themeId（0=跟随系统 / 1=强制浅色 / 2=强制深色）→ 实际生效。
 *
 * 关键：setDefaultNightMode 只对 DayNight 主题生效，而 AppTheme 在
 * values/ 是 Light、values-night/ 是 Dark（硬编码），必须按模式显式选主题。
 */
object ThemeUtils {

    /** 按全局设置返回对应主题资源 ID */
    @JvmStatic
    fun resolveTheme(): Int {
        return when (DataManager.getInstance().settings.themeId) {
            1 -> R.style.AppTheme  // 强制浅色（values/ 的 Light 主题）
            2 -> R.style.AppThemeDark  // 强制深色（显式 Dark 主题）
            else -> R.style.AppTheme  // 跟随系统（values-night 自动切换）
        }
    }

    /** 同步 AppCompatDelegate（供 Activity 重建后正确应用） */
    @JvmStatic
    fun applyUiMode() {
        when (DataManager.getInstance().settings.themeId) {
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
