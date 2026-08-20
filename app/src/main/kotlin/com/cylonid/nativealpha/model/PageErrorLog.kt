package com.cylonid.nativealpha.model

import android.content.Context
import com.cylonid.nativealpha.util.AppStorage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Collections

/**
 * 页面运行/网络错误明细（按 WebApp 独立）。
 *
 * 数据域：页面加载错误（onReceivedError/onReceivedHttpError/SSL/RenderGone）。
 * 存储：KEY_PAGE_ERRORS（DataStore 唯一存储源），上限 200 条丢最旧。
 * 与 KEY_APP_ERRORS（应用自身错误）完全分离，防统计口径污染。
 */
data class PageErrorEntry(
    val time: Long = 0L,          // 发生时间（epoch ms）
    val site: String = "",        // 站点 URL
    val type: String = "",        // 类型：HTTP / NETWORK / SSL / RENDER
    val code: String = "",        // 错误码（如 ERR_NAME_NOT_RESOLVED / 404）
    val description: String = ""  // 描述
) {
    companion object {
        private val gson = Gson()

        fun toJson(entries: List<PageErrorEntry>): String = gson.toJson(entries)

        fun fromJson(json: String): List<PageErrorEntry> {
            return try {
                val type = object : TypeToken<List<PageErrorEntry>>() {}.type
                val list: List<PageErrorEntry>? = gson.fromJson(json, type)
                list ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

/** 页面错误日志仓库：DataStore 唯一读写（异步，不阻塞主线程） */
object PageErrorRepository {
    private const val MAX_ENTRIES = com.cylonid.nativealpha.util.Const.ERROR_LOG_LIMIT  // 上限（条），超出丢最旧

    /** 追加一条页面错误（挂起函数，协程内调用） */
    suspend fun append(context: Context, webappId: Int, type: String, code: String, description: String) {
        try {
            val current = AppStorage.readString(context, AppStorage.KEY_PAGE_ERRORS)
            val list = PageErrorEntry.fromJson(current).toMutableList()
            list.add(
                PageErrorEntry(
                    time = System.currentTimeMillis(),
                    site = webappId.toString(),
                    type = type,
                    code = code,
                    description = description
                )
            )
            // 超上限丢最旧
            if (list.size > MAX_ENTRIES) {
                Collections.sort(list) { a, b -> a.time.compareTo(b.time) }
                list.subList(0, list.size - MAX_ENTRIES).clear()
            }
            AppStorage.writeString(context, AppStorage.KEY_PAGE_ERRORS, PageErrorEntry.toJson(list))
        } catch (e: Exception) {
            // 写入失败静默（不影响主功能）
        }
    }

    /** 读取某站点全部错误（挂起函数） */
    suspend fun getForSite(context: Context, webappId: Int): List<PageErrorEntry> {
        return try {
            val json = AppStorage.readString(context, AppStorage.KEY_PAGE_ERRORS)
            PageErrorEntry.fromJson(json).filter { it.site == webappId.toString() }
                .sortedByDescending { it.time }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 清空某站点错误（统计页「清空统计」用） */
    suspend fun clearForSite(context: Context, webappId: Int) {
        try {
            val json = AppStorage.readString(context, AppStorage.KEY_PAGE_ERRORS)
            val list = PageErrorEntry.fromJson(json).filter { it.site != webappId.toString() }
            AppStorage.writeString(context, AppStorage.KEY_PAGE_ERRORS, PageErrorEntry.toJson(list))
        } catch (e: Exception) {
            // 静默
        }
    }
}
