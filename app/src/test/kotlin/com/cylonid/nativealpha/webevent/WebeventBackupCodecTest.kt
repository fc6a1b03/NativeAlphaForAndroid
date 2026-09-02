package com.cylonid.nativealpha.webevent

import com.cylonid.nativealpha.webevent.EventRule
import com.cylonid.nativealpha.webevent.WebeventBackupCodec
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * webevent 备份分区编解码契约（C1）：字段名即 schema（rules/mutedSites），
 * 与 EventRuleStore 磁盘 JSON 同构；损坏分区 fail-safe 返回 null 不抛异常。
 */
class WebeventBackupCodecTest {

    private fun sampleRule(id: String = "r1", site: Int = 3) = EventRule(
        id = id,
        webappId = site,
        enabled = true,
        trigger = EventRule.TRIGGER_TITLE,
        condition = "完成",
        action = EventRule.ACTION_NOTIFY,
        createdAt = 1690000000000L
    )

    /** 编码→解码往返：规则与静音集合无损 */
    @Test
    fun roundTrip_preservesRulesAndMutedSites() {
        val original = listOf(sampleRule(), sampleRule(id = "r2", site = 5))
        val muted = setOf(3, 7)

        val restored = WebeventBackupCodec.decode(WebeventBackupCodec.encode(original, muted))

        assertNotNull(restored)
        assertEquals(original, restored!!.first)
        assertEquals(muted, restored.second)
    }

    /** 编码产物字段名固定：rules/mutedSites（DataManager 备份 key 同契约） */
    @Test
    fun encode_usesStableFieldNames() {
        val json = WebeventBackupCodec.encode(listOf(sampleRule()), emptySet()).toString()
        assertTrue(json.contains("\"rules\""))
        assertTrue(json.contains("\"mutedSites\""))
        // 规则字段名抽样（proguard keep 契约的字段在 JSON 里真实存在）
        assertTrue(json.contains("\"webappId\""))
        assertTrue(json.contains("\"trigger\""))
    }

    /** 缺 key / 空对象 / 非法 JSON：null 降级，不抛异常 */
    @Test
    fun decode_failSafeOnCorruptPartition() {
        assertNull(WebeventBackupCodec.decode(null))
        assertNull(WebeventBackupCodec.decode(JsonParser.parseString("{}").asJsonObject))
        assertNull(WebeventBackupCodec.decodeJson("not json"))
        assertNull(WebeventBackupCodec.decodeJson(null))
        // rules 是合法 JSON 但类型错误（对象而非数组）
        assertNull(
            WebeventBackupCodec.decode(
                JsonParser.parseString("{\"rules\":{},\"mutedSites\":[]}").asJsonObject
            )
        )
    }

    /** 空规则库：合法空分区，往返得空集（非 null——显式空与损坏语义不同） */
    @Test
    fun emptyRules_roundTripsAsEmpty() {
        val restored = WebeventBackupCodec.decode(WebeventBackupCodec.encode(emptyList(), emptySet()))
        assertNotNull(restored)
        assertTrue(restored!!.first.isEmpty())
        assertTrue(restored.second.isEmpty())
    }
}
