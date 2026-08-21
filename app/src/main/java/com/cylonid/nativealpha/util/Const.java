package com.cylonid.nativealpha.util;

public class Const {
    public static final String DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0";

    public static final String INTENT_WEBAPPID = "webappID";
    public static final String INTENT_TAB_INDEX = "tabIndex";
    public static final String INTENT_BACKUP_RESTORED = "backup_restored";
    public static final String INTENT_WEBAPP_CHANGED = "webapp_changed";
    public static final String INTENT_REFRESH_NEW_THEME = "theme_changed";

    public static final int NO_CONTAINER = -1;

    public static final int PERMISSION_RC_LOCATION = 123;
    public static final int PERMISSION_RC_STORAGE = 132;
    public static final int PERMISSION_CAMERA = 100;
    public static final int PERMISSION_AUDIO = 101;

    public static final int CODE_OPEN_FILE = 512;
    public static final int CODE_WRITE_FILE = 4096;

    public static final int FAVICON_MIN_WIDTH = 96;

    // ===== 应用错误日志 =====
    /** 导出应用错误日志天数（仅近 3 天） */
    public static final int APP_ERROR_DAYS = 3;
    /** 应用错误日志/页面错误上限（条，超出丢最旧） */
    public static final int ERROR_LOG_LIMIT = 200;

    // ===== 组合快捷键 =====
    /** 每 WebApp 最大快捷键数（防冗余） */
    public static final int MAX_KEY_SHORTCUTS = 5;

    // ===== 统计 =====
    /** 白屏检测超时（20s 进度无推进判定白屏） */
    public static final int BLANK_SCREEN_TIMEOUT_MS = 20_000;

    // ===== 错误页 =====
    /** 错误页固定字体缩放（130：当前设备实测最舒适，不继承用户页面缩放） */
    public static final int ERROR_PAGE_TEXT_ZOOM = 130;
}



