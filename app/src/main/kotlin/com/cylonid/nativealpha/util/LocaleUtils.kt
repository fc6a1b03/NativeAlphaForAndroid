package com.cylonid.nativealpha.util

import java.util.*

object LocaleUtils {

    /**
     * 应用语言代码（文件名后缀用：zh / en / de）——错误页等静态资源按此选择。
     */
    @JvmStatic
    val fileEnding: String
        get() = when (Locale.getDefault().language) {
            "de" -> "de"
            "zh" -> "zh"
            else -> "en"
        }

    /**
     * WebView 请求头 Accept-Language（JSoup 抓取器与 WebView 加载共用）。
     *
     * 根因修复（2.1.18）：此前抓取器未带语言头，Cloudflare 挑战页默认英文导致
     * "Just a moment..." 被当成标题回填。统一用系统语言生成标准 Accept-Language：
     * 例如 zh-CN,zh;q=0.9（中文）；en-US,en;q=0.9（英文）；de-DE,de;q=0.9（德文）。
     */
    @JvmStatic
    val acceptLanguage: String
        get() = when (Locale.getDefault().language) {
            "zh" -> "zh-CN,zh;q=0.9"
            "de" -> "de-DE,de;q=0.9"
            else -> "en-US,en;q=0.9"
        }
}
