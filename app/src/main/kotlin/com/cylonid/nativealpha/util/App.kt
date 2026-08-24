package com.cylonid.nativealpha.util

import android.app.Application
import android.content.Context
import com.cylonid.nativealpha.model.AppErrorEntry
import com.cylonid.nativealpha.model.AppErrorLogRepository

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        context = applicationContext

        // 全局未捕获异常兜底：记录应用错误日志（KEY_APP_ERRORS）后优雅重启，不弹系统"已停止"
        installUncaughtExceptionHandler()

        // 进程启动即应用 UI 模式（themeId 持久化在 SharedPreferences，
        // 此时加载最可靠——早于任何 Activity 的 setTheme）
        ThemeUtils.applyUiMode()

        // WebView 预热已移除：后台线程 new WebView() 在部分 WebView 版本
        // （TrichromeWebViewGoogle 6432 等）会破坏内核状态，导致后续 inflate
        // WebView 崩溃（AwContents must be created if we are not posting）。
        // 收益 1-2s 冷启动提速 vs 实机崩溃风险——取稳定性，移除预热。
    }

    companion object {
        /** Application 上下文（onCreate 赋值——早于任何调用点，lateinit 语义等价原 Java 静态字段） */
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
                    // 写应用错误日志（同步兜底，尽力而为）
                    val stack = throwable?.let { android.util.Log.getStackTraceString(it) } ?: "unknown"
                    val entry = AppErrorEntry(
                        System.currentTimeMillis(),
                        AppErrorEntry.LEVEL_CRASH,
                        thread?.name ?: "",
                        if (throwable != null) throwable.message.toString() else "",
                        stack
                    )
                    // 防死锁：崩溃线程若是协程 IO 线程（DataStore 底层 DefaultDispatcher），
                    // runBlocking 会自锁（等自己释放锁）——此时跳过同步写，日志丢失可接受
                    val threadName = thread?.name ?: ""
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
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
        } catch (ignored: Exception) {
            // 兜底安装失败不影响启动
        }
    }
}
