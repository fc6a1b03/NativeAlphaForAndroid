package com.cylonid.nativealpha.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp
import java.util.Locale

/**
 * 统计洞察引擎（A4 策略 + 注册表）：从既有统计字段自动生成「一句话洞察」。
 *
 * 设计：每条规则一个 [InsightStrategy]（一行一条注册），引擎按权重排序
 * ——新增洞察零修改引擎（开闭原则）。原「使用建议」（buildSuggestions）
 * 三条规则合流入本体系，消解一套并行的同构机制。
 *
 * 诚实红线：只陈述真实计算的值；[Insight.count] 非空时 UI 走 plurals，
 * 禁止编造对比基线。文案 resId 由 UI 层 stringResource 解析（R1：组合期取文案）。
 */

/** 洞察上下文：规则评估的全部输入（只读快照，无行为） */
internal data class InsightContext(
    val webapp: WebApp,
    /** FeatureMetrics 模块快照（webevent/matrix/share），key=event，value=累计次数 */
    val automation: Map<String, Long>
)

/** 单条洞察：文案资源（单句 textRes 或复数 pluralsRes 二选一）+ 权重（越大越先展示） */
internal data class Insight(
    @StringRes val textRes: Int? = null,
    @PluralsRes val pluralsRes: Int? = null,
    val args: List<String> = emptyList(),
    /** 复数计数（pluralsRes 必填；count 同时是文案的数值参数） */
    val count: Int? = null,
    val weight: Int
)

/** 洞察策略（函数式接口，注册表一行一条） */
internal fun interface InsightStrategy {
    fun evaluate(ctx: InsightContext): Insight?
}

// ===== 洞察阈值（数据→行动边界，仅此一处） =====
/** 平均加载低于该值（ms）视为「几乎无感」 */
private const val INSIGHT_SMOOTH_LOAD_MS = 1000L
/** 平均加载超过该值（ms）提示优化（约 3s，原建议阈值） */
internal const val SUGGEST_SLOW_LOAD_MS = 3000L
/** 页面错误数超过该值提示查看错误日志（原建议阈值） */
internal const val SUGGEST_ERROR_COUNT = 10
/** 缓存占用超过该值（B）提示清理（约 50MB，原建议阈值） */
private const val SUGGEST_CACHE_BYTES = 50L * 1024 * 1024

/** 洞察规则注册表：一行一条，weight 决定展示优先级 */
internal val INSIGHT_STRATEGIES: List<InsightStrategy> = listOf(
    // 慢载提示（原建议合流；最影响体验，权重最高）
    InsightStrategy { ctx ->
        avgLoadMs(ctx.webapp).takeIf { it > SUGGEST_SLOW_LOAD_MS }?.let {
            Insight(textRes = R.string.suggestion_slow_load, args = listOf(formatDuration(it)), weight = 60)
        }
    },
    // 自动化参与感：网页事件通知累计（WebNative 独有数据）
    InsightStrategy { ctx ->
        ctx.automation["notification_shown"]?.takeIf { it > 0 }?.let {
            Insight(
                pluralsRes = R.plurals.insight_notification, count = it.toInt(),
                weight = 50
            )
        }
    },
    // 错误提示（原建议合流）
    InsightStrategy { ctx ->
        ctx.webapp.statErrors.takeIf { it > SUGGEST_ERROR_COUNT }?.let {
            Insight(
                pluralsRes = R.plurals.suggestion_errors, count = it,
                weight = 40
            )
        }
    },
    // 速度型：几乎无感加载
    InsightStrategy { ctx ->
        avgLoadMs(ctx.webapp).takeIf { it in 1 until INSIGHT_SMOOTH_LOAD_MS }?.let {
            Insight(textRes = R.string.insight_speed_smooth, args = listOf(formatDuration(it)), weight = 30)
        }
    },
    // 缓存型：缓存体量即已省下的重新下载量（事实陈述）
    InsightStrategy { ctx ->
        ctx.webapp.statCacheHttpBytes.takeIf { it > 0 }?.let {
            Insight(textRes = R.string.insight_cache, args = listOf(formatBytes(it)), weight = 20)
        }
    },
    // 缓存清理提示（原建议合流；体量大到值得清理才打扰）
    InsightStrategy { ctx ->
        ctx.webapp.statCacheHttpBytes.takeIf { it > SUGGEST_CACHE_BYTES }?.let {
            Insight(textRes = R.string.suggestion_cache, args = listOf(formatBytes(it)), weight = 10)
        }
    }
)

/** 生成全部命中洞察（权重降序；空列表 = 不展示洞察卡） */
internal fun buildInsights(ctx: InsightContext): List<Insight> =
    INSIGHT_STRATEGIES.mapNotNull { it.evaluate(ctx) }.sortedByDescending { it.weight }

/** 平均加载耗时（ms；无加载记录返回 0） */
internal fun avgLoadMs(webapp: WebApp): Long =
    if (webapp.statLoadTimeCount > 0) webapp.statLoadTimeSum / webapp.statLoadTimeCount else 0L

/** 次数缩写（千分位/1.2K，UX 规范：大数字必须可读） */
internal fun formatCount(n: Long): String =
    if (n < 1000) n.toString()
    else if (n < 1_000_000) String.format(Locale.getDefault(), "%.1fK", n / 1000.0)
    else String.format(Locale.getDefault(), "%.1fM", n / 1_000_000.0)
