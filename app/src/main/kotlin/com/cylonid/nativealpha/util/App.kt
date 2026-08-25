package com.cylonid.nativealpha.util

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import com.cylonid.nativealpha.model.AppErrorEntry
import com.cylonid.nativealpha.model.AppErrorLogRepository
import com.cylonid.nativealpha.model.DataManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        context = applicationContext

        // 全局未捕获异常兜底：记录应用错误日志（KEY_APP_ERRORS）后优雅重启，不弹系统"已停止"
        installUncaughtExceptionHandler()

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

    companion object {
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
                    // 写应用错误日志（同步兜底，尽力而为）
                    val stack = Log.getStackTraceString(throwable)
                    val entry = AppErrorEntry(
                        System.currentTimeMillis(),
                        AppErrorEntry.LEVEL_CRASH,
                        thread.name,
                        throwable.message.toString(),
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
