package com.cylonid.nativealpha.util

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Web Vitals 明细（Phase 3，三档数据）：每站最近 N 次页面加载的性能细分。
 *
 * 数据域：DNS / TCP / TTFB / FCP / LCP（ms）+ DOM 节点数（页面重量代理）。
 * 存储：web_vitals（DataStore，经 JsonPrefsStore 模板），每站滚动保留 10 条。
 * 与 PageErrorRepository 的「按站 JSON 内嵌 site 字段」不同——本仓 key 内
 * 直接以 webappId 分桶（读多写少，免过滤）。
 */
internal data class WebVitalsEntry(
    val dns: Int = 0,
    val tcp: Int = 0,
    val ttfb: Int = 0,
    val fcp: Int = 0,
    val lcp: Int = 0,
    val domNodes: Int = 0,
    val at: Long = 0L
)

internal data class WebVitalsMap(val perSite: Map<String, List<WebVitalsEntry>> = emptyMap())

internal object WebVitalsStore {

    /** 每站保留条数（瀑布图取最近一次 + 历史趋势余量） */
    private const val MAX_PER_SITE = 10

    private val store = object : JsonPrefsStore<WebVitalsMap>(stringPreferencesKey("web_vitals")) {
        private val gson = com.google.gson.Gson()
        override fun empty() = WebVitalsMap()
        override fun decode(json: String): WebVitalsMap = try {
            gson.fromJson(json, WebVitalsMap::class.java)
        } catch (e: Exception) {
            empty()
        }
        override fun encode(value: WebVitalsMap): String = gson.toJson(value)
    }

    /** 追加一条（滚动丢最旧；挂起，IO 语境调用） */
    suspend fun append(context: Context, webappId: Int, entry: WebVitalsEntry) =
        withContextInternal(context) {
            val siteKey = webappId.toString()
            val list = ((perSite[siteKey] ?: emptyList()) + entry).takeLast(MAX_PER_SITE)
            WebVitalsMap(perSite + (siteKey to list))
        }

    /** 某站全部明细（按时间倒序：最新在前；无记录空表） */
    suspend fun getForSite(context: Context, webappId: Int): List<WebVitalsEntry> =
        store.read(context).perSite[webappId.toString()].orEmpty().sortedByDescending { it.at }

    /** 清空某站（StatsClearer 编排点） */
    suspend fun clearForSite(context: Context, webappId: Int) =
        withContextInternal(context) { WebVitalsMap(perSite - webappId.toString()) }

    /** 全站清空 */
    suspend fun clear(context: Context) = store.write(context, WebVitalsMap())

    private suspend fun withContextInternal(
        context: Context,
        transform: suspend WebVitalsMap.() -> WebVitalsMap
    ) {
        store.write(context, transform(store.read(context)))
    }
}
