package com.cylonid.nativealpha.util

import com.cylonid.nativealpha.model.AppErrorEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 取证探针格式化单测（ErrorReporter.probe /「程序内日志 → 导出 → 实锤」
 * 统一模式的现场格式约定）：`event k1=v1 k2=v2`，null 显式保留、空 fields
 * 退化为纯事件名。
 */
class ErrorReporterProbeTest {

    @Test
    fun `event with fields renders as key value chain`() {
        val out = ErrorReporter.formatProbe(
            "returned",
            mapOf("code" to -1, "clip" to 1, "dataUri" to null, "flags" to "0x1")
        )
        assertEquals("returned code=-1 clip=1 dataUri=null flags=0x1", out)
    }

    @Test
    fun `event without fields renders bare`() {
        assertEquals("launch", ErrorReporter.formatProbe("launch", emptyMap()))
    }

    @Test
    fun `field order follows insertion`() {
        val out = ErrorReporter.formatProbe("e", mapOf("b" to 2, "a" to 1))
        assertEquals("e b=2 a=1", out)
    }

    @Test
    fun `probe default level is info`() {
        // 取证信息非故障：崩溃弹窗/错误统计只认 CRASH/ERROR，INFO 不受影响
        assertEquals("INFO", AppErrorEntry.LEVEL_INFO)
    }

    @Test
    fun `url with fragment token is redacted`() {
        // v2.3.13 实机导出实锤：site=#token=J1tP 随探针进日志，凭证必须截断
        assertEquals(
            "http://114.132.159.229:15666/",
            ErrorReporter.redactUrl("http://114.132.159.229:15666/#token=J1tP")
        )
    }

    @Test
    fun `url with query is redacted`() {
        assertEquals(
            "https://zcode.z.ai/remote/v4",
            ErrorReporter.redactUrl("https://zcode.z.ai/remote/v4?sid=d_GBKr2")
        )
    }

    @Test
    fun `url with query and fragment truncates at question mark`() {
        assertEquals(
            "https://example.com/path",
            ErrorReporter.redactUrl("https://example.com/path?a=1#token=secret")
        )
    }

    @Test
    fun `url without query or fragment kept as is`() {
        val url = "https://example.com/remote/v4"
        assertEquals(url, ErrorReporter.redactUrl(url))
    }

    @Test
    fun `overly long url stem is capped`() {
        val long = "https://example.com/" + "a".repeat(200)
        assertEquals(80, ErrorReporter.redactUrl(long).length)
    }

    @Test
    fun `non url string values pass through untouched`() {
        // 非 URL 值保持 toString 渲染语义（formatProbe 拼接后的形态）
        assertEquals("LEGACY", ErrorReporter.sanitizeFieldValue("LEGACY"))
        assertEquals("-1", ErrorReporter.sanitizeFieldValue(-1))
        assertEquals("true", ErrorReporter.sanitizeFieldValue(true))
        assertEquals("null", ErrorReporter.sanitizeFieldValue(null))
    }

    @Test
    fun `content uri query is redacted but path stem kept`() {
        // content:// 文件 URI 无 query/fragment 时保留主干（排查需 provider 来源），
        // 带读取参数时截参数
        val uri = "content://com.miui.gallery.open/raw/%2Fstorage%2FDCIM%2Fshot.jpg"
        assertEquals(uri, ErrorReporter.sanitizeFieldValue(uri))
    }

    @Test
    fun `formatProbe redacts site field end to end`() {
        val out = ErrorReporter.formatProbe(
            "cell_dark_mode",
            mapOf("forced" to false, "site" to "https://zcode.z.ai/remote/v4?sid=d_GBKr2")
        )
        assertEquals("cell_dark_mode forced=false site=https://zcode.z.ai/remote/v4", out)
    }
}
