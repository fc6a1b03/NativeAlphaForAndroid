package com.cylonid.nativealpha.util

/**
 * 全局常量。
 * Java 调用方以 Const.XXX 静态字段访问（const val 编译为 public static final，互操作无损）。
 */
object Const {
    const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"

    const val INTENT_WEBAPPID = "webappID"
    const val INTENT_TAB_INDEX = "tabIndex"
    const val INTENT_BACKUP_RESTORED = "backup_restored"
    const val INTENT_WEBAPP_CHANGED = "webapp_changed"
    const val INTENT_REFRESH_NEW_THEME = "theme_changed"
    /** 扫码临时浏览：携带原始 URL 直接进页面（不注册站点，ID 恒为负） */
    const val INTENT_RAW_URL = "raw_url"


    // ===== 应用错误日志 =====
    /** 应用错误日志保留窗口（天）：超龄清理 + 导出/崩溃提示过滤统一口径 */
    const val APP_ERROR_DAYS = 3
    /** 应用错误日志/页面错误上限（条，超出丢最旧） */
    const val ERROR_LOG_LIMIT = 200

    // ===== 组合快捷键 =====
    /** 每 WebApp 最大快捷键数（防冗余） */
    const val MAX_KEY_SHORTCUTS = 5

    // ===== 统计 =====
    /** 白屏检测超时（20s 进度无推进判定白屏） */
    const val BLANK_SCREEN_TIMEOUT_MS = 20_000

    // ===== 关于 =====
    /** 项目源码仓库主页（应用内「关于」入口；更新检查仓库身份见 UpdateChecker.REPO） */
    const val GITHUB_REPO_URL = "https://github.com/fc6a1b03/NativeAlphaForAndroid"
}
