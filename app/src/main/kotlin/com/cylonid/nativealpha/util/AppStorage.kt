package com.cylonid.nativealpha.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 统一 DataStore 存储层（Google 官方推荐存储方案）。
 *
 * 职责边界：
 * - 错误日志（KEY_PAGE_ERRORS / KEY_APP_ERRORS）与统计明细（KEY_STATS）**唯一存储源**
 * - 后续演进：WebApp 列表/设置/元信息从 SharedPreferences 迁移至此（4.0 计划）
 *
 * 设计约束：
 * - DataStore 官方单例：同文件同进程仅一个实例（顶层属性创建一次）
 * - 读写全异步（Flow），不阻塞主线程
 * - 读损坏自动恢复（catch → emptyPreferences，不崩溃）
 * - 写走 updateData 事务（原子读改写，无竞态）
 */
object AppStorage {

    /** DataStore 单例（顶层属性，官方强制单例约束） */
    private val Context.dataStore by preferencesDataStore(name = "webnative_store")

    // 统一 key 定义（类型安全）
    val KEY_PAGE_ERRORS = stringPreferencesKey("page_errors")   // 页面运行/网络错误历史 JSON（按站，统计页用）
    val KEY_APP_ERRORS = stringPreferencesKey("app_errors")     // 应用自身运行错误日志 JSON（全局，兜底写入）
    val KEY_STATS = stringPreferencesKey("stats")               // 统计明细 JSON（WebApp 统计字段快照）

    /**
     * 异步读取 String 值（Flow，损坏自动恢复为空串）。
     */
    fun stringFlow(context: Context, key: androidx.datastore.preferences.core.Preferences.Key<String>): Flow<String> =
        context.dataStore.data
            .catch { e ->
                // 读损坏/IO 异常：恢复为空（不崩溃），下次写入自动重建
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .map { prefs -> prefs[key] ?: "" }

    /**
     * 同步读取 String 值（协程内调用，阻塞当前协程但非主线程）。
     */
    suspend fun readString(context: Context, key: androidx.datastore.preferences.core.Preferences.Key<String>): String =
        stringFlow(context, key).first()

    /**
     * 异步写入 String 值（事务性 updateData，原子读改写）。
     */
    suspend fun writeString(context: Context, key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        context.dataStore.edit { prefs ->
            prefs[key] = value
        }
    }
}
