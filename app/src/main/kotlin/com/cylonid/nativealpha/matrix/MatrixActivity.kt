package com.cylonid.nativealpha.matrix

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.lifecycleScope
import com.cylonid.nativealpha.util.FileChooserDelegate
import com.cylonid.nativealpha.util.SystemBars
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.util.AppMaterialTheme
import com.cylonid.nativealpha.util.ThemeUtils
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 多窗矩阵宿主（P4，规格 §4.3 全局约束）。
 *
 * 生命周期接线（D8/规格 §4.2）：
 * - onPause/onResume：全窗页面级暂停/恢复
 * - onStop/onStart：pauseTimers/resumeTimers（全局 API，整页后台语义）
 * - onDestroy：releaseAll 全量释放，进程内不驻留 WebView
 * - onTrimMemory：引擎分级响应（警示条/COMPLETE 强制降 2 窗）
 */
internal class MatrixActivity : ComponentActivity(), SystemBars.SelfManagedInsets {

    /** IME 避让挂载标记（onCreate 一次；SelfManagedInsets 下全局兜底跳过，须自挂） */
    private var imeGuardInstalled = false

    /** 文件选择统一委托（矩阵格 onShowFileChooser 与宿主同源） */
    val fileChooserDelegate = FileChooserDelegate(this)

    /** 权限请求 launcher（WebPermissionCoordinator 注入点：全授予才回调 true） */
    private var runtimePermissionCallback: ((granted: Boolean) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        runtimePermissionCallback?.invoke(grants.isNotEmpty() && grants.all { it.value })
        runtimePermissionCallback = null
    }

    internal fun requestRuntimePermissions(permissions: List<String>, onResult: (granted: Boolean) -> Unit) {
        runtimePermissionCallback = onResult
        permissionLauncher.launch(permissions.toTypedArray())
    }

    /**
     * IME 避让（View 级，与宿主 applyContentInsets 同机制）：
     * 消费量 = ime.bottom - navigationBars.bottom 的正数部分。
     * 量化依据（模拟器实测）：三键导航下系统 ime insets 已含堆叠的导航条
     * 高度（多报 100px），而全屏沉浸的矩阵内容本就铺满导航条区域——
     * 全额消费会双重扣除，键盘上方悬空大段留白。
     */
    private fun installImeGuard() {
        if (imeGuardInstalled) return
        imeGuardInstalled = true
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.updatePadding(bottom = maxOf(0, ime - nav))
            insets
        }
    }


    private lateinit var engine: MatrixEngine

    // ===== 格内视频全屏（onShowCustomView 挂载层，装饰在窗口最顶层） =====

    private var cellCustomView: View? = null
    private var cellCustomViewCallback: WebChromeClient.CustomViewCallback? = null
    private var cellOriginalOrientation = 0

    internal fun showCellCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (cellCustomView != null) {
            callback.onCustomViewHidden()
            return
        }
        cellCustomView = view
        cellOriginalOrientation = requestedOrientation
        cellCustomViewCallback = callback
        (window.decorView as FrameLayout).addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        // 全屏视频期强制沉浸（与满铺设计同语义；退出后 onResume 兜底回归）
        SystemBars.enterImmersive(this)
    }

    internal fun hideCellCustomView() {
        val view = cellCustomView ?: return
        (window.decorView as FrameLayout).removeView(view)
        cellCustomView = null
        cellCustomViewCallback?.onCustomViewHidden()
        cellCustomViewCallback = null
        requestedOrientation = cellOriginalOrientation
        SystemBars.enterImmersive(this)
    }

    private val snackbarHostState = SnackbarHostState()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题前置（规范 R）：applyUiMode 已在 App.onCreate 全局应用
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)
        ThemeUtils.applySystemBarColors(this)

        engine = MatrixEngine(this, applicationContext)
        engine.restoreSession()
        installImeGuard()
        // 满铺设计（用户定调矩阵撑满物理屏）：隐藏系统栏。此前从未实现——
        // MatrixScreen 注释宣称「Activity 侧已隐藏」实为缺失（三键/状态栏
        // 显示且遮挡格底内容）。onResume 重复调用兜底 transient 唤出后回归。
        SystemBars.enterImmersive(this)
        // QB 入口预检：进矩阵即预检预算（fail-open），不足整页劝退；
        // 恢复的占位格不占渲染内存，预检结果与闸门首窗判定一致
        engine.observeNotices(this)

        setContent {
            AppMaterialTheme {
                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                    MatrixScreen(
                        engine = engine,
                        onBack = { finish() },
                        // 状态栏/导航条可见（用户定调），App 内不留白：可用
                        // 空间=系统栏之间的全部区域，Scaffold inset 是唯一边界
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        engine.onPauseCells()
    }

    override fun onResume() {
        super.onResume()
        // 沉浸兜底：系统 transient 唤出/导航模式切换后回归时重新隐藏系统栏
        SystemBars.enterImmersive(this)
        engine.onResumeCells()
    }

    override fun onStop() {
        super.onStop()
        engine.stopCellTimers()
    }

    override fun onStart() {
        super.onStart()
        engine.resumeCellTimers()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        engine.onTrimMemory(level)
    }

    override fun onDestroy() {
        // 全屏视频装饰层残留清理（早于引擎释放，避免销毁后仍持 view）
        cellCustomViewCallback = null
        cellCustomView?.let { (window.decorView as FrameLayout).removeView(it) }
        cellCustomView = null
        // 全局定时器对称恢复（必须先于 releaseAll）：onStop 的 pauseTimers 是
        // 进程全局开关，矩阵退出后残留会让之后单独打开的站点 JS/加载全面
        // 停摆（真机实测：矩阵退出后新开站点无法加载+会话失效）。宿主仅在
        // onNewIntent 复用路径 resumeTimers，新建实例无兜底——矩阵必须自愈
        engine.resumeCellTimers()
        engine.releaseAll()
        super.onDestroy()
    }

    /** 一次性事件 → Snackbar 文案映射（引擎只发事件，UI 决定呈现） */
    private fun MatrixEngine.observeNotices(activity: ComponentActivity) {
        notices.onEach { notice ->
            val message = when (notice) {
                MatrixNotice.DEGRADED -> getString(R.string.matrix_notice_degraded)
                MatrixNotice.CRASH_BACKOFF -> getString(R.string.matrix_notice_backoff)
            }
            snackbarHostState.showSnackbar(message)
        }.launchIn(activity.lifecycleScope)
    }
}
