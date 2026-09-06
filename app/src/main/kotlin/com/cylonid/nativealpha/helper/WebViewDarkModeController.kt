
package com.cylonid.nativealpha.helper

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.cylonid.nativealpha.WebViewActivity
import com.cylonid.nativealpha.util.SystemBars
import com.cylonid.nativealpha.util.WebViewSetup

/**
 * 深色模式与系统栏控制器（重构刀 3，自 WebViewActivity 逐行迁移）。
 *
 * 职责：按站强制深色判定与 WebView 深色应用（判定与 WebView 侧收编
 * WebViewSetup，宿主/矩阵同源；本类保留宿主专属的 Activity localNightMode
 * 切换）、全屏沉浸开关、非全屏模式的内容避让 insets 监听。
 *
 * 设计约束：持有 Activity 实例引用（构造注入，非静态——防泄漏）。
 */
class WebViewDarkModeController(private val activity: WebViewActivity) {

    @SuppressLint("RequiresFeature")
    fun setDarkModeIfNeeded() {
        val webapp = activity.webapp
        val wv = activity.wv
        if (webapp == null || wv == null) {
            return
        }
        // 强制深色判定与 WebView 侧应用收编 WebViewSetup（宿主/矩阵同源——
        // 矩阵此前完全没接，同一站点宿主暗/矩阵亮）；本类保留宿主专属的
        // Activity 主题切换（localNightMode）
        val needsForcedDarkMode = WebViewSetup.needsForcedDarkMode(webapp)

        if (needsForcedDarkMode) {
            wv.setBackgroundColor(Color.BLACK)
            activity.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        } else {
            activity.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            // 加载页背景跟随应用主题（不固定白底）：深色主题下避免加载白屏闪瞎
            wv.setBackgroundColor(WebViewSetup.resolveThemeBackground(activity))
        }

        // 强制深色统一走 algorithmic darkening（webkit 新 API）：
        // setForceDark 系在 targetSdk>=33 的应用上被 WebView 整体忽略
        // （官方迁移口径，与设备 API 级别无关），targetSdk=37 下旧 API
        // 分支全是无效调用，故不再按 API 33 分路
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, needsForcedDarkMode)
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
