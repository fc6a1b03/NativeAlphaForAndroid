package com.cylonid.nativealpha.webevent

import androidx.annotation.Keep

/**
 * 网页事件规则（P5 数据模型，规格 §5.2）。
 *
 * 持久化契约：经 Gson 落入自持 DataStore `webevent_rules`（不参与宿主
 * 备份）+ proguard-rules.pro 显式 keep——release 实测 @Keep 不足以保住
 * Gson 反射字段名（P4 教训），字段改名 = 用户规则丢失。
 *
 * 触发器/动作取值约定（字符串而非枚举序列化，规格原文）：
 * - trigger: notification | title | selector
 * - action: notify | toast
 */
@Keep
data class EventRule(
    /** 规则唯一 ID（UUID） */
    val id: String,
    /** 绑定站点（宿主 WebApp ID）；站点删除时级联删除（P5-3） */
    val webappId: Int,
    /** 逐规则启用开关（与站点级静音两层粒度独立，P5-4） */
    val enabled: Boolean = true,
    /** 触发器类型：notification | title | selector */
    val trigger: String,
    /** 条件：关键字（notification/title，空=全部）/ CSS 选择器（selector） */
    val condition: String,
    /** 动作：notify | toast */
    val action: String = ACTION_NOTIFY,
    /** 冷却毫秒（同一规则窗口内只提醒一次，规格 Slider 0..60s 默认 10s） */
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    /** 创建时间戳（排序展示） */
    val createdAt: Long = 0L
) {
    companion object {
        const val TRIGGER_NOTIFICATION = "notification"
        const val TRIGGER_TITLE = "title"
        const val TRIGGER_SELECTOR = "selector"

        const val ACTION_NOTIFY = "notify"
        const val ACTION_TOAST = "toast"

        const val DEFAULT_COOLDOWN_MS = 10_000L

        /** 单站规则上限（防滥用；超出禁新增） */
        const val MAX_RULES_PER_SITE = 20
    }
}

/** 事件负载（bridge JSON 解析后的强类型，引擎唯一入参形态） */
data class WebEvent(
    val webappId: Int,
    /** notification | title | selector */
    val type: String,
    /** 事件摘要：T1=通知标题 / T3=新标题 / T2=命中的选择器 */
    val title: String,
    /** T1 附带的通知正文（匹配域含 body，P5-5） */
    val body: String = ""
)
