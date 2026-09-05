package com.cylonid.nativealpha.util

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * JSON 载荷 DataStore 仓库模板（A2 模板方法）：子类只声明 key 与编解码，
 * 读/写统一在此——消解 PageErrorRepository 式逐仓重复样板。
 *
 * 注：DataStore 文件为重复的 map-entry message（0a entryLen [0a keyLen key 12 prefLen pref]），
 * 排障注入/导出时须按此三层编码构造。
 * 契约：全部挂起（IO 语境调用）；解码失败返回空值不抛（数据文件损坏
 * 不阻塞功能，下次写入自动重建）。
 *
 * @param T 仓库载荷模型（快照对象，不可变）
 */
internal abstract class JsonPrefsStore<T>(val key: Preferences.Key<String>) {

    /** 空值（解码失败/空文件时的兜底，如空 Map/空 List） */
    protected abstract fun empty(): T

    /** 反序列化（实现内自捕获格式异常转空值） */
    protected abstract fun decode(json: String): T

    /** 序列化 */
    protected abstract fun encode(value: T): String

    /** 读取（损坏自动转空值） */
    suspend fun read(context: Context): T =
        try {
            val json = AppStorage.readString(context, key)
            if (json.isBlank()) empty() else decode(json)
        } catch (e: Exception) {
            empty()
        }

    /** 原子写入 */
    suspend fun write(context: Context, value: T) {
        AppStorage.writeString(context, key, encode(value))
    }

    companion object {
        /** key 工厂（统一 string key 创建口径） */
        fun stringKey(name: String): Preferences.Key<String> = stringPreferencesKey(name)
    }
}

/**
 * 条目级防御清洗（各 store 的 Gson decode 共用，R3 单一实现）：
 * Gson 泛型解析在 R8 混淆下可能把集合元素退化为 LinkedTreeMap——
 * 统一安全过滤丢弃坏条目，不使统计页崩溃。
 */
internal inline fun <reified T : Any> List<*>.filterInstances(): List<T> =
    mapNotNull { it as? T }
