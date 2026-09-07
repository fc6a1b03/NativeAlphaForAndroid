package com.cylonid.nativealpha.helper

import android.app.Activity
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.cylonid.nativealpha.R
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest

/**
 * Web 权限授权编排（宿主/矩阵唯一同源实现，v2.3.12 重构）。
 *
 * 三层语义：站点记忆（WebApp 字段持久化）→ 弹窗确认 → Android 运行时权限，
 * 授权动作统一在链路终结点执行（grant/deny 恰好一次）。旧宿主实现在
 * onPermissionRequest 同步尾部无条件 grant，弹窗/系统回调晚于它——「记忆后
 * 整页 reload」正是给该坏链路打的补丁（首次弹窗授权后重载页面二次请求），
 * 本类将授权移入异步终结点后 reload 不再需要。
 *
 * 依赖倒置：grant/deny/记忆读写/系统权限请求均由调用方注入——宿主与矩阵
 * 只提供 Activity 级通道，编排逻辑无双份漂移；注入接口化后编排可用
 * Robolectric 单测覆盖（PermissionRequest 平台类不可直接构造）。
 *
 * 永久拒绝（勾选"不再询问"）：记忆=已请求过 + shouldShowRequestPermissionRationale
 * =false → 不再弹系统框，引导去系统设置（请求历史由本类内部维护）。
 *
 * 弹窗并发：多资源同时请求时逐个弹窗，未决计数归零后一次性 settle。
 */
