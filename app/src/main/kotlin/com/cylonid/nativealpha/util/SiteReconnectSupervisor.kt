package com.cylonid.nativealpha.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import java.net.HttpURLConnection
import java.net.URL

/**
 * 站点断线自动恢复监督者（借鉴 happier connection-supervisor 的治理模型：
 * 先探测再恢复、指数退避、网络可用事件即时触发，而非盲目定时 reload）。
 *
 * 生命周期：加载失败（主帧 onReceivedError / 矩阵格失败态）时 [start]，
 * 页面加载成功或宿主销毁时 [stop]。恢复动作（onReachable）由实现方注入
 * ——宿主 reload retryUrl、矩阵 pickSite 走完整闸门链路。
 *
 * 可测性：probe 函数注入（默认 HttpURLConnection HEAD），唤醒走 CONFLATED
 * channel（虚拟时间下 withTimeoutOrNull/receive 均可推进），退避与判定为
 * 纯函数（[backoffDelayMs]/[isReachableCode]）。
 */
internal class SiteReconnectSupervisor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val probeFn: suspend (String) -> Int = ::defaultProbe
) {

    /** 监视中的目标 URL（探测目标=站点 baseUrl，恢复动作由实现方决定） */
    private var targetUrl: String? = null

    private var attempt = 0

    private var loopJob: Job? = null

    /** 立即探测信号（网络恢复事件；CONFLATED 合并连续信号且立即返回） */
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 探测可达时回调（实现方在主线程执行恢复动作） */
    var onReachable: ((url: String) -> Unit)? = null

    /**
     * 开始监视：立即做首轮探测，随后按指数退避周期重试；
     * 系统网络恢复事件（onAvailable）会即时插队探测。
     */
    fun start(url: String, onReachable: (String) -> Unit) {
        stop()
        targetUrl = url
        attempt = 0
        this.onReachable = onReachable
        registerNetworkWatch()
        loopJob = scope.launch { probeLoop(url) }
    }

    /** 网络可用事件插队：发信号中断退避等待，立即进入下一轮探测 */
    fun notifyNetworkAvailable() {
        if (targetUrl == null) return
        wakeSignal.trySend(Unit)
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        targetUrl = null
        onReachable = null
        wakeSignal.tryReceive()
        unregisterNetworkWatch()
    }

    private suspend fun probeLoop(url: String) {
        while (true) {
            val code = probeFn(url)
            if (isReachableCode(code)) {
                val cb = onReachable
                stop()
                cb?.invoke(url)
                return
            }
            attempt++
            // 退避等待可被网络恢复信号打断（receive 挂起，超时则按退避重试）
            withTimeoutOrNull(backoffDelayMs(attempt)) { wakeSignal.receive() }
        }
    }

    private fun registerNetworkWatch() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                notifyNetworkAvailable()
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (_: Exception) {
            // 注册失败（极端系统态）：退化为纯周期探测
        }
    }

    private fun unregisterNetworkWatch() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        networkCallback?.let { callback ->
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
                // 未注册/已释放：静默
            }
        }
        networkCallback = null
    }

    companion object {
        private const val BASE_DELAY_MS = 5_000L
        private const val MAX_DELAY_MS = 60_000L

        /** 指数退避：5s → 10s → 20s → 40s → 60s（封顶） */
        internal fun backoffDelayMs(attempt: Int): Long {
            if (attempt <= 0) return BASE_DELAY_MS
            val shifted = BASE_DELAY_MS shl (attempt - 1).coerceAtMost(10)
            return shifted.coerceAtMost(MAX_DELAY_MS)
        }

        /** 有 HTTP 响应即站点可达（401/403/404/405 说明服务活着，与可达性解耦） */
        internal fun isReachableCode(code: Int): Boolean = code in 200..499

        /** 默认探测：HEAD 请求，任意异常（含超时/DNS 失败）返回 -1=不可达 */
        internal suspend fun defaultProbe(url: String): Int = withContext(Dispatchers.IO) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "HEAD"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.instanceFollowRedirects = true
                conn.useCaches = false
                val code = conn.responseCode
                conn.disconnect()
                code
            } catch (_: Exception) {
                -1
            }
        }
    }
}
