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
    fun applySystemBarColors(activity: Activity, siteColor: Int? = null) {
        try {
            val dark = isDarkTheme(activity)
            // 站点 theme-color 优先（用户定调「直接取站点消息改变 UI 渲染」）：
            // 站点自带深色而应用主题浅色时，栏色必须跟页面，否则黑白割裂
            val color = siteColor ?: if (dark) DARK_SURFACE_ARGB else LIGHT_SURFACE_ARGB
            // 图标亮暗按**最终栏色**亮度判定：站点可能自定义任意色，
            // 沿用主题深浅会在站点色与主题反向时出现图标不可见
            val lightIcons = !isLightColor(color)
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

            // 图标亮暗：现代范式 WindowInsetsController（minSdk 31 全可用）
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = !lightIcons
            controller.isAppearanceLightNavigationBars = !lightIcons
            Log.d("ThemeUtils", "applySystemBarColors dark=$dark site=${siteColor != null} color=$color")
            // 实机取证（节流）：仅当「颜色 + 页面」相对上次发生变化时记一条。
            // 同一页面重复调用（onCreate/onResume/onConfigurationChanged 都会来）
            // 不落盘，避免日志刷爆；换页面即使同色也记——否则跳转链路取证断档。
            val probeKey = activity.javaClass.simpleName to color
            if (lastProbedKey != probeKey) {
                lastProbedKey = probeKey
                val cfg = activity.resources.configuration
                val nightMode = when (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
                    Configuration.UI_MODE_NIGHT_YES -> "yes"
                    Configuration.UI_MODE_NIGHT_NO -> "no"
                    else -> "undef"
                }
                ErrorReporter.probe(
                    activity, "SysBar", "sysbar_apply",
                    fields = mapOf(
                        "act" to activity.javaClass.simpleName,
                        "sdk" to android.os.Build.VERSION.SDK_INT,
                        "themeId" to DataManager.getInstance().settings.themeId,
                        "uiMode" to nightMode,
                        "dark" to dark,
                        "site" to (siteColor?.let { Integer.toHexString(it) } ?: "-"),
                        "color" to Integer.toHexString(color),
                        "navMode" to if (isThreeButtonNav(activity)) "3btn" else "gesture"
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("ThemeUtils", "applySystemBarColors failed", e)
        }
    }

    /** 上次落盘的（页面, 栏色）（节流用：同页面同色不再写日志） */
    @Volatile
    private var lastProbedKey: Pair<String, Int>? = null

    /**
     * 该栏色是否为浅色（决定系统栏图标用深色还是浅色）。
     * 用 WCAG 相对亮度阈值 0.5：站点可给任意色，必须按实际色算而非主题。
     */
    private fun isLightColor(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        // 感知亮度（sRGB 近似，避免引入 ColorUtils 依赖）
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
        return luminance > 0.5
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
