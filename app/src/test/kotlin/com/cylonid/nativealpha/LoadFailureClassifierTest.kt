package com.cylonid.nativealpha

import com.cylonid.nativealpha.util.LoadFailureClassifier
import com.cylonid.nativealpha.util.LoadFailureClassifier.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 主框架加载失败分类穷举（借鉴 happier 三分法）：
 * 证书/URL 终态不自动重载，断网/DNS/超时瞬态走探测-恢复闭环。
 */
class LoadFailureClassifierTest {

    /** 瞬态：断网/连接类（真机日志实证 desc 携带 net::ERR_* 串） */
    @Test
    fun transient_connectionFamily() {
        assertEquals(
            Kind.RETRYABLE,
            LoadFailureClassifier.classify("-6", "net::ERR_CONNECTION_CLOSED")
        )
        assertEquals(
            Kind.RETRYABLE,
            LoadFailureClassifier.classify("-8", "net::ERR_CONNECTION_TIMED_OUT")
        )
        assertEquals(
            Kind.RETRYABLE,
            LoadFailureClassifier.classify("-2", "net::ERR_NAME_NOT_RESOLVED")
        )
        assertEquals(
            Kind.RETRYABLE,
            LoadFailureClassifier.classify("-21", "net::ERR_INTERNET_DISCONNECTED")
        )
    }

    /** 终态：证书/SSL 族（desc 匹配 + errorCode 兜底两条路径） */
    @Test
    fun security_certFamily() {
        assertEquals(
            Kind.SECURITY,
            LoadFailureClassifier.classify("-201", "net::ERR_CERT_COMMON_NAME_INVALID")
        )
        assertEquals(
            Kind.SECURITY,
            LoadFailureClassifier.classify("-501", "net::ERR_SSL_PROTOCOL_ERROR")
        )
        assertEquals(
            Kind.SECURITY,
            LoadFailureClassifier.classify("-11", "")
        )
    }

    /** 终态：scheme/URL 非法族 */
    @Test
    fun badAddress_schemeFamily() {
        assertEquals(
            Kind.BAD_ADDRESS,
            LoadFailureClassifier.classify("-10", "net::ERR_UNSUPPORTED_SCHEME")
        )
        assertEquals(
            Kind.BAD_ADDRESS,
            LoadFailureClassifier.classify("-12", "net::ERR_BAD_URL")
        )
        assertEquals(
            Kind.BAD_ADDRESS,
            LoadFailureClassifier.classify("-12", null)
        )
    }

    /** 边界：空输入/未知错误 → 缺省 RETRYABLE（保持既有探测行为） */
    @Test
    fun default_retryableForUnknownOrNull() {
        assertEquals(Kind.RETRYABLE, LoadFailureClassifier.classify(null, null))
        assertEquals(Kind.RETRYABLE, LoadFailureClassifier.classify("unknown", ""))
        assertEquals(
            Kind.RETRYABLE,
            LoadFailureClassifier.classify("-1", "net::ERR_UNKNOWN")
        )
    }

    /** 分类到重试决策的映射：仅 RETRYABLE 可探测恢复 */
    @Test
    fun isRetryable_onlyForRetryableKind() {
        assertEquals(true, LoadFailureClassifier.isRetryable(Kind.RETRYABLE))
        assertEquals(false, LoadFailureClassifier.isRetryable(Kind.SECURITY))
        assertEquals(false, LoadFailureClassifier.isRetryable(Kind.BAD_ADDRESS))
    }
}
