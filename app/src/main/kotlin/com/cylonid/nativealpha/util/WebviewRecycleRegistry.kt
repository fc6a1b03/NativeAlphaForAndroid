package com.cylonid.nativealpha.util

import android.content.ComponentCallbacks2
import com.cylonid.nativealpha.WebViewActivity
import java.lang.ref.WeakReference

/**
 * 后台 WebView 分级回收登记表（核心理念：用时舒适、不用时透明安静）。
 *
 * 机制（谷歌官方范式，ComponentCallbacks2 分级）：
 * - WebViewActivity onStop 进后台 → 登记 LRU（弱引用，不阻 GC）
 * - Application.onTrimMemory 分级驱动 → 按策略回收最久未用的后台页
 *   （RUNNING_CRITICAL=1 个；BACKGROUND=一半；MODERATE=2/3；COMPLETE=全部）
 * - 回收=销毁 WebView 实例、保留 Activity 骨架（多任务卡片不消失）；
 *   用户切回时 Activity 按记录的站点自动重载（AI 会话数据在服务端，无损）
 *
 * 前台/可见页永不进登记表（onStart 即注销），分屏可见页不受影响。
 */
object WebviewRecycleRegistry {

    private class Entry(val ref: WeakReference<WebViewActivity>) {
        var lastActiveAt = System.currentTimeMillis()
    }

    private val entries = ArrayList<Entry>()

    /** 进后台登记（onStop）；已在表中则刷新活跃时间 */
    @Synchronized
    fun register(activity: WebViewActivity) {
        unregister(activity)
        entries.add(Entry(WeakReference(activity)))
    }

    /** 回前台注销（onStart）；Activity 销毁时也调用（防弱引用悬挂） */
    @Synchronized
    fun unregister(activity: WebViewActivity) {
        entries.removeAll { it.ref.get() == activity }
    }

    /**
     * 分级回收策略（纯函数可单测）：返回应回收的后台页数量。
     * 级别语义见 ComponentCallbacks2——越深的后台 LRU 级别越激进释放
     * （进程越濒死，官方建议越深越主动）。
     */
    @Suppress("DEPRECATION") // TRIM_* 常量 API 35 标废（系统自 34 起不保证后台级别通知）但无替代常量；onTrimMemory 仍为推荐机制，本处消费实际到达的信号，COMPLETE 语义风险已在 v2.2.17 适配
    fun recycleCount(trimLevel: Int, backgroundCount: Int): Int {
        if (backgroundCount <= 0) return 0
        return when (trimLevel) {
            // 前台可见但内存告急：只放弃最旧 1 个（不可见，无感）
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 1
            // 后台 LRU：越深越激进（40=一半、60=三分之二、80=全部）
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> backgroundCount / 2
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> backgroundCount * 2 / 3
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> backgroundCount
            else -> 0
        }.coerceAtMost(backgroundCount)
    }

    /**
     * 按级别回收（Application.onTrimMemory 主线程调用）。
     * 返回实际回收数；活跃时间最久的优先。
     */
    @Synchronized
    fun recycleOldest(trimLevel: Int): Int {
        val count = recycleCount(trimLevel, liveCount())
        if (count == 0) return 0
        // 清理已被 GC 的悬挂条目后按活跃时间升序（最旧优先）
        entries.removeAll { it.ref.get() == null }
        val targets = entries.sortedBy { it.lastActiveAt }.take(count)
        var done = 0
        targets.forEach { entry ->
            val activity = entry.ref.get()
            if (activity != null && !activity.isFinishing) {
                activity.recycleWebView()
                done++
            }
            entries.remove(entry)
        }
        return done
    }

    @Synchronized
    private fun liveCount(): Int {
        entries.removeAll { it.ref.get() == null }
        return entries.size
    }

}
