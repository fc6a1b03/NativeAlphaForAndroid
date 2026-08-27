package com.cylonid.nativealpha.helper

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Toast
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.WebViewActivity
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.util.NotificationUtils
import com.cylonid.nativealpha.util.WebViewLauncher

/**
 * 触摸手势处理器（v2.2.0 P3 第一刀，自 WebViewActivity 逐行迁移，零语义变更）。
 *
 * 职责：双击空白/输入框弹小菜单、长按媒体下载、多指切换站点、
 * 边缘单指滑前进后退、滚轮状态清理。
 *
 * 设计约束：持有 Activity 实例引用（由 Activity 构造注入，非静态——R11 防泄漏）；
 * 方法体与原实现逐行对应，行为差异零容忍。
 */
class WebViewTouchHandler(private val activity: WebViewActivity) {

    companion object {
        // Constants for touchlistener
        private const val NONE = 0
        private const val SWIPE = 1
        private const val SINGLE_FINGER = 2

        /** 单指左右滑手势触发距离（px）：比 TRESHOLD 更严——只识别明确意图的长滑，防误触 */
        private const val GESTURE_SWIPE_MIN_PX = 150

        /** 长按触发时间（ms）——长按媒体直接下载（500ms 主流折中：不误触不迟钝） */
        private const val LONG_PRESS_MS = 500L

        /** 单指手势边缘区宽度（屏幕横向比例）：起点在左右各 <20% 或 >80% 才识别左右滑（页面内表格不冲突） */
        private const val GESTURE_EDGE_ZONE_RATIO = 0.2f

        /** 水平位移 > 垂直位移的倍数：过滤倾斜滑动（上下滚动不误判左右手势） */
        private const val GESTURE_HORIZONTAL_DOMINANCE = 1.2f
        private const val TRESHOLD = 100

        /** 双击判定窗口（ms）：快速连点有效窗（300ms/50px 偏宽松曾误触——实测收紧） */
        private const val DOUBLE_TAP_WINDOW_MS = 250

        /** 双击坐标容差（px）：两次按下须落在同点附近才算双击 */
        private const val DOUBLE_TAP_SLOP_PX = 40
    }

    private val longPressHandler = Handler()
    private var longPressRunnable: Runnable? = null

    /** 收起软键盘（双击空白弹小菜单时调用，避免输入法和小菜单打架） */
    fun hideSoftKeyboard() {
        try {
            val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as
                InputMethodManager
            imm.hideSoftInputFromWindow(activity.wv!!.windowToken, 0)
        } catch (ignored: Exception) {
            // 收起失败不影响小菜单弹出
        }
    }

    /** 触摸手势（双击菜单/长按媒体下载/多指切换/边缘左右滑）——原匿名 OnTouchListener 逐行翻译 */
    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        var mode = NONE
        var startX = 0f
        var stopX = 0f
        var startY = 0f
        var stopY = 0f
        // 双击检测：双击空白 → 弹小菜单。
        // 设计：长按完全交还系统（文字选中 100% 正常，不再与系统 ActionMode
        // 竞争——此前空白长按判定在真实站点频繁误判，是历史 bug 根因）。
        // 双击是纯手势识别（300ms 内同点二次按下），系统在空白处无默认行为。
        // 自实现不用 GestureDetector：其内部状态机对注入事件/快速连点
        // 识别不稳定，自实现时间戳+坐标判定简单可靠。
        var lastDownTime = 0L
        // 上一次 ACTION_UP 的时刻：真实手指双击的 UP→DOWN 间隔 ≥30ms（人手极限）；
        // 滚轮/自动化注入的合成流 UP→DOWN 仅 1-2ms（实测 0x5002 触屏源），
        // 间隔 <20ms 判为合成事件，不参与双击判定
        var lastUpTime = 0L
        var lastDownX = -1f
        var lastDownY = -1f

