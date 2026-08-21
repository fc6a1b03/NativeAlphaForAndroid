package com.cylonid.nativealpha.util

import android.os.Bundle
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 页面状态缓存（LRU，最多保留 3 个 WebApp 的页面状态）。
 *
 * 用途：WebView 切换/关闭时保存页面状态（saveState Bundle），
 * 重新打开时恢复（restoreState）——不刷新页面、保留滚动位置/输入内容。
 *
 * 设计约束：
 * - 最多 3 个：超过时**异步删除最旧**（单线程池，不影响主应用）
 * - 数据安全：保存/恢复在主线程（WebView API 要求），删除在后台线程
 * - 单例：全局唯一（跨 Activity 共享状态缓存）
 */
object PageStateCache {

    /** 最大保留数（超过异步删最旧） */
    private const val MAX_ENTRIES = 3

    /** LRU 缓存：LinkedHashMap accessOrder=true（访问即移到尾部=最新） */
    private val cache = object : LinkedHashMap<Int, Bundle>(MAX_ENTRIES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bundle>?): Boolean {
            // 仅标记，不在此删除（删除要异步，避免阻塞主线程）
            return false
        }
    }

    /** 后台删除线程（单线程串行，不干扰主线程） */
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "page-state-evict").apply { isDaemon = true }
    }

    /**
     * 保存页面状态（主线程调用；WebView.saveState 要求主线程）。
     * 超过上限时异步删除最旧（不阻塞主线程）。
     */
    @Synchronized
    fun put(webappId: Int, state: Bundle) {
        cache[webappId] = state
        // 超限：异步删除最旧（保证主线程零开销）
        if (cache.size > MAX_ENTRIES) {
            val eldestKey = cache.keys.firstOrNull()
            if (eldestKey != null) {
                executor.execute {
                    remove(eldestKey)
                }
            }
        }
    }

    /**
     * 获取并刷新 LRU 位置（命中即最新）。
     * 返回 null = 无缓存（需全新加载）。
     */
    @Synchronized
    fun get(webappId: Int): Bundle? = cache[webappId]

    /** 显式删除（清空统计/用户主动清除时） */
    @Synchronized
    fun remove(webappId: Int) {
        cache.remove(webappId)
    }

    /** 当前缓存数量（调试/测试用） */
    @Synchronized
    fun size(): Int = cache.size
}
