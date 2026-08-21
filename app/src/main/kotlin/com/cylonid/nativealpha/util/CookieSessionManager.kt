package com.cylonid.nativealpha.util

import android.content.Context
import android.webkit.CookieManager
import com.cylonid.nativealpha.model.DataManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 登录态隔离（Cookie 会话管理）。
 *
 * 原理：Android WebView 的 Cookie 是应用级单例（无法原生按实例隔离），
 * 通过「切换时保存当前站 Cookie → 清除 → 恢复目标站 Cookie」实现伪隔离。
 *
 * 设计：
 * - 每 WebApp 一个 Cookie 快照（DataStore KEY_COOKIE_SNAPSHOTS 存储）
 * - 开启 isIsolatedSession 的 WebApp：打开时恢复自己的 Cookie，关闭时保存
 * - 未开启：走全局 Cookie（现状）
 * - 操作异步（IO 协程），不影响主应用
 */
object CookieSessionManager {

    private val gson = Gson()

    /** Cookie 快照（key=webappId，value=该站全部 Cookie 字符串） */
    private data class CookieSnapshots(val snapshots: Map<Int, String> = emptyMap())

    /**
     * 保存指定 WebApp 的当前 Cookie 快照（异步）。
     * 调用时机：开启隔离的 WebApp 关闭/切换时。
     */
    fun saveSnapshot(context: Context, webappId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val webapp = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappId, true) ?: return@launch
                if (!webapp.isIsolatedSession) return@launch
                // 收集该站全部 Cookie（所有域名下属于该站的）
                val allCookies = CookieManager.getInstance().getCookie("https://" + hostOf(webapp.baseUrl))
                    ?: ""
                if (allCookies.isEmpty()) return@launch
                val current = loadSnapshots(context)
                val updated = current.snapshots + (webappId to allCookies)
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
     * 恢复指定 WebApp 的 Cookie 快照（异步），并清除其他隔离站的 Cookie。
     * 调用时机：开启隔离的 WebApp 打开时。
     */
    fun restoreSnapshot(context: Context, webappId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val webapp = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappId, true) ?: return@launch
                if (!webapp.isIsolatedSession) return@launch
                val snapshots = loadSnapshots(context)
                val snapshot = snapshots.snapshots[webappId] ?: return@launch
                // 清除全部 Cookie（防串站），再恢复目标站
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                // 恢复快照（按域名 setCookie——快照是单域名，直接 set）
                val host = hostOf(webapp.baseUrl)
                snapshot.split(";").forEach { cookie ->
                    val c = cookie.trim()
                    if (c.isNotEmpty()) {
                        CookieManager.getInstance().setCookie("https://" + host, c)
                    }
                }
                CookieManager.getInstance().flush()
            } catch (e: Exception) {
                // 恢复失败静默（保持全局 Cookie）
            }
        }
    }

    /** 清除指定 WebApp 的快照（用户关闭隔离时） */
    fun clearSnapshot(context: Context, webappId: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val current = loadSnapshots(context)
                val updated = current.snapshots - webappId
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

    /** 从 URL 提取 host */
    private fun hostOf(url: String): String {
        return try {
            java.net.URI(url).host ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
