package com.cylonid.nativealpha.util

import android.annotation.SuppressLint
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

    /**
     * 应用系统栏颜色（状态栏/虚拟键跟随当前主题，切换主题后调用刷新）。
     * 从当前主题读 statusBarColor/navigationBarColor/windowLightStatusBar/
     * windowLightNavigationBar，同步到窗口——否则切换主题后系统栏颜色残留。
     */
    @JvmStatic
    @SuppressLint("ResourceType") // 动态 attr 数组（statusBarColor 等系统属性），Lint 静态分析误报 styleable 期望
    fun applySystemBarColors(activity: android.app.Activity) {
        try {
            val attrs = intArrayOf(
                android.R.attr.statusBarColor,
                android.R.attr.navigationBarColor,
                android.R.attr.windowLightStatusBar,
                android.R.attr.windowLightNavigationBar
            )
            val ta = activity.obtainStyledAttributes(attrs)
            val statusColor = ta.getColor(0, 0)
            val navColor = ta.getColor(1, 0)
            val lightStatus = ta.getBoolean(2, true)
            val lightNav = ta.getBoolean(3, true)
            ta.recycle()

            val window = activity.window
            window.statusBarColor = statusColor
            window.navigationBarColor = navColor
            // 图标亮暗（浅色主题=深色图标，深色主题=浅色图标）
            var flags = window.decorView.systemUiVisibility
            flags = if (lightStatus) {
                flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            flags = if (lightNav) {
                flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
            window.decorView.systemUiVisibility = flags
            Log.d("ThemeUtils", "applySystemBarColors status=$statusColor nav=$navColor lightStatus=$lightStatus lightNav=$lightNav")
        } catch (e: Exception) {
            Log.w("ThemeUtils", "applySystemBarColors failed", e)
        }
    }
}
