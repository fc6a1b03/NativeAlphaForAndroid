package com.cylonid.nativealpha.model

/**
 * 页面错误类型枚举（统计埋点统一数据源）。
 *
 * 用法：
 * - Java：`ErrorType.HTTP.name()`（与存储/展示层的 String 一致）
 * - Kotlin：`ErrorType.HTTP.name`（统计页配色 when 分支）
 *
 * 禁止在业务代码散落字符串魔法值（"HTTP"/"NETWORK"/"SSL"/"RENDER"/"JS"），
 * 一律经本枚举（单一事实源，防拼写不一致导致统计口径污染）。
 */
enum class ErrorType(val code: String) {
    /** HTTP 状态码错误（onReceivedHttpError） */
    HTTP("http"),
    /** 网络层错误（onReceivedError，DNS/连接失败等） */
    NETWORK("network"),
    /** SSL 证书错误（onReceivedSslError） */
    SSL("ssl"),
    /** 渲染进程崩溃（onRenderProcessGone） */
    RENDER("render"),
    /** 页面未捕获 JS 异常（onJsError） */
    JS("js")
}
