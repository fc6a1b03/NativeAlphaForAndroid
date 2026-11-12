package com.cylonid.nativealpha.util

/**
 * Android 运行时权限请求全局队列（dsh-Go 借鉴 C6：MediaPermissionQueue 形态）。
 *
 * 解决的缺口：系统运行时权限对话框全局唯一，并发调用会互相顶掉——后发的
 * 系统框吃掉先发的那次调用，先发者的结果回调丢失、授权链路挂起。宿主/
 * 矩阵各格的 [WebPermissionCoordinator] 是独立实例互不知晓，跨格同时首次
 * 请求同一权限（两格都要相机）即触发顶掉。
 *
 * 形态（参照 dsh owner+waiters，推广到多权限组）：
 * - **同组 join**：在途请求与后来者存在权限交集 → 后来者加入等待者列表，
 *   共享在途请求的结果（一次系统框满足多方——权限是应用级状态，用户刚批
 *   的决策语义等价适用于后来者；denied 同理按拒绝分发）。
 * - **异组 FIFO**：无交集的后来者排队，在途 settle 后依次出队发起（串行化，
 *   每个请求保有自己的系统框与独立决策——不同权限组合并会让「这次弹的是
 *   相机+麦克风」令用户困惑）。
 * - **retire 防挂死**：通道（宿主/矩阵各自的请求通道 lambda）销毁时清走
 *   本通道的在途与排队请求（全部按拒绝结算）——Activity 销毁后旧 launcher
 *   的结果回调永不再来，不清会饿死后续所有请求。
 *
 * 线程模型：Web 权限编排链路在主线程回调（coordinator 注入通道均主线程），
 * 队列状态不跨线程，不加锁。
 *
 * 引用边界：channel/fire 捕获 Activity（发起系统请求必须经由发起方
 * launcher），本 object 为静态单例——属**有界持有**：在途 settle 即释放、
 * 排队项 settle/retire 即释放、孤儿残留由 [ORPHAN_TIMEOUT_MS] 自愈上限兜底
 * （≤60s），不构成「静态长生命周期持有 Activity」红线违例。
 */
internal object AndroidPermissionRequestQueue {

    /** 在途请求：主请求结果回调 + 同组 join 等待者（共享同一次系统请求的结果） */
    private class ActiveRequest(
        val permissions: List<String>,
        val channel: Any,
        val primary: (Boolean) -> Unit,
        val createdAtMs: Long,
        val joiners: MutableList<(Boolean) -> Unit> = mutableListOf()
    )

    /** 排队请求：异组后来者 FIFO（channel/fire/onResult 逐个携带，发起用入队者自己的通道） */
    private class PendingRequest(
        val permissions: List<String>,
        val channel: Any,
        val fire: (permissions: List<String>, onResult: (Boolean) -> Unit) -> Unit,
        val onResult: (Boolean) -> Unit
    )

    /**
     * 孤儿自愈窗口：在途请求超过此时长仍无结果回调 = 发起通道已死（Activity
     * 销毁后旧 launcher 的回调永不再来）——下一个入队者将其按拒绝结算并
     * 接管，防止单个孤儿饿死后续全部请求。系统权限框的用户操作极限远小于
     * 此值，正常路径永不触发。
     */
    internal const val ORPHAN_TIMEOUT_MS = 60_000L
    @Volatile
    private var inFlight: ActiveRequest? = null

    private val queued = ArrayDeque<PendingRequest>()


    /**
     * 入队：同组（与在途请求存在权限交集）join 共享结果；否则排队等待在途
     * 请求结算后按 FIFO 发起。空闲时立即发起。
     * @param channel 请求通道标识（调用方注入的请求通道引用——retire 按引用匹配）
     * @param fire 发起系统请求的动作（宿主/矩阵各自 Activity 级通道）
     */
    fun enqueue(
        permissions: List<String>,
        channel: Any,
        fire: (permissions: List<String>, onResult: (Boolean) -> Unit) -> Unit,
        onResult: (Boolean) -> Unit,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val active = inFlight
        if (active != null && nowMs - active.createdAtMs > ORPHAN_TIMEOUT_MS) {
            // 孤儿自愈：在途请求超窗无回调=通道已死，按拒绝结算并腾位；
            // 队首先接管（保持 FIFO 公平），新请求再与队首正常比较
            inFlight = null
            active.primary(false)
            active.joiners.forEach { it(false) }
            drainNext()
            return enqueue(permissions, channel, fire, onResult, nowMs)
        }
        if (active != null && sharesAnyGroup(active.permissions, permissions)) {
            active.joiners.add(onResult)
            return
        }
        if (active == null) {
            start(ActiveRequest(permissions, channel, onResult, nowMs), fire)
            return
        }
        queued.addLast(PendingRequest(permissions, channel, fire, onResult))
    }

    /**
     * 通道销毁（Activity onDestroy）：清走本通道的在途与排队请求（按拒绝
     * 结算，不再有结果回调）。其他通道的请求不受影响，队首可立即接管。
     */
    fun retire(channel: Any) {
        val active = inFlight
        if (active != null && active.channel === channel) {
            inFlight = null
            active.primary(false)
            active.joiners.forEach { it(false) }
            drainNext()
        }
        val mine = queued.filter { it.channel === channel }
        mine.forEach {
            queued.remove(it)
            it.onResult(false)
        }
    }

    /** 测试与诊断用：队列是否空闲（无在途且无排队） */
    internal fun isIdle(): Boolean = inFlight == null && queued.isEmpty()

    private fun start(active: ActiveRequest, fire: (permissions: List<String>, onResult: (Boolean) -> Unit) -> Unit) {
        inFlight = active
        // 结果路由回 settle：主回调/joiners 统一在 settle 分发（fire 是异步的
        // 系统权限对话框，回调必然晚于本帧返回）
        fire(active.permissions) { granted -> settle(granted) }
    }

    private fun settle(granted: Boolean) {
        val active = inFlight ?: return
        inFlight = null
        active.primary(granted)
        active.joiners.forEach { it(granted) }
        drainNext()
    }

    private fun drainNext() {
        val next = queued.removeFirstOrNull() ?: return
        start(ActiveRequest(next.permissions, next.channel, next.onResult, System.currentTimeMillis()), next.fire)
    }

    /** 权限组判定：与在途请求存在任一权限交集即视为同组（相机组/麦克风组/定位组各自成组） */
    private fun sharesAnyGroup(a: List<String>, b: List<String>): Boolean =
        a.any { it in b }
}
