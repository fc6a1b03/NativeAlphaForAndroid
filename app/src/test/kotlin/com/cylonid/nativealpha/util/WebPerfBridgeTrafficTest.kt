package com.cylonid.nativealpha.util

import com.cylonid.nativealpha.model.WebApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 流量采集载荷解析契约：非负增量、越界丢弃、脏数据不污染累计。
 *
 * Robolectric 跑：解析用 org.json.JSONObject，JVM 纯单测下是 Android stub
 * （not mocked），必须借 Robolectric 的真实实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebPerfBridgeTrafficTest {

    @Test
    fun validDeltaParsed() {
        assertEquals(1024L, WebPerfBridge.parseTrafficDelta("""{"delta":1024}"""))
    }

    @Test
    fun zeroDeltaDiscarded() {
        // 增量 0 无意义（未产生新传输），不得计入批次
        assertNull(WebPerfBridge.parseTrafficDelta("""{"delta":0}"""))
    }

    @Test
    fun negativeDeltaDiscarded() {
        assertNull(WebPerfBridge.parseTrafficDelta("""{"delta":-500}"""))
    }

    @Test
    fun oversizedDeltaDiscarded() {
        // 超单批上限（512MB）：异常值保护，丢弃而非截断
        val over = WebPerfBridge.MAX_TRAFFIC_DELTA + 1
        assertNull(WebPerfBridge.parseTrafficDelta("""{"delta":$over}"""))
    }

    @Test
    fun missingFieldDiscarded() {
        assertNull(WebPerfBridge.parseTrafficDelta("""{"other":123}"""))
    }

    @Test
    fun malformedPayloadDiscarded() {
        assertNull(WebPerfBridge.parseTrafficDelta("not json"))
        assertNull(WebPerfBridge.parseTrafficDelta(""))
        assertNull(WebPerfBridge.parseTrafficDelta(null))
    }

    @Test
    fun exactMaxDeltaAccepted() {
        val max = WebPerfBridge.MAX_TRAFFIC_DELTA
        assertEquals(max, WebPerfBridge.parseTrafficDelta("""{"delta":$max}"""))
    }
}