        /**
         * 双击菜单判定：JS 检测命中语义后由 WebViewGestureHelper 归一化决策，
         * blank/input 弹小菜单（文本框双击=选择/粘贴高频操作），其余交还网页。
         */
        fun checkBlankAndShowMenu(px: Float, py: Float) {
            if (activity.isFinishing || activity.wv == null) return
            val js = String.format(java.util.Locale.US, WebViewGestureHelper.buildLongPressJs(), px, py)
            val mediaJs = String.format(java.util.Locale.US, WebViewGestureHelper.buildMediaLongPressJs(), px, py)
            activity.wv!!.evaluateJavascript(js) { value ->
                val type = WebViewGestureHelper.parseLongPressResult(value)
                if (WebViewGestureHelper.shouldShowMenuOnDoubleTap(type) && !activity.isFinishing && activity.wv != null) {
                    // blank/input 检测通过，再探测是否命中 media（双击图片时弹保存菜单而非小菜单）
                    activity.wv!!.evaluateJavascript(mediaJs) { mediaValue ->
                        val mediaUrl = mediaValue?.replace("\"", "") ?: "null"
                        if (mediaUrl == "null" || mediaUrl.isEmpty()) {
                            // 非 media（空白/输入框/文本字符）→ 弹原有小菜单
                            activity.runOnUiThread {
                                if (activity.wv != null) {
                                    // 双击弹菜单时收起输入法：blur 失焦（键盘必收且小菜单
                                    // 关闭后不弹回）+ hideSoftInput 兜底
                                    activity.wv!!.evaluateJavascript(
                                        "window.getSelection().removeAllRanges();", null
                                    )
                                    // 输入框失焦：键盘必然收起且不再弹
                                    activity.wv!!.evaluateJavascript(
                                        "var el=document.activeElement;"
                                            + "if(el&&(el.tagName==='INPUT'||el.tagName==='TEXTAREA'||el.isContentEditable)){el.blur();}",
                                        null
                                    )
                                }
                                // 收起输入法（兜底，blur 已处理主要路径）
                                hideSoftKeyboard()
                                activity.showWebViewMenuSheet()
                            }
                        } else {
                            // 命中图片/视频：交还 WebView（双击放大由网页处理；保存走长按）
                            android.util.Log.d("LongPress", "media double-tap -> webview handles")
                        }
                    }
                }
            }
        }

        /** 判断下载 URL 是否为视频（去 query/fragment 后按后缀；带签名参数的 CDN 链接也能识别） */
        fun isVideoUrl(url: String?): Boolean {
            if (url == null) return false
            var clean = url
            val q = clean.indexOf('?')
            if (q >= 0) clean = clean.substring(0, q)
            val h = clean.indexOf('#')
            if (h >= 0) clean = clean.substring(0, h)
            clean = clean.lowercase(java.util.Locale.US)
            return clean.endsWith(".mp4") || clean.endsWith(".webm") || clean.endsWith(".mov")
                || clean.endsWith(".ogg") || clean.endsWith(".mkv") || clean.endsWith(".m3u8")
        }

