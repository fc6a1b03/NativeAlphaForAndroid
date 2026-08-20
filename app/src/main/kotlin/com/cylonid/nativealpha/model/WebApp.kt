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
    fun copySettings(other: WebApp) {
        isOpenUrlExternal = other.isOpenUrlExternal
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