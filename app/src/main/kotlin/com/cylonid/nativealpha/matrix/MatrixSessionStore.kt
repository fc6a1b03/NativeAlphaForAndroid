package com.cylonid.nativealpha.matrix

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
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

    /**
     * 每窗边际成本（字节，设备端 EMA 校准值）。独立 key 不入
     * MatrixSessionState 模型（规格 §4.2 字段集保持不动）——QB 入口预检
     * 依赖跨会话的实测边际，否则进程重启清零、预检永远 fail-open 放行。
     */
    private val KEY_PER_CELL_BYTES = longPreferencesKey("matrix_per_cell_bytes")

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

    /** 读边际成本（≤0=无历史，闸门 fail-open 放行） */
    suspend fun readPerCellBytes(context: Context): Long =
        context.matrixDataStore.data
            .catch { e ->
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .map { prefs -> prefs[KEY_PER_CELL_BYTES] ?: 0L }
            .first()

    /** 写边际成本（onPageFinished 回采校准后调用；协程内） */
    suspend fun writePerCellBytes(context: Context, value: Long) {
        context.matrixDataStore.edit { prefs ->
            prefs[KEY_PER_CELL_BYTES] = value
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
