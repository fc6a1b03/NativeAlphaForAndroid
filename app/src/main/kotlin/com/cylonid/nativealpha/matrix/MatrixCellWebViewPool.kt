package com.cylonid.nativealpha.matrix

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import kotlinx.coroutines.Job

/**
 * 矩阵格 WebView 宿主池（引擎的 WebView 实例所有权承载）：持有实例数组与
 * 错峰加载任务数组，统一挂载/换位/释放/整页暂停恢复。
 *
 * 不做对象池复用（总纲「不做预热池」）：重用一律新实例，释放四步不可省。
 */
internal class MatrixCellWebViewPool(private val appContext: Context) {

    /** 宿主窗格持有的 WebView（下标对齐 cells；占位为 null） */
    private val cellWebViews = arrayOfNulls<WebView>(MatrixSessionState.MAX_WINDOW_COUNT)

    /** 错峰串行加载任务（格释放/窗口收缩时取消，防竞态） */
    private val staggeredLoadJobs = arrayOfNulls<Job>(MatrixSessionState.MAX_WINDOW_COUNT)

    /**
     * UI 挂载点（AndroidView factory）：返回该格 WebView 实例；跨槽位
     * 复用（放大/网格切换布局重建）先脱离旧父容器，同实例不重载。
     * 兜底空 View：仅命中状态竞态的瞬时空窗（正常路径 ACTIVE 必有实例）。
     */
    fun attach(cellIndex: Int): View {
        val webview = cellWebViews.getOrNull(cellIndex)
            ?: return View(appContext)
        (webview.parent as? ViewGroup)?.removeView(webview)
        return webview
    }

    /** 拖拽换位：实例与挂起任务随语义位置同步交换（WebView 不销毁不重载） */
    fun swapSlots(a: Int, b: Int) {
        val tmpWv = cellWebViews[a]
        cellWebViews[a] = cellWebViews[b]
        cellWebViews[b] = tmpWv

        val tmpJob = staggeredLoadJobs[a]
        staggeredLoadJobs[a] = staggeredLoadJobs[b]
        staggeredLoadJobs[b] = tmpJob
    }

    /** 登记错峰加载任务（loadCell 发起时；释放路径统一取消） */
    fun setStaggeredJob(cellIndex: Int, job: Job?) {
        staggeredLoadJobs.getOrNull(cellIndex)?.cancel()
        staggeredLoadJobs[cellIndex] = job
    }

    fun webViewAt(cellIndex: Int): WebView? = cellWebViews.getOrNull(cellIndex)

    /** 错峰加载体创建实例后登记到槽位（挂载仍走 [attach] 脱离旧父容器） */
    fun install(cellIndex: Int, webview: WebView) {
        cellWebViews[cellIndex] = webview
    }

    /**
     * 释放四步（确定性释放，触发点：close/减窗/降级/崩溃清理/onDestroy）：
     * cancel 挂起加载 → stopLoading → 层级移除 → onPause → destroy。
     * 断线监督者归引擎管（stopCellReconnect），不在池内。
     */
    fun releaseAt(cellIndex: Int) {
        staggeredLoadJobs.getOrNull(cellIndex)?.cancel()
        staggeredLoadJobs[cellIndex] = null
        val webview = cellWebViews.getOrNull(cellIndex) ?: return
        cellWebViews[cellIndex] = null
        try {
            webview.stopLoading()
            (webview.parent as? ViewGroup)?.removeView(webview)
            webview.onPause()
            webview.destroy()
        } catch (ignored: Exception) {
            // 销毁竞态（层级/内核态异常）：释放纪律优先，不阻断其余格
        }
    }

    /** onDestroy：全量释放，进程内不驻留 WebView（D8） */
    fun releaseAll() {
        staggeredLoadJobs.forEach { it?.cancel() }
        cellWebViews.indices.forEach { releaseAt(it) }
    }

    /** onPause：全窗 onPause()（页面级暂停） */
    fun pauseCells() {
        cellWebViews.forEach { it?.onPause() }
    }

    /** onResume：全窗对称恢复 */
    fun resumeCells() {
        cellWebViews.forEach { it?.onResume() }
    }

    /** onStop：pauseTimers 全局暂停（矩阵整页后台语义一致） */
    fun stopTimers() {
        cellWebViews.forEach { it?.pauseTimers() }
    }

    /** onStart：全局对称恢复 */
    fun resumeTimers() {
        cellWebViews.forEach { it?.resumeTimers() }
    }
}
