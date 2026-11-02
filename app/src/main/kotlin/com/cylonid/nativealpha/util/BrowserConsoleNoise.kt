package com.cylonid.nativealpha.util

/**
 * 浏览器控制台噪音判定（宿主/矩阵 ChromeClient 共用，R3 单一实现）：
 * Chromium 把三类页面侧代码坏味道以 ERROR 级灌进 console（2026-10-29 实机
 * 导出单日 22 条，全部来自 zcode.z.ai 自身），计入 statErrors/FeatureMetrics
 * 会把错误观测灌到虚高。它们不反映 WebView 壳的健康度，统计与诊断日志一并
 * 过滤（logcat 原样保留）：
 * - passive listener 里 preventDefault（站点代码问题，浏览器自动降级）
 * - ResizeObserver loop（浏览器良性提示，布局本身已收敛）
 * - aria-hidden 聚焦元素（无障碍警告，无功能影响）
 */
internal object BrowserConsoleNoise {

    fun isNoise(message: String?): Boolean {
        if (message == null) return false
        return message.startsWith("Unable to preventDefault inside passive event listener") ||
            message.startsWith("ResizeObserver loop") ||
            message.startsWith("Blocked aria-hidden on an element")
    }
}