internal class WebPermissionCoordinator(
    private val activity: Activity,
    /** 站点记忆读取 */
    private val readMemory: () -> WebPermissionMemory,
    /** 站点记忆写回（按变更字段标识；宿主/矩阵各自落持久化） */
    private val writeMemory: (field: MemoryField, memory: WebPermissionMemory) -> Unit,
    /** Android 运行时权限异步请求（宿主/矩阵各自 Activity 级通道） */
    private val requestAndroidPermissions: (permissions: List<String>, onResult: (Boolean) -> Unit) -> Unit,
    /** 站点确认弹窗（注入化：Robolectric 单测注入 fake 触发决策） */
    private val showSiteDialog: (titleRes: Int, descRes: Int, onAllow: () -> Unit, onDeny: () -> Unit) -> Unit =
        { titleRes, descRes, onAllow, onDeny ->
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(titleRes))
                .setMessage(activity.getString(descRes))
                .setPositiveButton(R.string.yes) { _, _ -> onAllow() }
                .setNegativeButton(R.string.no) { _, _ -> onDeny() }
                .setOnCancelListener { onDeny() }
                .create()
                .show()
        },
    /** 永久拒绝引导弹窗（注入化同上） */
    private val showPermanentlyDeniedDialogImpl: (titleRes: Int) -> Unit = { titleRes ->
        val title = activity.getString(titleRes)
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(activity.getString(R.string.permission_permanently_denied_msg, title))
            .setPositiveButton(activity.getString(R.string.permission_go_to_settings)) { _, _ ->
                try {
                    activity.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            ("package:" + activity.packageName).let(android.net.Uri::parse)
                        )
                    )
                } catch (ignored: Exception) {
                    // 无设置页可跳时静默（不影响主功能）
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .show()
    }
) {

    /** 记忆变更字段标识（写回方按此落对应 WebApp 字段） */
    enum class MemoryField { DRM, CAMERA, MICROPHONE, LOCATION }

    /** 站点级权限记忆快照 */
    data class WebPermissionMemory(
        val drm: Boolean,
        val camera: Boolean,
        val microphone: Boolean,
        val location: Boolean
    )

    /** 请求过的运行时权限（永久拒绝判定的历史依据） */
    private val requestedHistory = mutableSetOf<String>()

    /** 资源常量（webkit 级） */
    companion object {
        private const val RESOURCE_DRM = PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        private const val RESOURCE_CAMERA = PermissionRequest.RESOURCE_VIDEO_CAPTURE
        private const val RESOURCE_MICROPHONE = PermissionRequest.RESOURCE_AUDIO_CAPTURE

        private val CAMERA_ANDROID = listOf(android.Manifest.permission.CAMERA)
        private val MICROPHONE_ANDROID = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        private val LOCATION_ANDROID = listOf(
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    // ===== WebView 权限请求编排 =====

    /**
     * 处理 onPermissionRequest：按资源分流编排，grant/deny 恰好一次。
     * @param resources 站点请求的 webkit 资源列表
     * @param grant 授予动作（宿主/矩阵各自桥接 PermissionRequest）
     * @param deny 拒绝动作
     */
    fun handleWebPermission(
        resources: List<String>,
        grant: (List<String>) -> Unit,
        deny: () -> Unit
    ) {
        var pending = 0
        var denied = false
        val grantList = mutableListOf<String>()
        fun settle() {
            if (pending > 0) return
            if (grantList.isEmpty()) deny() else grant(grantList)
        }
        fun decide(
            resource: String,
            remembered: Boolean,
            androidPermissions: List<String>,
            titleRes: Int,
            descRes: Int,
            onRemembered: () -> Unit
        ) {
            if (remembered && androidPermissions.all {
                    ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
                }) {
                grantList += resource
                return
            }
            if (remembered && isPermanentlyDenied(androidPermissions)) {
                denied = true
                showPermanentlyDeniedDialog(titleRes)
                return
            }
            pending++
            if (remembered) {
                // 站点已记忆但系统权限被收回：直接补系统请求（不再弹站点确认）
                requestAndroid(androidPermissions) { ok ->
                    if (ok) grantList += resource else denied = true
                    pending--
                    settle()
                }
                return
            }
            askSitePermission(titleRes, descRes,
                onAllow = {
                    onRemembered()
                    if (androidPermissions.all {
                            ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
                        }) {
                        grantList += resource
                        pending--
                        settle()
                    } else {
                        requestAndroid(androidPermissions) { ok ->
                            if (ok) grantList += resource else denied = true
                            pending--
                            settle()
                        }
                    }
                },
                onDeny = { denied = true; pending--; settle() }
            )
        }
        val m = readMemory()
        if (RESOURCE_DRM in resources) {
            decide(RESOURCE_DRM, m.drm, emptyList(),
                R.string.allow_drm_content, R.string.desc_allow_drm
            ) { writeMemory(MemoryField.DRM, m.copy(drm = true)) }
        }
        if (RESOURCE_CAMERA in resources) {
            decide(RESOURCE_CAMERA, m.camera, CAMERA_ANDROID,
                R.string.allow_camera_access, R.string.desc_allow_camera
            ) { writeMemory(MemoryField.CAMERA, m.copy(camera = true)) }
        }
        if (RESOURCE_MICROPHONE in resources) {
            decide(RESOURCE_MICROPHONE, m.microphone, MICROPHONE_ANDROID,
                R.string.allow_microphone_access, R.string.desc_allow_microphone
            ) { writeMemory(MemoryField.MICROPHONE, m.copy(microphone = true)) }
        }
        if (pending == 0) settle()
    }

    /**
     * 地理定位授权编排：记忆=直接 invoke(true)；未记忆弹窗确认后写记忆并
     * 确保 Android 定位权限，结果经 callback 回给内核。
     */
    fun handleGeolocation(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        val m = readMemory()
        if (m.location && LOCATION_ANDROID.all {
                ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
            }) {
            callback.invoke(origin, true, false)
            return
        }
        askSitePermission(R.string.allow_location_access, R.string.desc_allow_location,
            onAllow = {
                writeMemory(MemoryField.LOCATION, m.copy(location = true))
                if (LOCATION_ANDROID.all {
                        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
                    }) {
                    callback.invoke(origin, true, false)
                } else {
                    requestAndroid(LOCATION_ANDROID) { ok ->
                        callback.invoke(origin, ok, false)
                    }
                }
            },
            onDeny = { callback.invoke(origin, false, false) }
        )
    }

    // ===== 内部构件 =====

    private fun requestAndroid(permissions: List<String>, onResult: (Boolean) -> Unit) {
        requestedHistory.addAll(permissions)
        requestAndroidPermissions(permissions) { granted ->
            onResult(granted)
        }
    }

    private fun isPermanentlyDenied(permissions: List<String>): Boolean {
        val allRequested = permissions.all { it in requestedHistory }
        if (!allRequested) return false
        return permissions.any {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED &&
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
    }

    private fun askSitePermission(titleRes: Int, descRes: Int, onAllow: () -> Unit, onDeny: () -> Unit) =
        showSiteDialog(titleRes, descRes, onAllow, onDeny)

    private fun showPermanentlyDeniedDialog(titleRes: Int) = showPermanentlyDeniedDialogImpl(titleRes)
}
