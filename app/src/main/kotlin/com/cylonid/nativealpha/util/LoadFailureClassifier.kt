package com.cylonid.nativealpha.util

/**
 * 主框架加载失败分类（纯函数可单测，借鉴 happier 的「终态/瞬态」三分法）：
 * 重试决策由分类驱动而非盲目退避——
 * - [Kind.SECURITY]：证书/SSL 类终态，站点可达但握手必败，自动重载无意义
 *   （探测会成功、重载会再败，白耗退避周期）；用户需检查站点证书或开启
 *   忽略 SSL 错误
 * - [Kind.BAD_ADDRESS]：scheme/URL 本身非法，重试语义为零
 * - [Kind.RETRYABLE]：断网/DNS/超时/连接拒绝等瞬态（缺省兜底），适合
 *   SiteReconnectSupervisor 的探测-恢复闭环
 *
 * 判定依据：desc 里的 chromium net::ERR_* 串优先（真机日志实证 desc 携带
 * 完整错误名），errorCode 的 WebViewClient ERROR_* 常量兜底（desc 可能缺）。
 */
internal object LoadFailureClassifier {

    enum class Kind { SECURITY, BAD_ADDRESS, RETRYABLE }

    /** WebViewClient.ERROR_FAILED_SSL_HANDSHAKE */
    private const val CODE_SSL_HANDSHAKE = "-11"

    /** WebViewClient.ERROR_UNSUPPORTED_SCHEME */
    private const val CODE_UNSUPPORTED_SCHEME = "-10"

    /** WebViewClient.ERROR_BAD_URL */
    private const val CODE_BAD_URL = "-12"

    fun classify(code: String?, desc: String?): Kind {
        val d = desc ?: ""
        return when {
            d.contains("ERR_SSL", ignoreCase = true) ||
                d.contains("ERR_CERT_", ignoreCase = true) ||
                d.contains("ERR_HTTPS", ignoreCase = true) ||
                d.contains("ERR_PROXY_CERT", ignoreCase = true) ||
                code == CODE_SSL_HANDSHAKE -> Kind.SECURITY
            d.contains("ERR_UNSUPPORTED_SCHEME", ignoreCase = true) ||
                d.contains("ERR_BAD_URL", ignoreCase = true) ||
                d.contains("ERR_INVALID_URL", ignoreCase = true) ||
                d.contains("ERR_INVALID_REDIRECT", ignoreCase = true) ||
                code == CODE_UNSUPPORTED_SCHEME ||
                code == CODE_BAD_URL -> Kind.BAD_ADDRESS
            else -> Kind.RETRYABLE
        }
    }

    /** 该类失败是否值得启动断线探测-自动重载闭环 */
    fun isRetryable(kind: Kind): Boolean = kind == Kind.RETRYABLE
}
