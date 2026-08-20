package com.cylonid.nativealpha.model

import android.app.Activity
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.ShortcutIconUtils
import com.cylonid.nativealpha.util.Utility
import java.util.*

data class WebApp(var baseUrl: String, val ID: Int) {
    var title: String
    /** 用户自定义显示名称；null 时列表/快捷方式回退用 title */
    var displayName: String? = null
    var isActiveEntry = true
    var isOverrideGlobalSettings = true

    var isOpenUrlExternal = false
    var isAllowCookies = true
    var isAllowThirdPartyCookies = false
    var isRestorePage = false
    var isAllowJs = true
    var isRequestDesktop = false
    var isClearCache = false
    var isSendSavedataRequest = false
    var isBlockImages = false
    var isAllowHttp = false
    var isAllowLocationAccess = false
    /** 字体缩放（50~200，100=默认）。Gson 反序列化旧数据可能为 0，getter 归一化 */
    var textZoom: Int = 100
        get() = if (field in 50..200) field else 100
    /** 页面缩放（50~200，100=默认）。Gson 反序列化旧数据可能为 0，getter 归一化 */
    var pageZoom: Int = 100
        get() = if (field in 50..200) field else 100
    var userAgent: String? = null
    var isUseCustomUserAgent = false
    var isAutoreload = false
    var timeAutoreload = 0
    var isForceDarkMode = false
    var isUseTimespanDarkMode = false
    var timespanDarkModeBegin: String? = "22:00"
    var timespanDarkModeEnd: String? = "06:00"
    var isIgnoreSslErrors = false
    var isShowExpertSettings = false
    var isSafeBrowsing = false
    /** 安全加固：禁用文件访问（true=setAllowFileAccess(false)，默认防护开） */
    var isFileAccessDisabled = true
    /** 安全加固：禁用内容提供器访问（true=setAllowContentAccess(false)，默认防护开） */
    var isContentAccessDisabled = true
    /** 安全加固：拦截混合内容（true=MIXED_CONTENT_NEVER_ALLOW，默认防护开） */
    var isMixedContentBlocked = true
    /** 安全加固：限制 JS 自动弹窗（true=setJavaScriptCanOpenWindowsAutomatically(false)，默认防护开） */
    var isJsPopupsRestricted = true
    var isBlockThirdPartyRequests = false
    var containerId: Int = Const.NO_CONTAINER
    var isUseContainer = false
    var isDrmAllowed = false
    var isShowFullscreen = false
    var isKeepAwake = false
    var isCameraPermission = false
    var isMicrophonePermission = false
    var isEnableZooming = false
    var isBiometricProtection = false
    var isAllowMediaPlaybackInBackground = false
    var order = 0
    var alwaysUseFallbackContextMenu = false

    // ===== 组合快捷键（页面独有快捷键发送，每站独立，不参与 copySettings 合并） =====
    // 格式："Ctrl+S" / "Shift+S" / "Ctrl+Shift+S"（Ctrl/Shift/Alt + 字母/数字/功能键）
    var keyShortcuts: MutableList<String> = mutableListOf()

    // ===== 统计字段（按 WebApp 独立，不参与 copySettings 合并） =====
    var statLaunches: Int = 0        // 打开次数
    var statLoadTimeSum: Long = 0    // 主体加载耗时累计 ms
    var statLoadTimeCount: Int = 0   // 加载次数（均值）
    var statMaxLoadTime: Long = 0    // 最慢加载 ms
    var statCacheHttpBytes: Long = 0 // HTTP 缓存占用（cacheDir）
    var statCacheStoreBytes: Long = 0// 站点存储（WebStorage）
    var statErrors: Int = 0          // 页面错误计数
    var statLastError: String? = null// 最近页面错误描述
    var statFirstLoadedAt: Long = 0  // 首次使用（0 = 未使用，展示时处理）
    var statLastUsedAt: Long = 0     // 最近使用

    init {
        title = baseUrl.replace("http://", "").replace("https://", "").replace("www.", "")
        initDefaultSettings()
    }

    constructor(baseUrl: String, ID: Int, order: Int): this(baseUrl, ID) {
        this.order = order
    }

    constructor(other: WebApp) : this(other.baseUrl, other.ID) {
        title = other.title
        displayName = other.displayName
        isOverrideGlobalSettings = other.isOverrideGlobalSettings
        containerId = other.containerId
        isUseContainer = other.isUseContainer
        copySettings(other)
    }



