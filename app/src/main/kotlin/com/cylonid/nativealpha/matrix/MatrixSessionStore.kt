package com.cylonid.nativealpha.matrix

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.JsonParseException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 多窗矩阵会话存储（P4，规格 §4.2「持久化自持」）。
 *
 * 自持 DataStore `matrix_session`：独立于宿主 AppStorage 文件，不参与
 * 备份导入导出（矩阵布局是设备本地体验，跨设备迁移无意义）。
 *
 * 读写纪律（线程/UI 纪律决策）：
 * - 全异步 Flow，写走 updateData 事务（原子读改写）
 * - 读损坏自动恢复默认值（IO 异常/JSON 损坏均不崩溃、不阻塞 UI）
 * - 调用方在矩阵窗口变更时写入 + onStop 兜底（时机由 UI 层编排）
 */
internal object MatrixSessionStore {

    /** DataStore 单例（顶层属性，官方强制同文件单进程一实例） */
    private val Context.matrixDataStore by preferencesDataStore(name = "matrix_session")

    private val KEY_SESSION = stringPreferencesKey("matrix_session_json")

    private val gson = com.google.gson.Gson()

    /** 会话状态流（损坏自动恢复默认值） */
    fun sessionFlow(context: Context): Flow<MatrixSessionState> =
        context.matrixDataStore.data
            .catch { e ->
                // 读损坏/IO 异常：恢复为空（不崩溃），下次写入自动重建
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .map { prefs -> decodeSafe(prefs[KEY_SESSION]) }

    /** 同步读取（协程内调用；窗口变更即写之外的恢复路径用） */
    suspend fun read(context: Context): MatrixSessionState =
        sessionFlow(context).first()

    /** 写入（窗口变更即写 + onStop 兜底；事务性原子更新） */
    suspend fun write(context: Context, state: MatrixSessionState) {
        context.matrixDataStore.edit { prefs ->
            prefs[KEY_SESSION] = gson.toJson(state)
        }
    }

    /** JSON 解码统一兜底：空串/损坏 → 默认值（fail-safe） */
    private fun decodeSafe(json: String?): MatrixSessionState {
        if (json.isNullOrEmpty()) return MatrixSessionState()
        return try {
            gson.fromJson(json, MatrixSessionState::class.java) ?: MatrixSessionState()
        } catch (ignored: JsonParseException) {
            MatrixSessionState()
        }
    }
}
