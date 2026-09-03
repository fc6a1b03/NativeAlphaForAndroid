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
    // 网络类页面错误（断网/DNS 失败等）默认不记入站点统计——属环境观测
    // 非站点缺陷，用户定调为噪音；开此开关可恢复完整记录
    var statLogNetworkErrors: Boolean = false,
    // 单指边缘左右滑导航默认关闭：起点偶落边缘区的横滚仍可能误触返回，
    // 前进/后退已有双指导航（默认开）与系统返回手势覆盖，边缘滑降级为可选增强
    var isEdgeSwipeNavigation: Boolean = false
) {
}
