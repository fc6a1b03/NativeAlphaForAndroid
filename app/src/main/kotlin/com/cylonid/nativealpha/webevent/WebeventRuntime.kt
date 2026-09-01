package com.cylonid.nativealpha.webevent

import android.content.Context
import android.webkit.WebView
import android.widget.Toast
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.util.FeatureMetrics

/**
 * 网页事件运行时（P5 宿主接线单一入口）。
 *
 * 宿主触点（全部经本对象，不散落）：
 * - App.onCreate：[init]（Store 载入 + Channel 幂等创建 + 动作分发装配）
 * - WebViewActivity：[attachBridge]（WebView 创建时）、[injectHook]
 *   （onPageStarted，仅配规则站）、[shouldKeepTimersRunning]（切后台豁免，P5-1）
 * - MainActivity 删除站点：[cascadeDeleteForSite]（P5-3 级联）
 */
internal object WebeventRuntime {

    private var appContext: Context? = null

    /** App.onCreate 幂等初始化 */
    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        EventRuleStore.init(context.applicationContext)
        WebeventNotifier.ensureChannel(context.applicationContext)
        EventRuleEngine.actionDispatcher =
            object : EventRuleEngine.ActionDispatcher {
                override fun dispatch(rule: EventRule, event: WebEvent, hitCount: Int) {
                    val context = appContext ?: return
                    when (rule.action) {
                        EventRule.ACTION_TOAST -> Toast.makeText(
                            context,
                            summarize(event, hitCount),
                            Toast.LENGTH_LONG
                        ).show()
                        else -> if (WebeventNotifier.isPermissionGranted(context)) {
                            WebeventNotifier.show(context, event, hitCount)
                            FeatureMetrics.count("webevent", "notification_shown")
                        } else {
                            // 权限拒绝降级：Toast 兜底 + 计数（规格权限链路）
                            Toast.makeText(
                                context,
                                context.getString(R.string.webevent_perm_degraded),
                                Toast.LENGTH_LONG
                            ).show()
                            FeatureMetrics.count("webevent", "permission_denied")
                        }
                    }
                }
            }
    }

    /** WebView 创建时注册桥（宿主 + 后续扩展点；矩阵 v1 不接，P5-6） */
    fun attachBridge(webView: WebView, webappId: Int) {
        if (!EventRuleStore.hasActiveRules(webappId)) return
        webView.addJavascriptInterface(
            WebEventBridge(webappId) { event -> EventRuleEngine.onWebEvent(event) },
            WebEventBridge.JAVASCRIPT_INTERFACE_NAME
        )
    }

    /**
     * onPageStarted 注入 hook（幂等脚本；无生效规则返回 null 不注入——
     * 未配规则站点零开销）。
     */
    fun hookScriptFor(webappId: Int): String? {
        if (!EventRuleStore.hasActiveRules(webappId)) return null
        val selectors = EventRuleStore.rules.value
            .filter {
                it.webappId == webappId && it.enabled &&
                    it.trigger == EventRule.TRIGGER_SELECTOR && it.condition.isNotBlank()
            }
            .map { it.condition }
        return JsHookScript.build(selectors)
    }

    /** 切后台豁免判定（P5-1）：有生效规则的站不 pauseTimers */
    fun shouldKeepTimersRunning(webappId: Int): Boolean =
        EventRuleStore.hasActiveRules(webappId)

    /** 站点删除级联（P5-3）：规则库 + 引擎运行态同步清理 */
    fun cascadeDeleteForSite(webappId: Int) {
        EventRuleStore.cascadeDeleteForSite(webappId)
        EventRuleEngine.forgetSite(webappId)
        hookAliveBySite.remove(webappId)
    }

    /**
     * hook 存活探针记账（宿主 onPageFinished 回传）：注入过的幂等标记
     * 是否在当前文档存活。会话内内存态——null=本会话未探测过（未知）。
     * 站点改版导致注入失败/标记丢失时，规则入口卡据此显示「可能失效」。
     */
    fun onHookProbe(webappId: Int, alive: Boolean) {
        hookAliveBySite[webappId] = alive
    }

    /** 该站 hook 存活性：null=未知（未探测/未配规则），false=疑似失效 */
    fun hookLiveness(webappId: Int): Boolean? = hookAliveBySite[webappId]

    private val hookAliveBySite = HashMap<Int, Boolean>()

    private fun summarize(event: WebEvent, hitCount: Int): String {
        val context = appContext ?: return event.title
        return if (hitCount > 1) {
            context.getString(R.string.webevent_merged_summary, hitCount)
        } else {
            event.title.ifBlank { context.getString(R.string.webevent_notif_selector_fired) }
        }
    }
}
