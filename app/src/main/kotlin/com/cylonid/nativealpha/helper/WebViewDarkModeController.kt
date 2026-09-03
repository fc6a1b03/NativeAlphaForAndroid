
package com.cylonid.nativealpha.helper

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.cylonid.nativealpha.WebViewActivity
import com.cylonid.nativealpha.util.DateUtils
import com.cylonid.nativealpha.util.SystemBars
import java.util.Calendar

/**
 * 深色模式与系统栏控制器（重构刀 3，自 WebViewActivity 逐行迁移，零语义变更）。
 *
 * 职责：按站强制深色判定与 WebView 深色应用（统一 algorithmic darkening——
 * 旧 setForceDark 系在 targetSdk>=33 被系统忽略，已清偿）、
 * 全屏沉浸开关、非全屏模式的内容避让 insets 监听。
 *
 * 设计约束：持有 Activity 实例引用（构造注入，非静态——防泄漏）；
 * 方法体与原实现逐行对应，行为差异零容忍。
 */
class WebViewDarkModeController(private val activity: WebViewActivity) {

    /** WebView 特性探测缓存：内核能力进程生命周期内不变，避免每次打开重复探测 */
    private val featAlgorithmicDarkening: Boolean by lazy {
        WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
    }

    @SuppressLint("RequiresFeature")
    fun setDarkModeIfNeeded() {
        val webapp = activity.webapp
        val wv = activity.wv
        if (webapp == null || wv == null) {
            return
        }
        val needsForcedDarkMode = webapp.isUseTimespanDarkMode &&
            DateUtils.isInInterval(
                DateUtils.convertStringToCalendar(webapp.timespanDarkModeBegin)!!,
                Calendar.getInstance(),
                DateUtils.convertStringToCalendar(webapp.timespanDarkModeEnd)!!
            )
            || (!webapp.isUseTimespanDarkMode && webapp.isForceDarkMode)

        // 强制深色统一走 algorithmic darkening（webkit 新 API）：
        // setForceDark 系在 targetSdk>=33 的应用上被 WebView 整体忽略
        // （官方迁移口径，与设备 API 级别无关），targetSdk=37 下旧 API
        // 分支全是无效调用，故不再按 API 33 分路
        val isAlgorithmicDarkeningSupported = featAlgorithmicDarkening

        if (needsForcedDarkMode) {
            wv.setBackgroundColor(Color.BLACK)
            activity.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES

            if (isAlgorithmicDarkeningSupported) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, true)
            }
        } else {
            activity.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            // 加载页背景跟随应用主题（不固定白底）：深色主题下避免加载白屏闪瞎
            // 读当前主题 colorBackground（浅色 #FBF8FF / 深色 #131318）
            var themeBg = Color.WHITE
            try {
                val tv = TypedValue()
                if (activity.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                    themeBg = tv.data
                }
            } catch (ignored: Exception) {
            }
            wv.setBackgroundColor(themeBg)

            if (isAlgorithmicDarkeningSupported) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, false)
            }
        }
    }

    // ===== 系统栏开关 =====

    fun hideSystemBars() {
        // 全屏能力收编 SystemBars（C-系统栏）：行为与原私有实现等价
        SystemBars.enterImmersive(activity)
    }

    fun showSystemBars() {
        if (activity.webapp!!.isShowFullscreen) return
        // 全屏能力收编 SystemBars（C-系统栏）：行为与原私有实现等价
        SystemBars.exitImmersive(activity)
    }

    /**
     * 异形屏自适应（targetSdk 35+ 强制 edge-to-edge，setDecorFitsSystemWindows 已失效）：
     * 非全屏模式 WebView 内容避开系统栏（顶部状态栏/挖孔、底部导航栏/手势条），
     * 全屏沉浸模式保持铺满（用户显式选择）。
     * insets 挂根布局而非 WebView：WebView 的 insets 分发可能被父容器消费，
     * 且三键导航/手势条切换时根布局 insets 更可靠。
     */
    fun applyContentInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            // 键盘弹出时避开（防御：部分输入法/机型 adjustResize 不生效）
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            v.setPadding(0, bars.top, 0, bottom)
            windowInsets
        }
    }
}
