package com.cylonid.nativealpha.util;

import android.app.Application;
import android.content.Context;
import android.webkit.WebView;

public class App extends Application {

    private static Context context;

    public void onCreate() {
        super.onCreate();

        App.context = getApplicationContext();

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
}
