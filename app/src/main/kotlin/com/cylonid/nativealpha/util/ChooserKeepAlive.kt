package com.cylonid.nativealpha.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 文件选择期间的前台保活（2026-10-26 上传失效根因修复）：
 *
 * 相册/文件选择器是重内存应用，WebNative 携带大 DOM 的 WebView 切后台后，
 * 低内存杀手会在选择期间杀掉本进程——进程重建后 chromium 的
 * filePathCallback 随旧进程死亡，ActivityResult 虽被系统重发（App 侧探针
 * 全绿）却无处可去，页面重载后表现为「选了个寂寞」。实机与模拟器双实锤
 * （模拟器 logcat 亲见 pid 7004→7440 重建）。
 *
 * 保活期间以 shortService（API 34+，约 3 分钟上限）或普通前台服务
 * （API 31-33）维持进程前台优先级；选择结束（settle）即停，不留常驻。
 */
class ChooserKeepAlive : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "文件选择", NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("正在选择文件")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "chooser_keepalive"
        private const val NOTIFICATION_ID = 20261

        @Volatile
        private var running = false

        /** launch 成功后调用：进程前台优先级防 LMK（幂等） */
        fun start(context: Context) {
            if (running) return
            running = true
            val intent = Intent(context, ChooserKeepAlive::class.java)
            context.startForegroundService(intent)
        }

        /** settle（回执/取消）后调用：不留常驻（幂等） */
        fun stop(context: Context) {
            if (!running) return
            running = false
            context.stopService(Intent(context, ChooserKeepAlive::class.java))
        }

        /** 单测可见的守卫状态 */
        internal fun isRunningForTest() = running
    }
}
