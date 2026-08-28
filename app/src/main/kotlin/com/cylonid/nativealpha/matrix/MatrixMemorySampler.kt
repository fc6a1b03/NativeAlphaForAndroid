package com.cylonid.nativealpha.matrix

import android.app.ActivityManager
import android.content.Context

/**
 * 内存采样器（P4 D7 设备侧，薄封装）。
 *
 * 系统级读数来源：
 * - 渲染进程 PSS：本应用 `:sandboxed_processN` 进程族 totalPss 求和
 *   （共享渲染进程即全部 WebView 渲染内存所在；系统对采样有秒级缓存，
 *   接受粗值——校准是渐进式的，粗值不破坏 fail-open）
 * - 整机可用内存/低内存阈值：ActivityManager.MemoryInfo
 *
 * 读数失败（ROM 阉割/无进程可见）返回无效值，由闸门 fail-open 放行。
 */
internal object MatrixMemorySampler {

    /** 渲染进程 PSS 总和（字节）；读不到返回 -1 */
    fun rendererPssBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return -1
        val pkgPrefix = context.packageName + ":sandboxed"
        val pids = am.runningAppProcesses
            ?.filter { it.processName.startsWith(pkgPrefix) }
            ?.map { it.pid }
            ?.takeIf { it.isNotEmpty() }
            ?: return -1
        return am.getProcessMemoryInfo(pids.toIntArray())
            .sumOf { it.totalPss.toLong() * 1024 }
    }

    /** 整机可用内存（字节）；读不到返回 -1 */
    fun availMemBytes(context: Context): Long = memoryInfo(context)?.availMem ?: -1

    /** 系统低内存阈值（字节，availMem 低于它即临界）；读不到返回 -1 */
    fun lowMemoryThresholdBytes(context: Context): Long = memoryInfo(context)?.threshold ?: -1

    private fun memoryInfo(context: Context): ActivityManager.MemoryInfo? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    }
}
