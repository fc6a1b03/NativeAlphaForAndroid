package com.cylonid.nativealpha.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DataManager P2 响应式改造行为测试：
 * 写路径收口（commitChanges）发射 webAppsFlow、invalidate 逃生门拾取外部写入。
 * Robolectric 提供真 SharedPreferences；单例跨用例残留状态用 invalidate 基线归零。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DataManagerFlowTest {

    @Test
    fun `addWebsite emits flow with new entry`() {
        val dm = DataManager.getInstance()
        dm.invalidate()
        val activeBefore = dm.webAppsFlow.value.items.count { it.isActiveEntry }
        dm.addWebsite(WebApp("https://flow-a.example.com", dm.incrementedID, 0))
        val flow = dm.webAppsFlow.value.items
        assertEquals(activeBefore + 1, flow.count { it.isActiveEntry })
        assertTrue(flow.any { it.baseUrl == "https://flow-a.example.com" })
    }

    @Test
    fun `commitChanges emits flow after in-place field mutation`() {
        val dm = DataManager.getInstance()
        dm.invalidate()
        val target = dm.webAppsFlow.value.items.firstOrNull() ?: return
        val stored = dm.getWebAppIgnoringGlobalOverride(target.ID, true) ?: return
        stored.title = "renamed-${target.ID}"
        // 模拟 MainActivity.deleteWebApp 式的野写收口：改内存对象后 commit。
        // revision 断言是关键——原地改不换元素引用，无 revision 必被 StateFlow 吞掉
        val revisionBefore = dm.webAppsFlow.value.revision
        dm.commitChanges()
        assertTrue(dm.webAppsFlow.value.items.any { it.title == "renamed-${target.ID}" })
        assertEquals(revisionBefore + 1, dm.webAppsFlow.value.revision)
    }

    @Test
    fun `invalidate picks up external sp writes`() {
        val dm = DataManager.getInstance()
        dm.invalidate()
        // 模拟收口遗漏的野写路径：绕过 DataManager 直接写 SP
        val json = Gson().toJson(arrayListOf(WebApp("https://external.example.com", 999, 0)))
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("WEBSITEDATA", Context.MODE_PRIVATE)
            .edit()
            .putString("WEBSITEDATA", json)
            .commit()
        dm.invalidate()
        assertTrue(dm.webAppsFlow.value.items.any { it.baseUrl == "https://external.example.com" })
    }
}