    //This part of the copy ctor should be callable independently from actual object construction to copy values of the global web app template
    fun copySettings(other: WebApp) {        isOpenUrlExternal = other.isOpenUrlExternal
        isAllowCookies = other.isAllowCookies
        isAllowThirdPartyCookies = other.isAllowThirdPartyCookies
        isRestorePage = other.isRestorePage
        isAllowJs = other.isAllowJs
        isActiveEntry = other.isActiveEntry
        isRequestDesktop = other.isRequestDesktop
        isClearCache = other.isClearCache
        isSendSavedataRequest = other.isSendSavedataRequest
        isBlockImages = other.isBlockImages
        isAllowHttp = other.isAllowHttp
        isAllowLocationAccess = other.isAllowLocationAccess
        textZoom = other.textZoom
        pageZoom = other.pageZoom
        userAgent = other.userAgent
        isUseCustomUserAgent = other.isUseCustomUserAgent
        isAutoreload = other.isAutoreload
        timeAutoreload = other.timeAutoreload
        isForceDarkMode = other.isForceDarkMode
        isUseTimespanDarkMode = other.isUseTimespanDarkMode
        timespanDarkModeBegin = other.timespanDarkModeBegin
        timespanDarkModeEnd = other.timespanDarkModeEnd
        isIgnoreSslErrors = other.isIgnoreSslErrors
        isShowExpertSettings = other.isShowExpertSettings
        isSafeBrowsing = other.isSafeBrowsing
        isFileAccessDisabled = other.isFileAccessDisabled
        isContentAccessDisabled = other.isContentAccessDisabled
        isMixedContentBlocked = other.isMixedContentBlocked
        isJsPopupsRestricted = other.isJsPopupsRestricted
        isBlockThirdPartyRequests = other.isBlockThirdPartyRequests
        isDrmAllowed = other.isDrmAllowed
        isShowFullscreen = other.isShowFullscreen
        isKeepAwake = other.isKeepAwake
        isCameraPermission = other.isCameraPermission
        isMicrophonePermission = other.isMicrophonePermission
        isEnableZooming = other.isEnableZooming
        isBiometricProtection = other.isBiometricProtection
        isAllowMediaPlaybackInBackground = other.isAllowMediaPlaybackInBackground
        order = other.order
        alwaysUseFallbackContextMenu = other.alwaysUseFallbackContextMenu
    }

    /**
     * 复制非设置字段（统计 + 组合快捷键）。
     * 这些字段**不参与 copySettings 全局合并**（防覆盖），但设置页构造副本时必须保留，
     * 否则保存设置会清空统计/快捷键（P0 防护）。
     */
    fun copyStatsAndShortcuts(other: WebApp) {
        statLaunches = other.statLaunches
        statLoadTimeSum = other.statLoadTimeSum
        statLoadTimeCount = other.statLoadTimeCount
        statMaxLoadTime = other.statMaxLoadTime
        statCacheHttpBytes = other.statCacheHttpBytes
        statCacheStoreBytes = other.statCacheStoreBytes
        statErrors = other.statErrors
        statLastError = other.statLastError
        statFirstLoadedAt = other.statFirstLoadedAt
        statLastUsedAt = other.statLastUsedAt
        // 深拷贝防共享引用（Gson 旧数据可能 null）
        keyShortcuts = (other.keyShortcuts ?: mutableListOf()).toMutableList()
    }

    private fun initDefaultSettings() {
        if (baseUrl.contains("facebook.com")) {
            userAgent = Const.DESKTOP_USER_AGENT
            isUseCustomUserAgent = true
        }
    }

    /*
        This function is used for settings where the ctor needs to have a different setting because
        we want different behaviour for already existing and newly created Web Apps.
            */
    fun applySettingsForNewWebApp() {
        isOverrideGlobalSettings = false
    }

    fun markInactive(activity: Activity) {
        isActiveEntry = false
        ShortcutIconUtils.deleteShortcuts(
            listOf(ID),
            activity
        )
    }


    val alphanumericBaseUrl: String
        get() = baseUrl.replace("\\P{Alnum}".toRegex(), "").replace("https", "").replace("http", "").replace("www", "")




}