package com.cylonid.nativealpha.webevent

import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebeventBackup
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/**
 * webevent 备份分区编解码 + 适配器（C1：规则纳入备份）。
 *
 * 编解码独立成纯函数（可单测，无 Android 依赖）；字段名即 schema
 * （rules/mutedSites，与 EventRuleStore 磁盘 JSON 同构——同一 Gson 契约）。
 * 解码 fail-safe：损坏分区返回 null，调用方降级为「不恢复」，不阻断
 * 整体备份导入（happier 兼容性纪律：单操作降级优于整体拒绝）。
 */
internal object WebeventBackupCodec {

    private val gson = com.google.gson.Gson()
    private val rulesType = object : TypeToken<List<EventRule>>() {}.type
    private val mutedType = object : TypeToken<Set<Int>>() {}.type

    const val KEY_RULES = "rules"
    const val KEY_MUTED_SITES = "mutedSites"

    fun encode(rules: List<EventRule>, mutedSites: Set<Int>): JsonObject {
        val obj = JsonObject()
        obj.add(KEY_RULES, gson.toJsonTree(rules, rulesType))
        obj.add(KEY_MUTED_SITES, gson.toJsonTree(mutedSites, mutedType))
        return obj
    }

    /** 损坏/缺 key 返回 null（降级为不恢复，不抛异常） */
    fun decode(obj: JsonObject?): Pair<List<EventRule>, Set<Int>>? {
        if (obj == null || !obj.has(KEY_RULES) || !obj.has(KEY_MUTED_SITES)) return null
        return try {
            val rules = gson.fromJson<List<EventRule>>(
                obj.get(KEY_RULES), rulesType
            ) ?: return null
            val muted = gson.fromJson<Set<Int>>(
                obj.get(KEY_MUTED_SITES), mutedType
            ) ?: return null
            rules to muted
        } catch (ignored: Exception) {
            null
        }
    }

    /** 分区 JSON 字符串解析入口（DataManager 侧拿到的是 JsonObject 引用，通常不走这） */
    fun decodeJson(raw: String?): Pair<List<EventRule>, Set<Int>>? {
        if (raw.isNullOrBlank()) return null
        return try {
            decode(JsonParser.parseString(raw).asJsonObject)
        } catch (ignored: Exception) {
            null
        }
    }

    /** 适配器：WebeventRuntime.init 时装配进 DataManager */
    internal val adapter = object : WebeventBackup {
        override fun exportJson(): JsonObject =
            encode(EventRuleStore.rules.value, EventRuleStore.mutedSites.value)

        override fun importJson(obj: JsonObject) {
            val restored = decode(obj) ?: return
            EventRuleStore.restoreForBackup(restored.first, restored.second)
        }
    }

    /** Runtime.init 调用：装配适配器（幂等） */
    fun installAdapter() {
        DataManager.getInstance().webeventBackup = adapter
    }
}
