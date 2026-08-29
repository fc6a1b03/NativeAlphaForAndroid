package com.cylonid.nativealpha.webevent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.util.WebViewLauncher

/**
 * 事件通知器（P5，规格 §5.2 通知本体；动作 A1）。
 *
 * - Channel `webevent_notify`（IMPORTANCE_DEFAULT，App.onCreate 幂等创建）
 * - title=站点名；text=事件摘要（合并时「N 个规则触发」）
 * - contentIntent 直达该站 WebViewActivity（P5 决策：独立打开，正常快照语义）
 * - autoCancel + 同站 setGroup 合并展示
 */
internal object WebeventNotifier {

    const val CHANNEL_ID = "webevent_notify"
    private const val GROUP_PREFIX = "webevent_"

    /** Channel 幂等创建（App.onCreate 调用；minSdk 31 无版本分支） */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.webevent_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.webevent_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    /** 通知权限是否已授予（API <33 无运行时通知权限，恒 true） */
    fun isPermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    /** 展示事件通知（引擎动作分发回调；notify 权限拒绝时降级 Toast 由调用方兜底） */
    fun show(context: Context, event: WebEvent, hitCount: Int) {
        if (!isPermissionGranted(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val site = DataManager.getInstance().getWebApp(event.webappId) ?: return
        val siteName = site.title
        val text = if (hitCount > 1) {
            context.getString(R.string.webevent_merged_summary, hitCount)
        } else {
            event.title.ifBlank {
                context.getString(R.string.webevent_notif_selector_fired)
            }
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            event.webappId,
            WebViewLauncher.createWebViewIntent(site, context, event.webappId, 0) ?: return,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(siteName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setGroup(GROUP_PREFIX + event.webappId)
            .build()
        // 同站通知合并展示：id 用站点 id（新事件替换旧事件，避免堆叠）
        manager.notify(event.webappId, notification)
    }
}
