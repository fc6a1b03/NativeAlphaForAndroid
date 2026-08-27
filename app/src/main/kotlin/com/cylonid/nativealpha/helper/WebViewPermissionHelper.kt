package com.cylonid.nativealpha.helper

import android.content.pm.PackageManager
import com.cylonid.nativealpha.WebViewActivity
import com.cylonid.nativealpha.util.Const

/**
 * 运行时权限分流处理器（v2.2.0 P3 第五刀，自 WebViewActivity 逐行迁移，零语义变更）。
 *
 * 职责：onRequestPermissionsResult 的授予/拒绝分流与 WebApp 权限位回写。
 *
 * 契约留守 Activity：onRequestPermissionsResult override（系统回调入口）、
 * enablePermissionBoolOnWebApp 与 PermissionGrantedCallback（WebViewChromeClient
 * 直接引用）、handleGeoPermissionCallback 与 mGeo 字段（WebViewChromeClient
 * 直接读写）——本类只承接纯分流逻辑。
 */
class WebViewPermissionHelper(private val activity: WebViewActivity) {

    /** 系统权限回调分流（grantResults 全授予判定在体内，原实现逐行对应） */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        var allGranted = grantResults.isNotEmpty()
        for (r in grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                allGranted = false
                break
            }
        }

        if (allGranted) {
            onPermissionsGranted(requestCode, permissions.toList())
        } else {
            onPermissionsDenied(requestCode, permissions.toList())
        }
    }

    private fun onPermissionsGranted(requestCode: Int, list: List<String>) {
        if (requestCode == Const.PERMISSION_RC_LOCATION) {
            activity.enablePermissionBoolOnWebApp { activity.webapp!!.isAllowLocationAccess = true }
            activity.handleGeoPermissionCallback(true)
        }
        if (requestCode == Const.PERMISSION_CAMERA) {
            activity.enablePermissionBoolOnWebApp { activity.webapp!!.isCameraPermission = true }
        }
    }

    private fun onPermissionsDenied(requestCode: Int, list: List<String>) {
        if (requestCode == Const.PERMISSION_RC_LOCATION) {
            activity.handleGeoPermissionCallback(false)
        }
    }
}
