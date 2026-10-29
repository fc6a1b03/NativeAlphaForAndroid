package com.cylonid.nativealpha.util

import android.app.Activity
import android.content.res.Configuration
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.drawable.toDrawable
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
            // targetSdk 35+ 系统栏透明、上述赋值被忽略，栏区显示的是窗口背景
            // ——必须同步设置窗口背景，否则系统主题切换（uiMode 在 configChanges
            // 中，Activity 不重建）时页面已换色而栏区仍停留旧色
            window.setBackgroundDrawable(color.toDrawable())
            // 关闭系统对比度 scrim：栏色已与页面同色，叠罩反而割裂
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false

            // 图标亮暗（深色主题=浅色图标）：现代范式 WindowInsetsController（minSdk 31 全可用）
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
            Log.d("ThemeUtils", "applySystemBarColors dark=$dark color=$color")
            // 实机取证：厂商 ROM 对栏色的处理差异无法在模拟器复现，把决策输入
            // （装置版本/主题/实际 uiMode/导航模式）与写入结果一并落诊断日志，
            // 用户报"栏色不跟页面"时按此单条即可判断是判定错、写入被忽略，
            // 还是系统栏被 ROM 改造（导航模式/对比度强制）
            val cfg = activity.resources.configuration
            val nightMode = when (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                Configuration.UI_MODE_NIGHT_YES -> "yes"
                Configuration.UI_MODE_NIGHT_NO -> "no"
                else -> "undef"
            }
            val baseFields = mapOf(
                "act" to activity.javaClass.simpleName,
                "sdk" to android.os.Build.VERSION.SDK_INT,
                "themeId" to DataManager.getInstance().settings.themeId,
                "uiMode" to nightMode,
                "dark" to dark,
                "color" to Integer.toHexString(color),
                "navMode" to if (isThreeButtonNav(activity)) "3btn" else "gesture"
            )
            ErrorReporter.probe(activity, "SysBar", "sysbar_apply", fields = baseFields)
            // insets 在 onCreate 阶段未就绪：窗口 attach 后补一条，带栏可见性
            // 与 ROM 实际生效值（真实栏色只能从 InsetsController 侧观察）
            window.decorView.post {
                try {
                    val insets = window.decorView.rootWindowInsets
                    val controller = window.decorView.windowInsetsController
                    ErrorReporter.probe(
                        activity, "SysBar", "sysbar_attached",
                        fields = baseFields + mapOf(
                            "statusVisible" to insets?.isVisible(
                                android.view.WindowInsets.Type.statusBars()
                            ),
                            "navVisible" to insets?.isVisible(
                                android.view.WindowInsets.Type.navigationBars()
                            ),
                            "navBarHeight" to insets?.getInsets(
                                android.view.WindowInsets.Type.navigationBars()
                            )?.bottom,
                            // 实际生效的外观位（ROM 可能覆盖图标亮暗）
                            "appearance" to controller?.systemBarsAppearance,
                            "viewportW" to activity.resources.displayMetrics.widthPixels,
                            "viewportH" to activity.resources.displayMetrics.heightPixels
                        )
                    )
                } catch (ignored: Exception) {
                    // 窗口已销毁：本次取证跳过
                }
            }
        } catch (e: Exception) {
            Log.w("ThemeUtils", "applySystemBarColors failed", e)
        }
    }

    /**
     * 三键导航判定（取证用）：导航栏高度显著大于手势条时视为三键。
     * 与系统实际实现无关，仅用于日志对照厂商 ROM 行为。
     */
    private fun isThreeButtonNav(activity: Activity): Boolean {
        val insets = activity.window.decorView.rootWindowInsets ?: return false
        val navHeight = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        val dm = activity.resources.displayMetrics
        // 手势条约 24-48dp，三键导航通常 ≥ 48dp；此处仅作日志分类
        return navHeight > (56 * dm.density).toInt()
    }

    /**
     * Activity 当前实际深色与否。
     *
     * 必须读 Activity 实际配置而非全局 themeId：`localNightMode`（站点强制
     * 深色、按站主题）会在运行期改写该 Activity 的 uiMode，只有实际配置才是
     * 系统栏与页面（WebView 的 prefers-color-scheme）共用的同一事实。读
     * themeId 会在「应用浅色 + 系统深色」等组合下与实际渲染割裂——错误页已
     * 按系统转深，系统栏却仍是浅色。
     */
    private fun isDarkTheme(activity: Activity): Boolean =
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /**
     * 应用内主题对应的 AppCompat 夜间模式（全应用唯一口径）：
     * themeId 1=强制浅色 / 2=强制深色 / 其他=跟随系统。
     * 供运行期显式设置 localNightMode 的页面复用，防止各处写死
     * FOLLOW_SYSTEM 覆盖用户选择。
     */
    @JvmStatic
    fun appNightMode(): Int {
        DataManager.getInstance().loadAppData()
        return when (DataManager.getInstance().settings.themeId) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }
}
