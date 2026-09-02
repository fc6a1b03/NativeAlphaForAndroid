package com.cylonid.nativealpha.util

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.cylonid.nativealpha.model.AppErrorEntry
import com.cylonid.nativealpha.util.ErrorReporter
import com.cylonid.nativealpha.model.AppErrorLogRepository
import com.cylonid.nativealpha.model.DataManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        context = applicationContext

        // 网页事件运行时（P5）：规则库载入 + 通知 Channel 幂等创建 + 动作分发装配
        com.cylonid.nativealpha.webevent.WebeventRuntime.init(context)

        // 全局未捕获异常兜底：记录应用错误日志（KEY_APP_ERRORS）后优雅重启，不弹系统"已停止"
        installUncaughtExceptionHandler()

        // 系统控件全局兜底（C-系统栏）：targetSdk 35+ 强制 edge-to-edge 后，
        // 未处理 insets 的页面内容会被状态栏/导航栏/挖孔遮挡（扫码页关闭按钮
        // 被状态栏吃掉一半的教训）——所有页面自动规避；自管页面实现
        // SystemBars.SelfManagedInsets 跳过（Scaffold/全屏页）
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                SystemBars.installInsetGuard(activity)
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        // 进程启动即应用 UI 模式（themeId 持久化在 SharedPreferences，
        // 此时加载最可靠——早于任何 Activity 的 setTheme）
        ThemeUtils.applyUiMode()

        // 启动预热（异步/分段加载）：后台线程提前触发 DataManager 的
        // SP 磁盘 IO + Gson 解析——主线程首次 loadAppData() 时数据已就绪
        // （dataLoaded 短路），冷启动主线程少等一段磁盘。
        // 线程安全：SP 并发读安全；websites 引用在后台解析完成后才整体
        // 发布，主线程随后的 dataLoaded=false 路径直接命中，不读半成品。
        Thread {
            try {
                DataManager.getInstance().loadAppData()
            } catch (ignored: Exception) {
                // 预热失败不影响主流程（主线程首次调用会重试）
            }
        }.start()

        // WebView 预热已移除：后台线程 new WebView() 在部分 WebView 版本
        // （TrichromeWebViewGoogle 6432 等）会破坏内核状态，导致后续 inflate
        // WebView 崩溃（AwContents must be created if we are not posting）。
        // 收益 1-2s 冷启动提速 vs 实机崩溃风险——取稳定性，移除预热。
    }

    /**
     * 内存压力记录（错误收集定位：内存异常/占用过高属应用自身问题，收录）。
     *
     * 分级去重：RUNNING_LOW 首次记 WARNING；升级到 COMPLETE/CRITICAL 再记
     * （同级别系统会连续回调，不去重会刷屏）。附 Java 堆快照辅助定位。
     *
     * UI_HIDDEN（20）不算压力：那是「退后台 UI 不可见」的正常生命周期回调，
     * 每次退后台都会触发（真机日志 15 条 LEVEL_20、heap 仅 6-21MB 的教训）；
     * 且它会污染 [loggedTrimLevel] 单调门槛——20 记过后，此后真正的
     * RUNNING_LOW(10) 因 10<20 永远记不上。判定收口到纯函数可单测。
     */
    private var loggedTrimLevel = 0

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!shouldLogMemoryPressure(level, loggedTrimLevel)) return
        loggedTrimLevel = level
        val runtime = Runtime.getRuntime()
        val heapMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val levelName = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
            else -> "LEVEL_$level"
        }
        ErrorReporter.report(
            this, "Memory", "memory pressure: $levelName (java heap used ${heapMb}MB)",
            level = AppErrorEntry.LEVEL_WARNING
        )
    }

    companion object {
        /**
         * 内存压力记录判定（纯函数，可单测）：剔除 UI_HIDDEN 伪压力 +
         * 低于 RUNNING_LOW 的回调；同级别及以下已被记录过则去重。
         */
        internal fun shouldLogMemoryPressure(level: Int, lastLoggedLevel: Int): Boolean {
            if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return false
            if (level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return false
            return level > lastLoggedLevel
        }

        /**
         * Application 上下文。
         * 注：持有的是 Application Context（与 Application 生命周期一致），非 Activity/Service Context，
         * 不存在因 Activity 泄漏导致的内存泄漏；lateinit 语义等价原 Java 静态字段。
         */
        @SuppressLint("StaticFieldLeak")
        private lateinit var context: Context

        @JvmStatic
        fun getAppContext(): Context = context
    }

    /**
     * 全局未捕获异常兜底：写入应用错误日志（KEY_APP_ERRORS，DataStore）后重启。
     * 不弹系统"已停止"对话框，避免用户数据丢失；错误日志供导出排查。
     */
    private fun installUncaughtExceptionHandler() {
        try {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    // 写应用错误日志（同步兜底，尽力而为）。
                    // 错误收集定位（用户定调）：崩溃/OOM/内存异常是错误日志
                    // 的核心收录对象——OOM 类自动标注便于与普通崩溃区分
                    val stack = Log.getStackTraceString(throwable)
                    val isOom = throwable is OutOfMemoryError
                    val crashTag = if (isOom) "OOM/" + thread.name else thread.name
                    val crashMessage =
                        (if (isOom) "[OutOfMemory] " else "") + throwable.message.toString()
                    val entry = AppErrorEntry(
                        System.currentTimeMillis(),
                        AppErrorEntry.LEVEL_CRASH,
                        crashTag,
                        crashMessage,
                        stack
                    )
                    // 防死锁：崩溃线程若是协程 IO 线程（DataStore 底层 DefaultDispatcher），
                    // runBlocking 会自锁（等自己释放锁）——此时跳过同步写，日志丢失可接受
                    val threadName = thread.name
                    val isCoroutineIoThread = threadName.startsWith("DefaultDispatcher") ||
                        threadName.startsWith("kotlinx.coroutines")
                    if (!isCoroutineIoThread) {
                        AppErrorLogRepository.appendSync(this@App, entry)
                    }
                } catch (ignored: Exception) {
                    // 日志写入失败不阻塞重启流程
                }
                // 转交默认处理器（系统记录崩溃日志）
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable)
                } else {
                    Process.killProcess(Process.myPid())
                }
            }
        } catch (ignored: Exception) {
            // 兜底安装失败不影响启动
        }
    }
}
