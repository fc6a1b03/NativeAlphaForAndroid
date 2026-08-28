package com.cylonid.nativealpha.matrix

import com.google.gson.Gson
import com.google.gson.JsonParseException

/**
 * MatrixSessionState 的 Gson 编解码（P4 数据层）。
 *
 * 编解码独立成纯函数对象：损坏 JSON 的兜底策略由 [MatrixSessionStore]
 * 施加（解码抛异常 → 默认值），本对象只负责纯转换、可无依赖单测。
 *
 * 字段名即持久化契约（v2.0 起无兼容层）：@Keep 锁名 + 契约单测防漂移。
 */
internal object MatrixSessionCodec {

    private val gson = Gson()

    /** 序列化（DataStore 写入前） */
    fun encode(state: MatrixSessionState): String = gson.toJson(state)

    /**
     * 反序列化（DataStore 读出后）。损坏/非法 JSON 抛 [JsonParseException]，
     * 调用方须捕获并回退默认值（fail-safe，不崩溃）。
     */
    fun decode(json: String): MatrixSessionState =
        gson.fromJson(json, MatrixSessionState::class.java)
}
