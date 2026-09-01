package com.cylonid.nativealpha.util

/**
 * 站点健康登记（会话内内存态，借鉴 happier presence「活跃/失活」语义）：
 * 宿主/矩阵共用的 SiteWebViewClient 基类单点回写——页面成功 finished 记
 * 健康、主帧失败记失联；主列表卡片与矩阵选站面板读取展示「最近加载失败」。
 *
 * 设计取舍：
 * - 只存会话内结果（Boolean?，null=本会话未观测）——不持久化：历史失败
 *   不能代表站点当下状态（统计学口径 statLastError 已覆盖持久层），
 *   进程重启清零是诚实语义而非缺陷
 * - 纯 Kotlin 无 Android 依赖，可单测
 */
internal object SiteHealthRegistry {

    private val healthyBySite = HashMap<Int, Boolean>()

    fun markSuccess(webappId: Int) {
        healthyBySite[webappId] = true
    }

    fun markFailure(webappId: Int) {
        healthyBySite[webappId] = false
    }

    /** null=本会话未观测到该站加载结果 */
    fun statusOf(webappId: Int): Boolean? = healthyBySite[webappId]

    fun forget(webappId: Int) {
        healthyBySite.remove(webappId)
    }
}
