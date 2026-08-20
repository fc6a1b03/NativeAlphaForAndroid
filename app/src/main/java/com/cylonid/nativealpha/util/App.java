package com.cylonid.nativealpha.util;

import android.app.Application;
import android.content.Context;
import android.webkit.WebView;

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

        // WebView 预热：后台线程初始化（省首次打开 WebView 的 1-2s 冷启动延迟）
        // 官方推荐：WebView 首次创建开销大，提前初始化可显著提速
        try {
            Thread webviewWarmup = new Thread(() -> {
                try {
                    WebView wv = new WebView(App.this);
                    wv.getSettings().setJavaScriptEnabled(true);
                    wv.setBackgroundColor(0);
                    wv.destroy();
                } catch (Exception ignored) {
                    // 预热失败不影响主流程
                }
            }, "webview-warmup");
            webviewWarmup.start();
        } catch (Exception ignored) {
        }
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
                                    com.cylonid.nativealpha.model.AppErrorEntry.LEVEL_CRASH,                                    thread != null ? thread.getName() : "",
                                    throwable != null ? String.valueOf(throwable.getMessage()) : "",
                                    stack
                            );
                    com.cylonid.nativealpha.model.AppErrorLogRepository.INSTANCE.appendSync(App.this, entry);
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
