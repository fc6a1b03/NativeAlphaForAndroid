package com.cylonid.nativealpha.util

import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.DataManager

/**
 * UI 模式工具：themeId（0=跟随系统 / 1=强制浅色 / 2=强制深色）→ 实际生效。
 *
 * 实现：直接按模式选主题资源（Light/Dark 显式主题 + 固定色板），
 * 不依赖 AppCompat DayNight 变体机制（在部分系统上 setDefaultNightMode 对
 * MDC DayNight 主题强制无效）。
 *
 * 跟随系统模式：用 AppCompatDelegate 跟随系统 uiMode。
 */
object ThemeUtils {

    /** 按全局设置返回对应主题资源 ID（显式 Light/Dark，色板固定不走 values-night） */
    @JvmStatic
    fun resolveTheme(): Int {
        DataManager.getInstance().loadAppData()
        val themeId = DataManager.getInstance().settings.themeId
        Log.d("ThemeUtils", "resolveTheme themeId=$themeId")
        return when (themeId) {
            1 -> R.style.AppTheme          // 强制浅色
            2 -> R.style.AppThemeDark      // 强制深色（固定深色色板）
            else -> R.style.AppTheme       // 跟随系统（values-night 自动）
        }
    }

    /** 同步 AppCompatDelegate（跟随系统模式需要） */
    @JvmStatic
    fun applyUiMode() {
        DataManager.getInstance().loadAppData()
        val themeId = DataManager.getInstance().settings.themeId
        val mode = when (themeId) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        Log.d("ThemeUtils", "applyUiMode themeId=$themeId mode=$mode")
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
