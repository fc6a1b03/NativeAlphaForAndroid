package com.cylonid.nativealpha.util

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * 功能级观测门面（v2.2.0 §2.2 / P3.5）：matrix / webevent 独立包的计数与
 * 错误上报统一入口。
 *
 * 设计：
 * - 计数内存聚合（LongAdder 无锁热路径）；每模块累计达 [FLUSH_THRESHOLD] 触发
 *   一次 DataStore 落盘——禁止每次事件写盘（损耗红线）
 * - 落盘走 SupervisorJob+IO 协程，失败静默（ErrorReporter 同范式）；站点级
 *   事件仍走宿主 StatsRecorder（按 webappId 归属），本门面只管功能自身事件
 * - 错误透传宿主 ErrorReporter（tag 前缀 module:，导出日志可按模块过滤）
 * - v1 只入库存档不加 UI（统计页面保持原样——M2 决策）
 */
object FeatureMetrics {

    /** 每模块累计计数达此值触发一次 DataStore 落盘（覆盖写全量快照，幂等） */
    private const val FLUSH_THRESHOLD = 20

    /** 测试/调用方可读阈值（避免魔法数耦合进断言） */
    internal val flushThreshold get() = FLUSH_THRESHOLD

    /** 计数器池：key = "module:event"（LongAdder 无锁累加） */
    private val counters = ConcurrentHashMap<String, LongAdder>()

    /** 模块级落盘节流：距上次触发以来的累计次数 */
    private val sinceFlush = ConcurrentHashMap<String, AtomicInteger>()

    /** IO 作用域（SupervisorJob：单模块落盘失败不级联其他模块） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** DataStore 键（AppStorage 统一封装，按模块隔离） */
    private fun storeKey(module: String) = stringPreferencesKey("feature_metrics_$module")

    /**
     * 落盘通道（internal 可替换：单测注入捕获，生产写 AppStorage DataStore）。
     * 生产实现的序列化在调用线程（µs 级 Map 拼接），磁盘 IO 全在协程。
     */
    internal var persister: (module: String, snapshot: Map<String, Long>) -> Unit =
        { module, snapshot ->
            val json = Gson().toJson(snapshot)
            scope.launch {
                try {
                    AppStorage.writeString(App.getAppContext(), storeKey(module), json)
                } catch (ignored: Exception) {
                    // 落盘失败静默（观测数据不阻塞主功能；下次阈值触发全量覆盖）
                }
            }
        }

    /**
     * 功能事件计数（任意线程安全，无锁热路径）。
     * 达到模块阈值时异步落盘该模块全量快照。
     */
    fun count(module: String, event: String) {
        counters.computeIfAbsent("$module:$event") { LongAdder() }.increment()
        val pending = sinceFlush.computeIfAbsent(module) { AtomicInteger() }
        if (pending.incrementAndGet() >= FLUSH_THRESHOLD) {
            pending.set(0)
            persister(module, moduleSnapshot(module))
        }
    }

    /**
     * 功能错误上报：透传宿主 ErrorReporter（tag 前缀 module:）。
     * 永不抛异常（观测逻辑不得影响主功能）。
     */
    fun reportError(module: String, where: String, message: String, error: Throwable? = null) {
        try {
            ErrorReporter.report(App.getAppContext(), "$module:$where", message, error)
        } catch (ignored: Exception) {
            // 上报失败静默（观测通道永不阻塞主功能）
        }
    }

    /** 模块全量计数快照（无锁遍历，弱一致即可——观测数据） */
    internal fun moduleSnapshot(module: String): Map<String, Long> {
        val prefix = "$module:"
        val out = LinkedHashMap<String, Long>()
        for ((key, adder) in counters) {
            if (key.startsWith(prefix)) {
                out[key.removePrefix(prefix)] = adder.sum()
            }
        }
        return out
    }
}
