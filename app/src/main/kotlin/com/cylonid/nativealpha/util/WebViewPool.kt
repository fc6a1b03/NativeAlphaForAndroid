package com.cylonid.nativealpha.util

import android.webkit.WebView
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * WebView 实例池（页面状态真正保留方案）。
 *
 * 原理：WebView 实例存活 = 页面/滚动位置/输入内容完整保留（saveState 只能存导航历史）。
 * Activity 销毁时 WebView 从父容器剥离存入池；重开同 webapp 时取回复用。
 *
 * 设计约束：
 * - 最多保留 3 个实例；超过时**异步销毁最旧**（单线程池，不影响主应用）
 * - 数据安全：存取在主线程（WebView 要求），销毁在后台线程
 * - 单例：全局唯一（跨 Activity 共享）
 */
object WebViewPool {

    /** 最大保留实例数（超过异步销毁最旧） */
    private const val MAX_INSTANCES = 3

    /** LRU 池：LinkedHashMap accessOrder=true（访问即移到尾部=最新） */
    private val pool = object : LinkedHashMap<Int, WebView>(MAX_INSTANCES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, WebView>?): Boolean = false
    }

    /** 后台销毁线程（单线程串行，不干扰主线程） */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "webview-pool-evict").apply { isDaemon = true }
    }

    /**
     * 存入实例（Activity onDestroy 时调用，WebView 已从父容器剥离）。
     * 超过上限时异步销毁最旧（不阻塞主线程）。
     */
    @Synchronized
    fun put(webappId: Int, wv: WebView) {
        // 同 ID 已有旧实例：先销毁（防泄漏）
        pool.remove(webappId)?.let { destroyAsync(it) }
        pool[webappId] = wv
        // 超限：异步销毁最旧
        if (pool.size > MAX_INSTANCES) {
            val eldestKey = pool.keys.firstOrNull()
            if (eldestKey != null) {
                val evicted = pool.remove(eldestKey)
                if (evicted != null) destroyAsync(evicted)
            }
        }
    }

    /** 取回实例（重开时调用）；null = 无缓存需新建 */
    @Synchronized
    fun get(webappId: Int): WebView? = pool.remove(webappId)

    /** 当前池大小（调试/测试用） */
    @Synchronized
    fun size(): Int = pool.size

    /** 异步销毁 WebView（后台线程，防主线程卡顿） */
    private fun destroyAsync(wv: WebView) {
        executor.execute {
            try {
                // 确保从父容器剥离（防 attach 状态 destroy 崩溃）
                if (wv.getParent() != null) {
                    (wv.getParent() as android.view.ViewGroup).removeView(wv)
                }
                wv.stopLoading()
                wv.removeAllViews()
                wv.destroy()
            } catch (ignored: Exception) {
                // 销毁失败静默（池淘汰尽力而为）
            }
        }
    }
}
