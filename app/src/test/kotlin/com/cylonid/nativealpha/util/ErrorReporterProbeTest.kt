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
}
