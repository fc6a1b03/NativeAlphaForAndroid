package com.cylonid.nativealpha.util;

import android.app.Application;
import android.content.Context;

public class App extends Application {

    private static Context context;

    public void onCreate() {
        super.onCreate();

        App.context = getApplicationContext();

        // 全局未捕获异常兜底：记录应用错误日志（KEY_APP_ERRORS）后优雅重启，不弹系统"已停止"
        installUncaughtExceptionHandler();

        // 进程启动即应用 UI 模式（themeId 持久化在 SharedPreferences，
        // 此时加载最可靠——早于任何 Activity 的 setTheme）
        ThemeUtils.applyUiMode();

        // WebView 预热已移除：后台线程 new WebView() 在部分 WebView 版本
        // （TrichromeWebViewGoogle 6432 等）会破坏内核状态，导致后续 inflate
        // WebView 崩溃（AwContents must be created if we are not posting）。
        // 收益 1-2s 冷启动提速 vs 实机崩溃风险——取稳定性，移除预热。
    }

    public static Context getAppContext() {
        return App.context;
    }

    /**
     * 全局未捕获异常兜底：写入应用错误日志（KEY_APP_ERRORS，DataStore）后重启。
     * 不弹系统"已停止"对话框，避免用户数据丢失；错误日志供 3.5 导出排查。
     */
    private void installUncaughtExceptionHandler() {
        try {
            final Thread.UncaughtExceptionHandler defaultHandler =
                    Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                try {
                    // 写应用错误日志（异步协程，尽力而为）
                    String stack = throwable != null
                            ? android.util.Log.getStackTraceString(throwable)
                            : "unknown";
                    com.cylonid.nativealpha.model.AppErrorEntry entry =
                            new com.cylonid.nativealpha.model.AppErrorEntry(
                                    System.currentTimeMillis(),
                                    com.cylonid.nativealpha.model.AppErrorEntry.LEVEL_CRASH,
                                    thread != null ? thread.getName() : "",
                                    throwable != null ? String.valueOf(throwable.getMessage()) : "",
                                    stack
                            );
                    // 防死锁：崩溃线程若是协程 IO 线程（DataStore 底层 DefaultDispatcher），
                    // runBlocking 会自锁（等自己释放锁）——此时跳过同步写，日志丢失可接受
                    String threadName = thread != null ? thread.getName() : "";
                    boolean isCoroutineIoThread = threadName.startsWith("DefaultDispatcher")
                            || threadName.startsWith("kotlinx.coroutines");
                    if (!isCoroutineIoThread) {
                        com.cylonid.nativealpha.model.AppErrorLogRepository.INSTANCE.appendSync(App.this, entry);
                    }
                } catch (Exception ignored) {
                    // 日志写入失败不阻塞重启流程
                }
                // 转交默认处理器（系统记录崩溃日志）
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            });
        } catch (Exception ignored) {
            // 兜底安装失败不影响启动
        }
    }
}
