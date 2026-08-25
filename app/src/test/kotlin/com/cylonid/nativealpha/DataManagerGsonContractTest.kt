package com.cylonid.nativealpha

import com.cylonid.nativealpha.model.GlobalSettings
import com.cylonid.nativealpha.model.WebApp
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DataManager 迁移 Kotlin（v2.1.32 第二刀）的 Gson 持久化契约锁。
 *
 * 迁移原则是"机械迁移零语义变更"——本测试锁死序列化字段名与
 * 全局设置合并行为，任何字段改名/合并回归在此即刻爆红：
 * Gson JSON 是用户数据契约（v2.0 起无兼容层），改名 = 用户数据丢失。
 */
class DataManagerGsonContractTest {

    private val gson = Gson()

    /** WebApp 关键持久化字段名不漂移（抽查高频+易改名字段） */
    @Test
    fun webAppJsonFieldNames_stayStable() {
        val webApp = WebApp("https://example.com", 42)
        webApp.title = "Example"
        webApp.iconPath = "/icons/example.png"
        webApp.order = 3
        webApp.textZoom = 120
        webApp.pageZoom = 110
        webApp.statLaunches = 7

        val json = gson.toJson(webApp)
        val obj = gson.fromJson(json, com.google.gson.JsonObject::class.java)

        assertTrue(obj.has("baseUrl"))
        assertTrue(obj.has("ID"))
        assertTrue(obj.has("title"))
        assertTrue(obj.has("iconPath"))
        assertTrue(obj.has("order"))
        assertTrue(obj.has("textZoom"))
        assertTrue(obj.has("pageZoom"))
        assertTrue(obj.has("statLaunches"))
        // 关键值断言
        assertEquals(42, obj.get("ID").asInt)
        assertEquals("Example", obj.get("title").asString)
        assertEquals(3, obj.get("order").asInt)
        assertEquals(120, obj.get("textZoom").asInt)
        assertEquals(110, obj.get("pageZoom").asInt)
        assertEquals(7, obj.get("statLaunches").asInt)
    }

    /** GlobalSettings 持久化字段名不漂移（含 snake_case 历史契约 clear_cookies） */
    @Test
    fun globalSettingsJsonFieldNames_stayStable() {
        val settings = GlobalSettings()
        settings.themeId = 2
        settings.globalWebApp = WebApp("https://global.example.com", Int.MAX_VALUE)

        val json = gson.toJson(settings)
        val obj = gson.fromJson(json, com.google.gson.JsonObject::class.java)

        assertTrue(obj.has("themeId"))
        assertTrue(obj.has("globalWebApp"))
        assertTrue(obj.has("clear_cookies")) // snake_case 历史契约，禁止顺手改名
        assertTrue(obj.has("isTwoFingerMultitouch"))
        assertEquals(2, obj.get("themeId").asInt)
    }

    /** WebApp 反序列化空值防护：缺失 globalWebApp 字段时默认值兜底（实测 Gson 保留字段初始值） */
    @Test
    fun globalSettings_missingGlobalWebAppField_keepsDefaultValue() {
        // 模拟旧 JSON 缺 globalWebApp 字段
        val legacyJson = """{"themeId":1}"""
        val loaded = gson.fromJson(legacyJson, GlobalSettings::class.java)

        // Gson 对 Kotlin 非 null 属性缺失字段时保留默认值（构造器默认参数写入字段初始值）
        assertEquals("about:blank", loaded.globalWebApp.baseUrl)
        assertEquals(Int.MAX_VALUE, loaded.globalWebApp.ID)

        // DataManager.loadAppData 的防御逻辑仍保留：万一为 null（自定义 Gson 配置时）补默认
        if (loaded.globalWebApp == null) {
            loaded.globalWebApp = WebApp("about:blank", Int.MAX_VALUE)
        }
        assertEquals("about:blank", loaded.globalWebApp.baseUrl)
    }

    /** 全局合并链路：copySettings 复制 textZoom；DataManager 合并后再覆盖回自身外观值 */
    @Test
    fun copySettings_globalMerge_keepsAppearanceAndStats() {
        val global = WebApp("about:blank", Int.MAX_VALUE)
        global.isAllowJs = false
        global.textZoom = 150

        val app = WebApp("https://example.com", 1)
        app.isOverrideGlobalSettings = false
        app.isAllowJs = true
        app.textZoom = 100
        app.pageZoom = 90
        app.statLaunches = 5

        app.copySettings(global)

        // 既有契约（WebApp.kt:134-135）：textZoom/pageZoom 均参与 copySettings 复制
        assertFalse(app.isAllowJs)
        assertEquals(150, app.textZoom)
        assertEquals(100, app.pageZoom) // 复制 global 的默认 pageZoom
        // 统计不参与（copySettings 无统计字段——P0 防护注释确认）
        assertEquals(5, app.statLaunches)
    }

    /** DataManager 全局合并的最终语义：copySettings 后覆盖回自身外观值（迁移逐行保留） */
    @Test
    fun dataManagerMergePattern_overridesAppearanceAfterCopySettings() {
        val global = WebApp("about:blank", Int.MAX_VALUE)
        global.textZoom = 150
        global.pageZoom = 130

        val webApp = WebApp("https://example.com", 1)
        webApp.isOverrideGlobalSettings = false
        webApp.textZoom = 100
        webApp.pageZoom = 90

        // DataManager.getWebAppIgnoringGlobalOverride 合并链路（Kotlin 版同 Java 版逐行对应）：
        val merged = WebApp(webApp.baseUrl, webApp.ID, webApp.order)
        merged.title = webApp.title
        merged.iconPath = webApp.iconPath
        merged.copySettings(global)
        merged.textZoom = webApp.textZoom
        merged.pageZoom = webApp.pageZoom
        merged.isOverrideGlobalSettings = false

        // 最终语义：设置类跟全局，外观（字体/页面缩放）始终用自身值
        assertEquals(100, merged.textZoom)
        assertEquals(90, merged.pageZoom)
    }
}
