package com.cylonid.nativealpha.matrix

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.PermissionRequest
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

    /** 权限请求 launcher（结果授权/拒绝 pending 的 PermissionRequest） */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request != null) {
            val granted = grants.filterValues { it }.keys.toTypedArray()
            if (granted.isNotEmpty()) request.grant(granted) else request.deny()
        }
    }

    /** 进行中的权限请求（回调回调时机=launcher 返回） */
    private var pendingPermissionRequest: PermissionRequest? = null

    /** 矩阵格权限请求入口（相机/麦克风——AI 语音对话） */
    internal fun requestWebPermissions(resources: Array<String>, request: PermissionRequest) {
        pendingPermissionRequest = request
        permissionLauncher.launch(resources)
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