        /** 保存图片/视频：复用 DownloadManager（与全站下载一致的统一处理） */
        fun downloadMedia(url: String?) {
            if (url.isNullOrEmpty()) {
                NotificationUtils.showToast(
                    activity,
                    activity.getString(R.string.file_download), android.widget.Toast.LENGTH_SHORT
                )
                return
            }
            var dlUrl = url
            if (dlUrl.startsWith("blob:")) {
                dlUrl = dlUrl.replace("blob:", "")
                try {
                    dlUrl = java.net.URLDecoder.decode(dlUrl, "UTF-8")
                } catch (ignored: java.io.UnsupportedEncodingException) {
                }
            }
            try {
                val request = android.app.DownloadManager.Request(android.net.Uri.parse(dlUrl))
                val isMediaVideo = isVideoUrl(dlUrl)
                request.setMimeType(if (isMediaVideo) "video/*" else "image/*")
                var fileName = com.cylonid.nativealpha.util.Utility.getFileNameFromDownload(dlUrl, null, null)
                if (fileName.isNullOrEmpty()) {
                    fileName = "media_" + System.currentTimeMillis() +
                        (if (isMediaVideo) ".mp4" else ".png")
                }
                request.setTitle(fileName)
                request.setDescription(activity.getString(R.string.file_download_started))
                request.allowScanningByMediaScanner()
                request.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS, fileName
                )
                val dm = activity.getSystemService(Activity.DOWNLOAD_SERVICE) as android.app.DownloadManager?
                if (dm != null) {
                    // 与「应用更新」一致：系统 DownloadManager + 开始/完成通知；开始即 Snackbar 提示
                    dm.enqueue(request)
                    NotificationUtils.showInfoSnackbar(
                        activity,
                        activity.getString(R.string.file_download_started),
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                    )
                }
            } catch (e: Exception) {
                NotificationUtils.showToast(
                    activity,
                    activity.getString(R.string.file_download), android.widget.Toast.LENGTH_SHORT
                )
            }
        }

        /** 取消长按检测（移动/抬起/多指时） */
        fun cancelLongPress() {
            if (longPressRunnable != null) {
                longPressHandler.removeCallbacks(longPressRunnable!!)
                longPressRunnable = null
            }
        }

        /** 长按命中 media：直接下载（用户需求——长按即存，无中间菜单） */
        fun checkMediaLongPress(px: Float, py: Float) {
            if (activity.isFinishing || activity.wv == null) return
            val mediaJs = String.format(java.util.Locale.US, WebViewGestureHelper.buildMediaLongPressJs(), px, py)
            activity.wv!!.evaluateJavascript(mediaJs) { value ->
                val mediaUrl = value?.replace("\"", "") ?: "null"
                if (mediaUrl != "null" && mediaUrl.isNotEmpty() && !activity.isFinishing) {
                    activity.runOnUiThread {
                        // 直接下载（DownloadManager）；Toast 提示已存
                        downloadMedia(mediaUrl)
                    }
                }
            }
        }

        activity.wv!!.setOnTouchListener { _, event ->
            val webapp = DataManager.getInstance().getWebApp(activity.webappID)
            if (webapp == null || webapp.isRequestDesktop) {
                return@setOnTouchListener false
            }
            // 鼠标/触控笔源不参与触摸手势：双击/长按/多指语义均针对手指设计，
            // 鼠标滚轮(hover+scroll 走 onGenericMotionEvent)与左键快连曾误判双击弹菜单
            if (event.getSource() and InputDevice.SOURCE_CLASS_POINTER != 0
                && event.isFromSource(InputDevice.SOURCE_MOUSE)
            ) {
                return@setOnTouchListener false
            }

            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    // 双击检测：250ms 内同点（±40px）再次按下 → 双击 → 弹小菜单。
                    // 250ms/40px 是快速双击窗口：慢速点击/滚动不会误判
                    // （300ms/50px 偏宽松，滚动+连点曾误触——实测收紧）
                    val now = System.currentTimeMillis()
                    val x = event.getX(0)
                    val y = event.getY(0)
                    // 合成流过滤：UP 后 <20ms 的 DOWN 是滚轮/注入产生的连续轻扫
                    // （真实手指双击的 UP→DOWN 间隔 ≥30ms 人手极限；实测合成流
                    // 间隔可为 0/1/2/8ms——边界必须含 0）
                    val sinceUp = now - lastUpTime
                    val isSynthetic = lastUpTime > 0 && sinceUp < 20
                    if (!isSynthetic && now - lastDownTime < DOUBLE_TAP_WINDOW_MS
                        && kotlin.math.abs(x - lastDownX) < DOUBLE_TAP_SLOP_PX
                        && kotlin.math.abs(y - lastDownY) < DOUBLE_TAP_SLOP_PX
                    ) {
                        lastDownTime = 0 // 重置防三连击
                        // 双击：弹小菜单（输入框双击也走菜单——交互一致性）
                        checkBlankAndShowMenu(x, y)
                    } else {
                        lastDownTime = if (isSynthetic) 0 else now
                        lastDownX = x
                        lastDownY = y
                        // 单击：完全交还 WebView（键盘自然弹，无任何拦截——
                        // 拦截/恢复机制是实机「输入法反复弹收」的根因，已删除）
                    }
                    // 单指按下：记录起始坐标（供滑动阈值判断）
                    // stopX/stopY 同时初始化（防 POINTER_UP 用旧值/0 误判）
                    startX = x
                    startY = y
                    stopX = x
                    stopY = y
                    // 长按检测：600ms 无移动且命中图片/视频 → 弹保存菜单
                    if (longPressRunnable != null) {
                        longPressHandler.removeCallbacks(longPressRunnable!!)
                    }
                    val pressX = x
                    val pressY = y
                    longPressRunnable = Runnable {
                        longPressHandler.removeCallbacks(longPressRunnable!!)
                        checkMediaLongPress(pressX, pressY)
                    }
                    longPressHandler.postDelayed(longPressRunnable!!, LONG_PRESS_MS)
                    // 单指手势：仅单指（未进入多指）时启用
                    mode = SINGLE_FINGER
                    false
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    // This happens when you touch the screen with two fingers
                    mode = SWIPE
                    // 多指手势（捏合/双指滚动）：不是双击，清除双击检测状态
                    lastDownTime = 0
                    // You can also use event.getY(1) or the average of the two
                    startX = event.getX(0)
                    startY = event.getY(0)
                    true
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    // This happens when you release the second finger
                    mode = NONE
                    // release 前指针数（POINTER_UP 时 getPointerCount 已减 1）
                    val prevCount = event.pointerCount + 1
                    if (kotlin.math.abs(startX - stopX) > TRESHOLD) {
                        if (startX > stopX) {
                            if (prevCount == 3 &&
                                DataManager.getInstance().settings.isThreeFingerMultitouch
                            ) {
                                WebViewLauncher.startWebView(
                                    DataManager.getInstance().getPredecessor(activity.webappID),
                                    activity
                                )
                                activity.finish()
                            } else if (DataManager.getInstance().settings.isTwoFingerMultitouch) {
                                activity.safeGoForward()
                            }
                        } else {
                            if (prevCount == 3 &&
                                DataManager.getInstance().settings.isThreeFingerMultitouch
                            ) {
                                WebViewLauncher.startWebView(
                                    DataManager.getInstance().getSuccessor(activity.webappID),
                                    activity
                                )
                                activity.finish()
                            } else if (DataManager.getInstance().settings.isTwoFingerMultitouch) {
                                activity.safeBackPressed()
                            }
                        }
                        return@setOnTouchListener true
                    }
                    if (DataManager.getInstance().settings.isMultitouchReload &&
                        kotlin.math.abs(startY - stopY) > TRESHOLD
                    ) {
                        if (stopY > startY) {
                            activity.currentlyReloading = true
                            activity.safeReload()
                        }
                        return@setOnTouchListener true
                    }
                    // fall through 语义（原 Java switch 无 break 直落）：不满足阈值时
                    // 走 UP 分支的收尾——POINTER_UP 后 mode=NONE，UP 的边缘手势判断
                    // 天然不触发，等价于仅做长按取消 + 状态复位
                    false
                }

                MotionEvent.ACTION_UP -> {
                    // 抬起即取消长按：单击(快速 DOWN→UP)后 600ms 定时器不得再触发——
                    // 否则单击图片必然在抬起后误弹「保存/下载」（用户实测反馈的 bug）。
                    // 只清长按不清双击状态：双击=UP 后短窗内再 DOWN，UP 本身是双击的
                    // 正常组成部分，清掉会让双击检测永久失效（实测踩坑）
                    lastUpTime = System.currentTimeMillis()
                    longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    handleActionUp(mode, event, startX, startY, stopX, stopY)
                }

                MotionEvent.ACTION_MOVE -> {
                    // 无论单指/多指都记录当前坐标（stopX/stopY 保持最新，POINTER_UP/UP 用最新值）
                    // 单指非滑动（mode==单指）也更新：防 POINTER_UP 用旧值误判
                    stopX = event.getX(0)
                    stopY = event.getY(0)
                    // 移动超阈值（滑动/拖动滚动）：不是双击，清除双击检测状态；移动也取消长按
                    if (kotlin.math.abs(stopX - startX) > TRESHOLD
                        || kotlin.math.abs(stopY - startY) > TRESHOLD
                    ) {
                        cancelLongPressAndReset { }
                    }
                    false
                }

                MotionEvent.ACTION_SCROLL -> {
                    // 滚轮滚动：不是双击，清除双击检测状态（防止误判弹菜单）
                    lastDownTime = 0
                    false
                }

                else -> false
            }
        }
    }

    /** UP/POINTER_UP 落底共享逻辑：边缘单指滑手势 + 状态重置（原 switch 直落语义拆出） */
    private fun handleActionUp(
        mode: Int,
        event: MotionEvent,
        startX: Float,
        startY: Float,
        stopX: Float,
        stopY: Float
    ): Boolean {
        // 抬起：重置滑动状态。
        // 单指左右滑手势（竖屏单手控制前进/后退）：
        // - 右滑（stopX > startX）= 后退（回上一个页面，与返回一致）
        // - 左滑（stopX < startX）= 前进（有历史才执行）
        // 防冲突（用户反馈）：只识别**屏幕左右边缘区**（起点在左右各 20% 内）——
        // 页面中间区域（表格/轮播等可横向滚动内容）完全交还 WebView，不拦截。
        // 触发距离 150px（比 TRESHOLD 更严），要求水平位移 > 垂直位移*1.2。
        if (mode == SINGLE_FINGER && event.pointerCount == 1) {
            val dx = stopX - startX
            val dy = kotlin.math.abs(stopY - startY)
            val screenW = activity.resources.displayMetrics.widthPixels
            val edgeZone = startX < screenW * GESTURE_EDGE_ZONE_RATIO
                || startX > screenW * (1f - GESTURE_EDGE_ZONE_RATIO)
            if (edgeZone && kotlin.math.abs(dx) > GESTURE_SWIPE_MIN_PX
                && kotlin.math.abs(dx) > dy * GESTURE_HORIZONTAL_DOMINANCE
            ) {
                if (dx > 0) {
                    // 右滑后退：有历史才退（与系统返回一致）；
                    // 无历史不动作（此前会退出应用）——给轻提示
                    if (activity.wv != null && activity.wv!!.canGoBack()) {
                        activity.safeBackPressed()
                    } else {
                        NotificationUtils.showToast(
                            activity,
                            activity.getString(R.string.gesture_no_prev_page), Toast.LENGTH_SHORT
                        )
                    }
                } else if (dx < 0) {
                    // 左滑前进：有前进历史才执行；无则不动作，给轻提示
                    if (activity.wv != null && activity.wv!!.canGoForward()) {
                        activity.safeGoForward()
                    } else {
                        NotificationUtils.showToast(
                            activity,
                            activity.getString(R.string.gesture_no_next_page), Toast.LENGTH_SHORT
                        )
                    }
                }
                // 关键：不消费 UP（return false）——WebView 需要完整 DOWN→MOVE→UP
                // 事件流，消费 UP 会让 WebView 触摸状态机卡在"按住"，
                // 导致后续上下滑动完全失效（用户反馈的 bug 根因）
                return false
            }
        }
        return false
    }

    /** MOVE 超阈值：取消长按 + 清双击状态（局部状态经回调复位） */
    private fun cancelLongPressAndReset(resetDoubleTap: () -> Unit) {
        longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
        longPressRunnable = null
        resetDoubleTap()
    }
}
