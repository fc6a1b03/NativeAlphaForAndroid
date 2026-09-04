package com.cylonid.nativealpha.webevent

import com.cylonid.nativealpha.util.FeatureMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 事件规则引擎（P5，规格 §5.2：匹配→冷却→同站合并）。
 *
 * 纯函数核心抽 [Matcher]/[EdgeDetector]/[CooldownGate]（可无依赖单测）；
 * 合并窗口同站 500ms（Q9：多规则同站命中合并 1 条「N 个规则触发」）。
 *
 * 运行时状态（P5-7）：跳变沿/冷却时间戳存进程内存，进程死重置可接受。
 * 全部可变状态只在主线程触碰（bridge 回调与协程均在 Main.immediate）。
 */
internal object EventRuleEngine {

    /** 同站合并窗口（Q9 规格值） */
    const val MERGE_WINDOW_MS = 500L

    // ===== 纯函数核心 =====

    /** 关键字匹配（P5-5：title+body 域，包含即命中，不区分大小写） */
    internal object Matcher {
        fun matches(rule: EventRule, event: WebEvent): Boolean {
            if (!rule.enabled) return false
            if (rule.trigger == EventRule.TRIGGER_SELECTOR) {
                // T2：hook 端回传命中的选择器原文，与条件相等即命中
                return event.type == EventRule.TRIGGER_SELECTOR && event.title == rule.condition
            }
            if (event.type != rule.trigger) return false
            val keyword = rule.condition.trim()
            if (keyword.isEmpty()) return true // 空=全部触发（规格 helperText 语义）
            val needle = keyword.lowercase()
            return event.title.lowercase().contains(needle) ||
                event.body.lowercase().contains(needle)
        }
    }

    /** 跳变沿检测（P5-5：T3/T2 仅「从不含→含」触发；T1 无跳变沿语义） */
    internal class EdgeDetector {
        private val lastMatched = HashMap<String, Boolean>()

        /** @return true=本次为上升沿（或该触发器类型无沿语义） */
        fun shouldFire(rule: EventRule, matched: Boolean): Boolean = when (rule.trigger) {
            EventRule.TRIGGER_TITLE, EventRule.TRIGGER_SELECTOR -> {
                val key = rule.id
                val previous = lastMatched[key] ?: false
                lastMatched[key] = matched
                matched && !previous
            }
            else -> matched
        }

        /** 规则删除时清理沿状态（防泄漏+防旧状态干扰重建的同 id 规则） */
        fun forget(ruleId: String) {
            lastMatched.remove(ruleId)
        }
    }

    /** 冷却闸（同一规则冷却窗口内只提醒一次；无历史=首次触发直接放行） */
    internal class CooldownGate {
        private val lastFiredAt = HashMap<String, Long>()

        fun shouldFire(rule: EventRule, nowMs: Long): Boolean {
            val previous = lastFiredAt[rule.id]
            if (previous == null) {
                lastFiredAt[rule.id] = nowMs // 首次触发同样起算冷却窗
                return true
            }
            if (nowMs - previous < rule.cooldownMs) return false
            lastFiredAt[rule.id] = nowMs
            return true
        }

        fun clear() = lastFiredAt.clear()
    }

    // ===== 编排（主线程） =====

    private val edges = EdgeDetector()
    private val cooldowns = CooldownGate()

    /** 同站合并窗口挂起任务（siteId → 待发批次） */
    private val pendingMerges = HashMap<Int, MergeBatch>()
    private val pendingJobs = HashMap<Int, Job>()

    /** 动作执行回调（由 Notifier/宿主环境注入——引擎保持无 Android UI 依赖） */
    internal var actionDispatcher: ActionDispatcher? = null

    internal interface ActionDispatcher {
        /** @param hitCount 合并后的命中规则数（>1 表示多规则合并） */
        fun dispatch(rule: EventRule, event: WebEvent, hitCount: Int)
    }

    /**
     * 事件入口（bridge 回调，主线程）：站点静音/跳变沿/冷却逐关过滤，
     * 命中进同站合并窗口。
     */
    fun onWebEvent(event: WebEvent, nowMs: Long = System.currentTimeMillis()) {
        val siteRules = EventRuleStore.rules.value
            .filter { it.webappId == event.webappId && EventRuleStore.isSiteMuted(event.webappId).not() }
        var matchedAny = false
        for (rule in siteRules) {
            val matched = Matcher.matches(rule, event)
            if (!edges.shouldFire(rule, matched)) continue
            if (!matched) continue
            if (!cooldowns.shouldFire(rule, nowMs)) continue
            matchedAny = true
            scheduleDispatch(rule, event, nowMs)
        }
        if (matchedAny) FeatureMetrics.count(FeatureMetrics.MODULE_WEBEVENT, "rule_matched")
    }

    /** 命中入同站合并窗口：首条开窗定时 flush，窗口内后续命中并入批次 */
    private fun scheduleDispatch(rule: EventRule, event: WebEvent, @Suppress("UNUSED_PARAMETER") nowMs: Long) {
        val batch = pendingMerges[event.webappId]
        if (batch == null) {
            pendingMerges[event.webappId] = MergeBatch(rule, event, 1)
            pendingJobs[event.webappId] = CoroutineScope(Dispatchers.Main.immediate).launch {
                delay(MERGE_WINDOW_MS)
                flush(event.webappId)
            }
        } else {
            batch.hitCount += 1
        }
    }

    private fun flush(webappId: Int) {
        val batch = pendingMerges.remove(webappId) ?: return
        pendingJobs.remove(webappId)?.cancel()
        val dispatcher = actionDispatcher ?: return
        dispatcher.dispatch(batch.firstRule, batch.event, batch.hitCount)
        FeatureMetrics.count(FeatureMetrics.MODULE_WEBEVENT, "fired")
    }

    /** 站点级联清理时同步引擎运行态（P5-3；冷却表全局清空——低频场景） */
    fun forgetSite(webappId: Int) {
        EventRuleStore.rulesForSite(webappId).forEach { edges.forget(it.id) }
        cooldowns.clear()
        pendingMerges.remove(webappId)
        pendingJobs.remove(webappId)?.cancel()
    }

    /** 单条规则删除时清理沿状态（防旧状态干扰重建的同 id 规则） */
    fun forgetRule(ruleId: String) {
        edges.forget(ruleId)
    }

    private class MergeBatch(val firstRule: EventRule, val event: WebEvent, var hitCount: Int)
}
