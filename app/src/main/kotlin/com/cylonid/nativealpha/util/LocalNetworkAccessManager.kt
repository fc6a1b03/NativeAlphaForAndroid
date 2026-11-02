package com.cylonid.nativealpha.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 本地网络权限（Android 17 / API 37）唯一入口——能力唯一实现处，调用方只编排
 * （范式同 WebAppIconManager）。
 *
 * 背景：Android 17 起本地网络保护强制——target SDK ≥37 的应用访问私网/
 * localhost 流量默认被系统拦截，须声明并运行时请求 ACCESS_LOCAL_NETWORK
 * （NEARBY_DEVICES 组）。官方文档定谳「WebView 流量继承宿主 App 权限状态」，
 * 而用户添加 NAS/homelab/路由器等局域网站点是本应用核心场景，故按需声明 +
 * 场景化申请（参照 POST_NOTIFICATIONS 先例：仅在添加私网站点或加载失败
 * 引导时触发，非启动即弹）。
 *
 * 守卫纪律：所有判定/请求路径必须 SDK_INT ≥ 37——target 37 ≠ 设备 37
 * （Android 15/16 设备无此权限概念，官方明确 target <37 不得运行时请求）。
 */
internal object LocalNetworkAccessManager {

    /** Android 17（API 37）新增的本地网络运行时权限，NEARBY_DEVICES 组 */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** 错误页「授予本地网络权限」按钮的拦截地址（webnative://retry 同款机制） */
    const val GRANT_URL = "webnative://lna-grant"

    /** 权限是否已授予；API <37 无此权限概念，恒 true（不拦任何流量） */
    fun isPermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            ContextCompat.checkSelfPermission(context, PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 场景化判定：该 URL 是否需要引导申请本地网络权限
     * （设备 ≥37 且 URL 指向私网且未授权）。向导添加与错误页授权按钮共用。
     */
    fun needsPermission(context: Context, url: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            UrlUtils.isPrivateNetworkHost(url) &&
            !isPermissionGranted(context)
}
