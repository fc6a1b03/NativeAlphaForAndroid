package com.cylonid.nativealpha.util

import android.app.Activity
import android.content.res.Configuration
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
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
     * 应用系统栏颜色（状态栏/虚拟键跟随当前主题）。
     *
     * 取色不经资源限定符：`@color/md_theme_surface` 按**系统**夜间模式解析，
     * 与应用内 themeId 脱钩——系统浅色 + 应用强制深色时会取到浅色值，系统栏
     * 与页面割裂。故此处按 [resolveTheme] 同一口径（themeId）直接给出色板，
     * 与应用色板同源（[LIGHT_SURFACE_ARGB]/[DARK_SURFACE_ARGB] 为唯一定义源）。
     *
     * 导航栏对比度 scrim 显式关闭：系统在浅色/半透明导航栏上自动叠灰罩，
     * 三键导航下会把页面色盖成灰条（"不跟应用颜色"的常见元凶）。
     */
    @JvmStatic
    @Suppress("DEPRECATION") // statusBarColor/navigationBarColor 赋值 API 35+ 为 no-op
    // （内容已强制 edge-to-edge 铺到系统栏后），API 31-34 仍需其渲染栏底色
    fun applySystemBarColors(activity: Activity) {
        try {
            val dark = isDarkTheme(activity)
            val color = if (dark) DARK_SURFACE_ARGB else LIGHT_SURFACE_ARGB
            val window = activity.window

            window.statusBarColor = color
            window.navigationBarColor = color
            // 关闭系统对比度 scrim：栏色已与页面同色，叠罩反而割裂
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false

            // 图标亮暗（深色主题=浅色图标）：现代范式 WindowInsetsController（minSdk 31 全可用）
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            Log.d("ThemeUtils", "applySystemBarColors dark=$dark color=$color")
        } catch (e: Exception) {
            Log.w("ThemeUtils", "applySystemBarColors failed", e)
        }
    }

    /**
     * 当前实际深色与否：themeId 指定优先，跟随系统时读资源配置
     * （与 [resolveTheme]/[applyUiMode] 同一判定口径）。
     */
    private fun isDarkTheme(activity: Activity): Boolean {
        return when (DataManager.getInstance().settings.themeId) {
            2 -> true
            1 -> false
            else -> (activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }
}
