package com.cylonid.nativealpha.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * 文件选择链路闭环单测（FileChooserDelegate：launch → 模拟系统返回 → WebView 回调）。
 *
 * 动机（2026-10-26 实机反馈「相册上传失效」）：extractChooserUris/decidePath 已有
 * 纯函数锚点，但「launcher 真实注册 → 系统回调 → settle 回执 WebView」的闭环
 * 无测试。HostActivity 在 onCreate（STARTED 前）构造 delegate——与生产约束
 * 「Activity 构造期实例化」一致；本测试跑完整链路，AI 站空 accept 上传走的
 * PHOTO_PICKER 路径在此断言为绿则代码层健康，实机失效问题收敛到设备/系统层
 * （Photo Picker ROM 兼容或 R8）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileChooserDelegateFlowTest {

    /** delegate 必须在 onCreate 注册 launcher（STARTED 前），故用宿主 Activity 承载 */
    private class HostActivity : ComponentActivity() {
        lateinit var delegate: FileChooserDelegate
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            delegate = FileChooserDelegate(this)
        }
    }

    private class RecordingCallback : android.webkit.ValueCallback<Array<Uri>> {
        var received: Array<Uri>? = null
        var calls = 0
        override fun onReceiveValue(value: Array<Uri>?) {
            received = value
            calls++
        }
    }

    /** 显式 image accept params（相册/照片选择器路径，抽象基类无公开构造，mockito 构造） */
    private fun imageAcceptParams(): FileChooserParams = mock {
        on(it.acceptTypes) doReturn arrayOf("image/*")
        on(it.mode) doReturn FileChooserParams.MODE_OPEN
    }

    /** 空 accept（AI 站 <input type=file> 通用形态）：v2.4.8 起语义为任意文件 → LEGACY */
    private fun emptyAcceptParams(): FileChooserParams = mock {
        on(it.acceptTypes) doReturn arrayOf<String>()
        on(it.mode) doReturn FileChooserParams.MODE_OPEN
        on(it.createIntent()) doReturn Intent(Intent.ACTION_GET_CONTENT)
    }

    private fun launchedHost(): Pair<ActivityController<HostActivity>, HostActivity> {
        val controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        return controller to controller.get()
    }

    /** ComponentActivity.onActivityResult 是 protected——测试经反射直达 registry 分发 */
    private fun dispatchActivityResult(activity: ComponentActivity, requestCode: Int, resultCode: Int, data: Intent) {
        val method = ComponentActivity::class.java.getDeclaredMethod(
            "onActivityResult", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java
        )
        method.isAccessible = true
        method.invoke(activity, requestCode, resultCode, data)
    }

    @Test
    fun photoPickerFlow_launchesSystemPickImages_andDeliversUriToWebView() {
        val (controller, activity) = launchedHost()
        val cb = RecordingCallback()

        val accepted = activity.delegate.onShowFileChooser(activity, imageAcceptParams(), cb)
        assertTrue("onShowFileChooser 必须接管（返回 true）", accepted)

        val launchedIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("Photo Picker 必须真实 launch", launchedIntent)
        assertEquals(
            "显式 image accept 在 picker 可用时走系统 Photo Picker",
            "android.provider.action.PICK_IMAGES",
            launchedIntent.action
        )

        // 模拟系统 Photo Picker 返回：ShadowActivity.receiveResult 按 started
        // intent 匹配回填，走 Activity.onActivityResult → registry 真实分发链
        // （不依赖 registry 内部 key 实现细节）
        val picked = Uri.parse("content://media/picked/42")
        val shadow = shadowOf(activity)
        shadow.receiveResult(launchedIntent, Activity.RESULT_OK, Intent().setData(picked))

        assertEquals("WebView 回调必须收到恰好一次回执", 1, cb.calls)
        assertEquals(
            "回执 URI 必须是所选图片（Photo Picker 授权直通，不走归一化）",
            arrayOf(picked),
            cb.received
        )
        controller.destroy()
    }

    @Test
    fun secondRequestWhilePendingIsRejected_preventsCallbackHijack() {
        val (controller, activity) = launchedHost()
        val cb1 = RecordingCallback()
        val cb2 = RecordingCallback()

        val firstAccepted = activity.delegate.onShowFileChooser(activity, imageAcceptParams(), cb1)
        val secondAccepted = activity.delegate.onShowFileChooser(activity, imageAcceptParams(), cb2)

        assertTrue("首次请求必须接管", firstAccepted)
        assertTrue("挂起期间第二次请求必须被拒（返回 false 走兜底）", secondAccepted.not())
        assertEquals("未完成的第一个回调不被触发", 0, cb1.calls)
        assertEquals("被拒请求的回调不被触发", 0, cb2.calls)
        controller.destroy()
    }

    @Test
    fun emptyAccept_meansAnyFile_launchesLegacyChooser() {
        // v2.4.8 语义修正回归锚点：空 accept=任意文件（HTML 规范），Photo Picker
        // ImageOnly 会拒收非图片——实机实证 zcode 无 accept 输入上传过 Downloads 文档
        val (controller, activity) = launchedHost()
        val cb = RecordingCallback()

        val accepted = activity.delegate.onShowFileChooser(activity, emptyAcceptParams(), cb)
        assertTrue("onShowFileChooser 必须接管（返回 true）", accepted)

        val launchedIntent = shadowOf(activity).nextStartedActivity
        assertNotNull("LEGACY 必须真实 launch", launchedIntent)
        assertEquals(
            "空 accept 走文件管理器（ACTION_GET_CONTENT），不收窄为仅图片",
            Intent.ACTION_GET_CONTENT,
            launchedIntent.action
        )
        controller.destroy()
    }
}
