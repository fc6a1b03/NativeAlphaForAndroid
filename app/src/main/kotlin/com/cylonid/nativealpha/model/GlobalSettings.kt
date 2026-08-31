package com.cylonid.nativealpha.model


data class GlobalSettings(
    var isClearCache: Boolean = false,
    var isTwoFingerMultitouch: Boolean = true,
    var isThreeFingerMultitouch: Boolean = false,
    var isShowProgressbar: Boolean = false,
    var isMultitouchReload: Boolean = true,
    var themeId: Int = 0,
    var globalWebApp: WebApp = WebApp("about:blank", Int.MAX_VALUE),
    var alwaysShowSoftwareButtons: Boolean = false,
    var clear_cookies: Boolean = false,
    // 网络类页面错误（断网/DNS 失败等）默认不记入站点统计——属环境观测
    // 非站点缺陷，用户定调为噪音；开此开关可恢复完整记录
    var statLogNetworkErrors: Boolean = false
) {

    fun setClearCookies(clear_cookies: Boolean) {
        this.clear_cookies = clear_cookies
    }
}
