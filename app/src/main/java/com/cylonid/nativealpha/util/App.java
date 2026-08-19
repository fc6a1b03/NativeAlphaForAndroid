package com.cylonid.nativealpha.util;

import android.app.Application;
import android.content.Context;

public class App extends Application {

    private static Context context;

    public void onCreate() {
        super.onCreate();

        App.context = getApplicationContext();

        // 进程启动即应用 UI 模式（themeId 持久化在 SharedPreferences，
        // 此时加载最可靠——早于任何 Activity 的 setTheme）
        ThemeUtils.applyUiMode();
    }

    public static Context getAppContext() {
        return App.context;
    }
}
