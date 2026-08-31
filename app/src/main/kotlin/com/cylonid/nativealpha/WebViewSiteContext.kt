package com.cylonid.nativealpha

import android.content.Context
import android.webkit.HttpAuthHandler
import android.webkit.WebView
import com.cylonid.nativealpha.model.WebApp

/**
 * WebViewClient 与宿主环境之间的解耦契约（QA 基类抽取，P4 第 0 步）。
 *
 * 历史问题：CustomBrowser 直接持有 WebViewActivity 强引用，站点行为
 * （拦截/SSL/UA/统计）与宿主生命周期耦合——矩阵窗无法复用（其崩溃路径
 * 会 host.finish() 带走全部窗格）。
 *
 * 本接口抽出站点行为所需的全部宿主触点：
 * - 站点数据（webappId/webapp/URL 状态）由宿主实现方持有
 * - 加载生命周期 UI（动物动画/白屏检测/错误页）由宿主实现方提供；
 *   矩阵实现方换为格状态机迁移
 * - 统计口径分流：宿主记 StatsRecorder（统计页语义）；矩阵仅 FeatureMetrics
 *   计数（M2 决策 QC：矩阵加载不入宿主统计）
 *
 * 行为契约：所有实现方对同名钩子必须保持「同源语义」——站点行为
 * （拦截/SSL/导航分流）在基类唯一实现，实现方不得重写。渲染进程崩溃
 * 清理不进本接口：它是基类的 open 函数（宿主/矩阵子类唯一行为分歧点）。
 */
internal interface WebViewSiteContext {

    /** 宿主 Context：SSL/HTTP 认证对话框与外部 Intent（tel:/mailto:/App 唤起） */
    val siteContext: Context

    /** 站点 ID（统计埋点与 DataManager 查询键） */
    val webappId: Int

    /** 站点配置（行为开关来源；null 时导航分流走系统默认） */
    val webapp: WebApp?

    /** 首次主框架请求 URL（错误页重试的兜底目标） */
    var urlOnFirstPageload: String

    /** 错误页重试目标（onReceivedError 主框架失败记录，webnative://retry 消费） */
    var retryUrl: String

    /** 页面加载开始时间（onPageStarted 置位，onPageFinished 计算耗时后清零） */
    var pageLoadStartTime: Long

    /** 页面开始加载：宿主重置加载 UI 状态（白屏检测重置/进度计时/动画起点） */
    fun onPageLoadStarted()

    /** 页面完成加载：宿主收尾加载 UI（白屏检测取消/停动画/缓存统计/缩放生效） */
    fun onPageLoadFinished()

    /** 主框架加载失败或空白页：展示自定义错误页（替代系统白屏） */
    fun showCustomErrorPage(code: String?, desc: String?)

    /** HTTP Basic 认证请求：宿主弹认证对话框 */
    fun onHttpAuthRequested(handler: HttpAuthHandler, authHost: String, realm: String)

    /** 页面缩放应用（onPageFinished 里 zoomBy 模拟捏合） */
    fun applyPageZoom()

    /** 缓存占用统计（onPageFinished 异步执行） */
    fun recordCacheUsage()

    /** 导航开始时刷新深色模式（SPA 内跳转主题可能变化） */
    fun refreshDarkModeOnMainThread()

    /** 站内导航加载（含 http 明文确认对话框与自定义请求头） */
    fun loadSiteUrl(view: WebView, url: String)

    /**
     * 页面耗时统计分流：宿主记 StatsRecorder（统计页耗时图表）；
     * 矩阵实现为 FeatureMetrics 计数（QC 决策，不入宿主统计）。
     */
    fun recordPageLoadDuration(durationMs: Long)

    /**
     * 页面错误统计分流：宿主记 StatsRecorder（统计页错误历史）；
     * 矩阵实现为 FeatureMetrics 计数（QC 决策）。
     */
    fun recordPageError(errorType: String, code: String, desc: String)

    /**
     * 断线自动恢复：主帧加载失败后启动探测监视（先探测站点可达再自动
     * 重载，避免盲目重试与无出口等待）；探测目标为站点 baseUrl，恢复
     * 动作由实现方注入（宿主 reload retryUrl / 矩阵 pickSite 重载）。
     */
    fun startReconnectWatch()

    /** 停止断线监视（页面加载成功或宿主/窗格销毁时调用） */
    fun stopReconnectWatch()
}
