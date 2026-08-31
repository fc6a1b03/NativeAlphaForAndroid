package com.cylonid.nativealpha.matrix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.lifecycleScope
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
internal class MatrixActivity : ComponentActivity() {

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
        // QB 入口预检：进矩阵即预检预算（fail-open），不足整页劝退；
        // 恢复的占位格不占渲染内存，预检结果与闸门首窗判定一致
        engine.observeNotices(this)

        setContent {
            AppMaterialTheme {
                Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                    MatrixScreen(
                        engine = engine,
                        onBack = { finish() },
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
