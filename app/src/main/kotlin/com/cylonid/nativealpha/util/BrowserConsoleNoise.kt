package com.cylonid.nativealpha.util

/**
 * 浏览器控制台噪音判定（宿主/矩阵 ChromeClient 共用，R3 单一实现）：
 * Chromium 把三类页面侧代码坏味道以 ERROR 级灌进 console（2026-10-29 实机
 * 导出单日 22 条，全部来自 zcode.z.ai 自身），计入 statErrors/FeatureMetrics
 * 会把错误观测灌到虚高。它们不反映 WebView 壳的健康度，统计与诊断日志一并
 * 过滤（logcat 原样保留）：
 * - passive listener 里 preventDefault（站点代码问题，浏览器自动降级）
 * - ResizeObserver loop（浏览器良性提示，布局本身已收敛）
 * - aria-hidden 聚焦元素（无障碍警告，无功能影响；Chromium 新旧两种措辞）
 * - CSP 拦截 data: 请求（Cloudflare Turnstile 等，站点自身策略正常执行）
 * - 验证码隐形样式探针（Turnstile console.error 格式化垃圾输出）
 * - "[object X]" 异常字符串化残渣（无信息量，判读价值为零）
 */
internal object BrowserConsoleNoise {

    fun isNoise(message: String?): Boolean {
        if (message == null) return false
        return message.startsWith("Unable to preventDefault inside passive event listener") ||
            message.startsWith("ResizeObserver loop") ||
            // 两种 Chromium 措辞：「on an element」（泛指）与「on a <body> element」
            // （2026-11-02 实机日志实测带具体标签）——取共同前缀，修 v2.4.8 起
            // 白名单与实况不匹配导致该条从未生效的漏网
            message.startsWith("Blocked aria-hidden on ") ||
            // Cloudflare Turnstile：页面脚本请求 data: URI 被站点自身 CSP 拦截，
            // 浏览器正常执行策略，壳侧无可行动作
            message.startsWith("Refused to connect to 'data:") ||
            // Turnstile 隐形样式探针：console.error("%c...font-size:0;color:transparent")
            // 格式化垃圾输出（实测 6 条/日，两条变体 %c%d/%c%o 共含此样式串）
            message.contains("font-size:0;color:transparent") ||
            // 异常对象被字符串化的无信息量残渣（Discourse 等：
            // "[object DOMException]" 连异常类型都拿不到，留痕无判读价值）
            message.startsWith("[object")
    }
}
