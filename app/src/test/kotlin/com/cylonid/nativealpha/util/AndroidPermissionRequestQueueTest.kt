package com.cylonid.nativealpha.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 运行时权限请求全局队列单测（C6）：同组 join 批量分发 / 异组 FIFO 串行 /
 * retire 清本通道 / 孤儿超时自愈。纯逻辑对象（lambda 注入）JVM 直测。
 *
 * 语义速记：enqueue 的 fire=「发起系统请求」的动作（其第二参是结果回调，
 * fire 不调用它=请求保持在途）；onResult=主请求的结果回调（settle 时分发）。
 */
class AndroidPermissionRequestQueueTest {

    private val channelA = Any()
    private val channelB = Any()

    private val CAMERA = listOf(android.Manifest.permission.CAMERA)
    private val MIC = listOf(android.Manifest.permission.RECORD_AUDIO)

    private var fired = 0
    private var lastFired: List<String>? = null
    /** 保持在途的 fire 通道所捕获的结果回调（模拟系统框未返回结果） */
    private var heldResult: ((Boolean) -> Unit)? = null

    @Before
    fun reset() {
        // 对象单例——retire 双通道复位状态；空队列下 retire 是无操作
        AndroidPermissionRequestQueue.retire(channelA)
        AndroidPermissionRequestQueue.retire(channelB)
        fired = 0
        lastFired = null
        heldResult = null
        assertTrue(AndroidPermissionRequestQueue.isIdle())
    }

    /** 同步回调型通道：fire 即回调结果（settle 同步路径） */
    private fun fireSync(): (List<String>, (Boolean) -> Unit) -> Unit =
        { permissions, onResult ->
            fired++
            lastFired = permissions
            onResult(true)
        }

    /** 保持在途型通道：fire 只发起不回调（模拟系统框等待用户操作） */
    private fun fireHolding(): (List<String>, (Boolean) -> Unit) -> Unit =
        { permissions, onResult ->
            fired++
            lastFired = permissions
            heldResult = onResult
        }

    @Test
    fun `idle enqueue fires immediately`() {
        var result: Boolean? = null
        AndroidPermissionRequestQueue.enqueue(CAMERA, channelA, fireSync(), onResult = { result = it })
        assertEquals(1, fired)
        assertEquals(true, result)
        assertTrue(AndroidPermissionRequestQueue.isIdle())
    }

    @Test
    fun `same group later request joins and shares result`() {
        var primaryResult: Boolean? = null
        var joinerResult: Boolean? = null
        // A 相机在途（通道保持在途）
        AndroidPermissionRequestQueue.enqueue(CAMERA, channelA, fireHolding(), onResult = { primaryResult = it })
        // B 相机同组：join，不新发系统请求
        AndroidPermissionRequestQueue.enqueue(CAMERA, channelB, fireSync(), onResult = { joinerResult = it })
        assertEquals(1, fired)
        assertTrue(heldResult != null)
        // A 的系统结果到达：primary 与 joiner 分发同一结果
        heldResult?.invoke(true)
        assertEquals(true, primaryResult)
        assertEquals(true, joinerResult)
        assertTrue(AndroidPermissionRequestQueue.isIdle())
    }


    @Test
    fun `same group denial distributes to joiner as well`() {
        var primaryResult: Boolean? = null
        var joinerResult: Boolean? = null
        AndroidPermissionRequestQueue.enqueue(CAMERA, channelA, fireHolding(), onResult = { primaryResult = it })
        AndroidPermissionRequestQueue.enqueue(CAMERA, channelB, fireSync(), onResult = { joinerResult = it })
        heldResult?.invoke(false)
        assertEquals(false, primaryResult)
        assertEquals(false, joinerResult)
    }

    @Test
    fun `different group queues fifo and fires after settle`() {
        var micResult: Boolean? = null
        AndroidPermissionRequestQueue.enqueue(CAMERA, channelA, fireHolding(), onResult = { })
        // B 麦克风异组：排队不发起
        AndroidPermissionRequestQueue.enqueue(MIC, channelB, fireSync(), onResult = { micResult = it })
        assertEquals(1, fired)
        assertEquals(CAMERA, lastFired)
        // A settle → B 出队发起（第二次系统框）并拿到自己的结果
        heldResult?.invoke(false)
        assertEquals(2, fired)
        assertEquals(MIC, lastFired)
        assertEquals(true, micResult)
        assertTrue(AndroidPermissionRequestQueue.isIdle())
    }

    @Test
    fun `retire denies in-flight of that channel only and queue takes over`() {
        var aResult: Boolean? = null
        var micResult: Boolean? = null
        AndroidPermissionRequestQueue.enqueue(CAMERA, channelA, fireHolding(), onResult = { aResult = it })
        AndroidPermissionRequestQueue.enqueue(MIC, channelB, fireSync(), onResult = { micResult = it })
        AndroidPermissionRequestQueue.retire(channelA)
        assertEquals(false, aResult)
        // A 被拒绝结算后 B 随即出队发起
        assertEquals(2, fired)
        assertEquals(MIC, lastFired)
        assertEquals(true, micResult)
    }

    @Test
    fun `retire keeps other channel requests untouched`() {
        AndroidPermissionRequestQueue.enqueue(CAMERA, channelA, fireHolding(), onResult = { })
        AndroidPermissionRequestQueue.retire(channelB)
        // channelB 无在途无排队：不影响 A 的在途
        assertEquals(1, fired)
        heldResult?.invoke(true)
        assertEquals(1, fired)
    }

    @Test
    fun `orphan in-flight is denied and later request takes over`() {
        var orphanResult: Boolean? = null
        var laterResult: Boolean? = null
        // 孤儿形态：fire 已发起但结果回调永不再来（通道已死）
        AndroidPermissionRequestQueue.enqueue(CAMERA, channelA, fireHolding(), onResult = { orphanResult = it })
        // 60s+ 后 B 入队：孤儿被拒绝结算，B 直接接管发起
        AndroidPermissionRequestQueue.enqueue(
            MIC, channelB, fireSync(),
            onResult = { laterResult = it },
            nowMs = System.currentTimeMillis() + AndroidPermissionRequestQueue.ORPHAN_TIMEOUT_MS + 1
        )
        assertEquals(false, orphanResult)
        // fired=2：A 孤儿发起 1 次 + B 自愈后接管发起 1 次
        assertEquals(2, fired)
        assertEquals(MIC, lastFired)
        assertEquals(true, laterResult)
    }

    @Test
    fun `orphan cleanup drains queue head first for fifo fairness`() {
        var orphanResult: Boolean? = null
        var queuedMicResult: Boolean? = null
        AndroidPermissionRequestQueue.enqueue(CAMERA, channelA, fireHolding(), onResult = { orphanResult = it })
        // C 麦克风在孤儿期间排队
        AndroidPermissionRequestQueue.enqueue(
            MIC, channelB, fireSync(),
            onResult = { queuedMicResult = it },
            nowMs = System.currentTimeMillis() + 1000
        )
        // 超窗的新请求入队：孤儿结算 → 队首 C 先接管 → 新请求与 C 比较
        AndroidPermissionRequestQueue.enqueue(
            CAMERA, channelB, fireSync(),
            onResult = { },
            nowMs = System.currentTimeMillis() + AndroidPermissionRequestQueue.ORPHAN_TIMEOUT_MS + 1
        )
        assertEquals(false, orphanResult)
        assertEquals(true, queuedMicResult)
    }

}
