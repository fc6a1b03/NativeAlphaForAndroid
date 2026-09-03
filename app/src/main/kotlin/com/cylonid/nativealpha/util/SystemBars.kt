package com.cylonid.nativealpha.util

import android.app.Activity
import android.os.Build
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 系统控件全局能力（C-系统栏：任何页面自动规避状态栏/导航栏/摄像头挖孔）。
 *
 * 背景：targetSdk 35+ 强制 edge-to-edge，setDecorFitsSystemWindows 失效，
 * 未处理 insets 的页面内容会被系统控件遮挡（扫码页关闭按钮被状态栏吃掉一半
 * 的教训）。历史上只有 WebViewActivity 一家自建了全屏+避让——能力收编至此，
 * 任何页面三行接入，新页面默认受保护。
 *
 * 两种模式：
 * - [installInsetGuard]（默认，全局自动生效）：内容自动规避系统栏+挖孔，
 *   App.onCreate 的 LifecycleCallbacks 对所有未声明自管的 Activity 注入
 * - [enterImmersive]/[exitImmersive]（显式，按需调用）：真全屏（隐藏状态栏/
 *   导航栏，内容铺满含挖孔区），下滑临时唤出
 *
 * 自管页面（Compose Scaffold 自消费 insets 的页、WebViewActivity 每站
 * 全屏开关页、矩阵满铺页）实现 [SelfManagedInsets]，全局兜底自动跳过，
 * 避免双重 inset 破坏布局。
 */
object SystemBars {

    /**
     * 页面自管 insets 标记：实现此接口的 Activity 跳过全局兜底
     * （Compose Scaffold/自行做全屏沉浸的页面）。
     */
    interface SelfManagedInsets

    /**
     * 全局兜底（App.onCreate LifecycleCallbacks 对每个 Activity 调用）：
     * 内容区自动避开系统栏+挖孔（padding 注入 android.R.id.content 并消费
     * insets——子 View 不再重复处理，根治遮挡）。
     * [SelfManagedInsets] 页面跳过（Scaffold/全屏页自管）。
     * 注意：兜底只覆盖系统栏+挖孔，不含 IME——需要键盘避让的页面
     * （带输入框）应实现 SelfManagedInsets 自行处理。
     */
    fun installInsetGuard(activity: Activity) {
        if (activity is SelfManagedInsets) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    /**
     * 真全屏（沉浸）：隐藏状态栏+导航栏，内容铺满含挖孔区；下滑临时唤出
     * （半透明悬浮，不挤压布局）。调用方通常同时实现 [SelfManagedInsets]。
     */
    @Suppress("DEPRECATION") // setDecorFitsSystemWindows 仅 API<35 生效，分支内引用静态报警
    fun enterImmersive(activity: Activity) {
        // minSdk=31，WindowInsetsController API 始终可用；
        // setDecorFitsSystemWindows 仅 API<35 有意义（35+ 系统强制 edge-to-edge，
        // 该调用废弃且无效，内容避让由 installInsetGuard/applyContentInsets 承担）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            activity.window.setDecorFitsSystemWindows(false)
        }
        val controller = activity.window.insetsController ?: return
        controller.hide(
            android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.navigationBars()
        )
        controller.systemBarsBehavior =
            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** 退出沉浸：对称恢复系统栏与内容贴合（与 [enterImmersive] 配对） */
    @Suppress("DEPRECATION") // 同 enterImmersive：API<35 分支内合法引用
    fun exitImmersive(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            activity.window.setDecorFitsSystemWindows(true)
        }
        activity.window.insetsController?.show(
            android.view.WindowInsets.Type.statusBars() or
                android.view.WindowInsets.Type.navigationBars()
        )
    }
}
