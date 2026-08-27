package com.cylonid.nativealpha.util

import android.content.Context
import android.webkit.CookieManager
import com.cylonid.nativealpha.model.DataManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 登录态隔离（Cookie 会话管理）。
 *
 * 原理：Android WebView 的 Cookie 是应用级单例（无法原生按实例隔离），
 * 通过「切换时保存当前站+标签 Cookie → 清除 → 恢复目标站+标签 Cookie」实现伪隔离。
 *
 * 设计：
 * - 快照 key = 「webappId.tabIndex」（多标签会话隔离：同一 WebApp 多个会话独立 Cookie）
 * - 开启 isIsolatedSession 的 WebApp：打开时恢复自己的 Cookie，关闭时保存
 * - 未开启：走全局 Cookie（现状）
 * - 操作异步（IO 协程），不影响主应用
 */
object CookieSessionManager {

    private val gson = Gson()

    /** IO 作用域（SupervisorJob：单个快照操作失败不级联取消其他操作——风格统一 P2） */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cookie 快照（key=webappId.tabIndex，value=该标签 Cookie 字符串） */
    private data class CookieSnapshots(val snapshots: Map<String, String> = emptyMap())

    /** 快照 key：webappId + tabIndex */
    private fun snapshotKey(webappId: Int, tabIndex: Int): String = "$webappId.$tabIndex"

    /**
     * 保存指定 WebApp + Tab 的当前 Cookie 快照（异步）。
     * 调用时机：开启隔离的 WebApp 关闭/切换时。
     * @param tabIndex 多标签会话隔离：同一 WebApp 多个会话各自独立 Cookie
     */
    fun saveSnapshot(context: Context, webappId: Int, tabIndex: Int = 0) {
        ioScope.launch {
            try {
                val webapp = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappId, true) ?: return@launch
                if (!webapp.isIsolatedSession) return@launch
                // 收集该站全部 Cookie（所有域名下属于该站的）
                val allCookies = CookieManager.getInstance().getCookie("https://" + hostOf(webapp.baseUrl))
                    ?: ""
                if (allCookies.isEmpty()) return@launch
                val current = loadSnapshots(context)
                val updated = current.snapshots + (snapshotKey(webappId, tabIndex) to allCookies)
                AppStorage.writeString(
                    context, AppStorage.KEY_COOKIE_SNAPSHOTS,
                    gson.toJson(CookieSnapshots(updated))
                )
            } catch (e: Exception) {
                // 快照失败静默（不影响主功能）
            }
        }
    }

    /**
     * 恢复指定 WebApp + Tab 的 Cookie 快照（异步），并清除其他隔离站的 Cookie。
     * 调用时机：开启隔离的 WebApp 打开时。
     * @param tabIndex 多标签会话隔离：恢复对应标签的独立 Cookie
     * @param onRestored 恢复完成回调（主线程）：调用方应在 cookie 就绪后再
     * loadUrl——否则页面首批请求带着「清空后未恢复」的 Cookie 发出，
     * 登录态偶发丢失的根因（loadUrl 与本异步恢复的时序竞争）
     */
    fun restoreSnapshot(
        context: Context,
        webappId: Int,
        tabIndex: Int = 0,
        onRestored: (() -> Unit)? = null
    ) {
        ioScope.launch {
            try {
                val webapp = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappId, true)
                if (webapp == null || !webapp.isIsolatedSession) {
                    // 无站点/未开隔离：无需恢复
                } else {
                    val snapshots = loadSnapshots(context)
                    val snapshot = snapshots.snapshots[snapshotKey(webappId, tabIndex)]
                    // 清除全部 Cookie（防串站），再恢复目标站（无快照则只清）
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    snapshot?.let { snap ->
                        val host = hostOf(webapp.baseUrl)
                        snap.split(";").forEach { cookie ->
                            val c = cookie.trim()
                            if (c.isNotEmpty()) {
                                CookieManager.getInstance().setCookie("https://" + host, c)
                            }
                        }
                        CookieManager.getInstance().flush()
                    }
                }
            } catch (e: Exception) {
                // 恢复失败静默（保持全局 Cookie）——仍放行页面加载
            }
            // 所有路径（含异常）都放行：页面加载不被 cookie 恢复失败卡死
            onRestored?.let { cb -> withContext(Dispatchers.Main) { cb() } }
        }
    }

    /** 清除指定 WebApp 的所有标签快照（用户关闭隔离时） */
    fun clearSnapshot(context: Context, webappId: Int) {
        ioScope.launch {
            try {
                val current = loadSnapshots(context)
                val prefix = "$webappId."
                val updated = current.snapshots.filterKeys { !it.startsWith(prefix) }
                AppStorage.writeString(
                    context, AppStorage.KEY_COOKIE_SNAPSHOTS,
                    gson.toJson(CookieSnapshots(updated))
                )
            } catch (e: Exception) {
                // 静默
            }
        }
    }

    private suspend fun loadSnapshots(context: Context): CookieSnapshots {
        return try {
            val json = AppStorage.readString(context, AppStorage.KEY_COOKIE_SNAPSHOTS)
            if (json.isEmpty()) CookieSnapshots()
            else gson.fromJson(json, object : TypeToken<CookieSnapshots>() {}.type) ?: CookieSnapshots()
        } catch (e: Exception) {
            CookieSnapshots()
        }
    }

    /** 从 URL 提取 host（失败空串）——统一实现走 UrlUtils.hostOf */
    private fun hostOf(url: String): String = UrlUtils.hostOf(url) ?: ""
}
