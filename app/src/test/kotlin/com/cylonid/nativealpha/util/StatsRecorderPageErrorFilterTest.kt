package com.cylonid.nativealpha.util

import com.cylonid.nativealpha.model.ErrorType
import com.cylonid.nativealpha.util.StatsRecorder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络类页面错误记录判定锁（站点统计降噪开关）。
 *
 * 用户定调：断网/DNS 失败等网络类错误属环境观测非站点缺陷，默认不记入
 * 站点统计；开关开启后恢复完整记录。判定收口在 shouldRecordPageError
 * 纯函数，本测试穷举锁死口径——任何类型误过滤/误放行在此即刻爆红。
 */
class StatsRecorderPageErrorFilterTest {

    @Test
    fun networkType_filteredByDefault() {
        assertFalse(
            "默认口径：NETWORK 类不记录",
            StatsRecorder.shouldRecordPageError(ErrorType.NETWORK.name, networkLoggingEnabled = false)
        )
    }

    @Test
    fun networkType_recordedWhenSwitchOn() {
        assertTrue(
            "开关开启：NETWORK 类恢复记录",
            StatsRecorder.shouldRecordPageError(ErrorType.NETWORK.name, networkLoggingEnabled = true)
        )
    }

    @Test
    fun nonNetworkTypes_alwaysRecorded() {
        val networkOn = true
        val networkOff = false
        for (type in ErrorType.entries - ErrorType.NETWORK) {
            assertTrue(
                "$type 与网络开关无关，恒记录（关）",
                StatsRecorder.shouldRecordPageError(type.name, networkLoggingEnabled = networkOff)
            )
            assertTrue(
                "$type 与网络开关无关，恒记录（开）",
                StatsRecorder.shouldRecordPageError(type.name, networkLoggingEnabled = networkOn)
            )
        }
    }

    @Test
    fun unknownType_notDropped() {
        // 未知类型（未来新增枚举前的过渡数据）不得被误过滤
        assertTrue(StatsRecorder.shouldRecordPageError("UNKNOWN", networkLoggingEnabled = false))
    }
}
