package com.cylonid.nativealpha.model

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.view.Gravity
import android.widget.Toast
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.util.App
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.InvalidChecksumException
import com.cylonid.nativealpha.util.ShortcutIconUtils
import com.cylonid.nativealpha.util.Utility
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.TreeMap

/**
 * 数据中枢：WebApp 列表与全局设置的加载/持久化（SharedPreferences + Gson）、
 * 备份导入导出（版本化 JSON + SHA-256 校验和）。
 *
 * 单例形态为 companion + @JvmStatic（非 object）：80 处调用点全部为
 * `DataManager.getInstance().xxx()` 链式形态，保持 Java/Kotlin 调用方零改动。
 */
class DataManager private constructor() {

    private var websites = ArrayList<WebApp>()
    private var maxAssignedId = -1
    private var appdata: SharedPreferences? = null

    /** 是否已从 SharedPreferences 加载过数据（幂等优化：启动路径多次调用不再重复 Gson 解析）。
     * @Volatile：App.onCreate 后台预热线程与主线程首次 loadAppData 并发触发时，
     * 保证可见性——两侧幂等（重复解析结果一致），最坏代价是多解析一次，无双检锁必要 */
    @Volatile
    private var dataLoaded = false

    private var _settings = GlobalSettings()

    /**
     * 全局设置。自定义 setter 保留原 Java `setSettings()` 的副作用语义
     * （赋值即写回 SharedPreferences）；类内部加载路径赋值走 `_settings`
     * 绕过保存，与原 Java 直接字段赋值等价。
     */
    var settings: GlobalSettings
        get() = _settings
        set(value) {
            _settings = value
            saveGlobalSettings()
        }

    init {
        maxAssignedId = -1
    }

    fun saveWebAppData() {
        Utility.assertTrue(
            App.getAppContext() != null,
            "App.getAppContext() null before saving sharedpref"
        )

        appdata = App.getAppContext()
            .getSharedPreferences(SHARED_PREF_KEY, Context.MODE_PRIVATE)
        val editor = appdata!!.edit()
        val json = GSON.toJson(websites)
        editor.putString(SHARED_PREF_WEBAPP_DATA, json)
        editor.putInt(SHARED_PREF_MAX_ID, maxAssignedId)
        editor.apply()
    }

    fun getEulaData(): Boolean {
        return generalInfo.getBoolean(EULA_ACCEPTED, false)
    }

    fun getLastShownUpdate(): Int {
        return generalInfo.getInt(LAST_SHOWN_UPDATE, 0)
    }

    fun setEulaData(newValue: Boolean) {
        generalInfo.edit().putBoolean(EULA_ACCEPTED, newValue).apply()
    }

    fun setLastShownUpdate(newValue: Int) {
        generalInfo.edit().putInt(LAST_SHOWN_UPDATE, newValue).apply()
    }

    private val generalInfo: SharedPreferences
        get() {
            Utility.assertTrue(
                App.getAppContext() != null,
                "App.getAppContext() null before saving sharedpref"
            )
            return App.getAppContext()
                .getSharedPreferences(GENERAL_INFO, Context.MODE_PRIVATE)
        }

    private fun checkIfWebAppIdsCollide(
        oldWebApps: ArrayList<WebApp>,
        newWebApps: ArrayList<WebApp>
    ) {
        val end = minOf(oldWebApps.size, newWebApps.size)
        val shortcutsToBeRemoved = ArrayList<Int>()

        for (i in 0 until end) {
            val oldWebApp = oldWebApps.getOrNull(i)
            val newWebApp = newWebApps.getOrNull(i)
            if (oldWebApp != null && newWebApp != null) {
                if (oldWebApp.baseUrl != newWebApp.baseUrl) {
                    shortcutsToBeRemoved.add(newWebApp.ID)
                }
            }
        }

        ShortcutIconUtils.deleteShortcuts(shortcutsToBeRemoved, App.getAppContext())
    }

    fun loadAppData() {
        loadAppData(false)
    }

    /**
     * 加载应用数据。
     * @param force true 时强制重新解析（数据变更后调用，如设置保存/导入备份）
     */
    fun loadAppData(force: Boolean) {
        if (dataLoaded && !force) return

        Utility.assertTrue(
            App.getAppContext() != null,
            "App.getAppContext() null before loading sharedpref"
        )

        // force 重读时复用已有 SharedPreferences 实例：apply() 已把新值写入内存，
        // 重新 getSharedPreferences 会从磁盘读，可能读到 apply 尚未落盘的旧数据。
        if (appdata == null) {
            appdata = App.getAppContext()
                .getSharedPreferences(SHARED_PREF_KEY, Context.MODE_PRIVATE)
        }
        // Webapp data（D14：不做旧版兼容，直接默认 Gson 反序列化）
        if (appdata!!.contains(SHARED_PREF_WEBAPP_DATA)) {
            val json = appdata!!.getString(SHARED_PREF_WEBAPP_DATA, "")
            // Gson 不读 Kotlin 元数据：元素可能为 null（防御性 getOrNull/null 检查由此保留）
            val newWebsites =
                GSON.fromJson<ArrayList<WebApp>?>(json, TypeToken.getParameterized(
                    ArrayList::class.java, WebApp::class.java
                ).type)
            if (newWebsites != null) {
                checkIfWebAppIdsCollide(websites, newWebsites)
                // 旧数据迁移（v2.1.23 前 displayName 字段）：用户改过的名字 → 迁移回 title
                for (w in newWebsites) {
                    if (w != null && !w.legacyDisplayName.isNullOrEmpty()) {
                        w.title = w.legacyDisplayName!!
                        w.legacyDisplayName = null
                    }
                }
                websites = newWebsites
            }
        }

        maxAssignedId = appdata!!.getInt(SHARED_PREF_MAX_ID, maxAssignedId)

        if (appdata!!.getBoolean(SHARED_PREF_GLOBAL_SETTINGS_JSON, false)) {
            loadGlobalSettingsLegacy()
        }
        // Global settings（D14：不做旧版兼容，直接默认 Gson 反序列化）
        if (appdata!!.contains(SHARED_PREF_GLOBAL_SETTINGS)) {
            val json = appdata!!.getString(SHARED_PREF_GLOBAL_SETTINGS, "")
            val loaded = GSON.fromJson<GlobalSettings?>(json, GlobalSettings::class.java)
            if (loaded != null) {
                // 空值防护：JSON 缺失 globalWebApp 字段时 Gson 返回 null，补默认值防 NPE
                if (loaded.globalWebApp == null) {
                    loaded.globalWebApp = WebApp("about:blank", Int.MAX_VALUE)
                }
                _settings = loaded
                assertGlobalWebappData()
            }
        }

        dataLoaded = true
    }

    /** 迁移旧版散列全局设置（仅在检测到 globalSettingsStoredAsJson 键时触发） */
    fun loadGlobalSettingsLegacy() {
        _settings.isClearCache = appdata!!.getBoolean(SHARED_PREF_GLOB_CACHE, false)
        _settings.setClearCookies(appdata!!.getBoolean(SHARED_PREF_GLOB_COOKIE, false))
        _settings.isTwoFingerMultitouch =
            appdata!!.getBoolean(SHARED_PREF_GLOB_2F_MULTITOUCH, true)
        _settings.isMultitouchReload =
            appdata!!.getBoolean(SHARED_PREF_GLOB_MULTITOUCH_RELOAD, true)
        _settings.isThreeFingerMultitouch =
            appdata!!.getBoolean(SHARED_PREF_GLOB_3F_MULTITOUCH, false)
        _settings.isShowProgressbar =
            appdata!!.getBoolean(SHARED_PREF_GLOB_PROGRESSBAR, false)
        _settings.themeId = appdata!!.getInt(SHARED_PREF_GLOB_UI_THEME, 0)
    }

    fun saveGlobalSettings() {
        Utility.assertTrue(
            App.getAppContext() != null,
            "App.getAppContext() null before saving appdata to sharedpref"
        )

        appdata = App.getAppContext()
            .getSharedPreferences(SHARED_PREF_KEY, Context.MODE_PRIVATE)
        val editor = appdata!!.edit()

        val json = GSON.toJson(_settings)
        editor.putString(SHARED_PREF_GLOBAL_SETTINGS, json)
        editor.putBoolean(SHARED_PREF_GLOBAL_SETTINGS_JSON, true)
        editor.apply()
    }

    fun addWebsite(newSite: WebApp) {
        websites.add(newSite)
        saveWebAppData()
    }

    val incrementedID: Int
        get() = websites.size

    val incrementedOrder: Int
        get() = activeWebsitesCount + 1

    val activeWebsites: ArrayList<WebApp>
        get() {
            val activeWebapps = ArrayList<WebApp>()

            for (webapp in websites) {
                if (webapp.isActiveEntry) activeWebapps.add(webapp)
            }
            activeWebapps.sortBy { it.order }
            return activeWebapps
        }

    fun getWebApp(i: Int): WebApp? {
        return getWebAppIgnoringGlobalOverride(i, false)
    }

    fun getWebAppIgnoringGlobalOverride(i: Int, ignoreOverride: Boolean): WebApp? {
        loadAppData()
        return try {
            val webApp = websites[i]
            if (!webApp.isOverrideGlobalSettings && !ignoreOverride) {
                // 深拷贝后合并全局设置，避免污染原对象（原对象保留自身设置，供"应用设置为主"使用）
                val merged = WebApp(webApp.baseUrl, webApp.ID, webApp.order)
                merged.title = webApp.title
                // iconPath 不参与全局合并（copySettings 已排除）——深拷贝时单独保留（防图标丢失）
                merged.iconPath = webApp.iconPath
                merged.copySettings(_settings.globalWebApp)
                // 外观设置（字体/页面缩放）不参与全局合并：始终用 WebApp 自身的值
                merged.textZoom = webApp.textZoom
                merged.pageZoom = webApp.pageZoom
                merged.isOverrideGlobalSettings = false
                // 统计字段不参与 copySettings 合并：从原对象单独复制（防统计丢失）
                merged.statLaunches = webApp.statLaunches
                merged.statLoadTimeSum = webApp.statLoadTimeSum
                merged.statLoadTimeCount = webApp.statLoadTimeCount
                merged.statMaxLoadTime = webApp.statMaxLoadTime
                merged.statCacheHttpBytes = webApp.statCacheHttpBytes
                merged.statCacheStoreBytes = webApp.statCacheStoreBytes
                merged.statErrors = webApp.statErrors
                merged.statLastError = webApp.statLastError
                merged.statFirstLoadedAt = webApp.statFirstLoadedAt
                merged.statLastUsedAt = webApp.statLastUsedAt
                // 加载耗时明细 + 发送计数（新统计字段，合并时保留）
                if (webApp.statLoadTimes != null) {
                    merged.statLoadTimes = ArrayList(webApp.statLoadTimes)
                }
                if (webApp.keyShortcutSendCounts != null) {
                    merged.keyShortcutSendCounts = HashMap(webApp.keyShortcutSendCounts)
                }
                // 组合快捷键不参与 copySettings 合并：从原对象复制（每站独立）
                if (webApp.keyShortcuts != null) {
                    merged.keyShortcuts = ArrayList(webApp.keyShortcuts)
                }
                merged
            } else {
                webApp
            }
        } catch (e: IndexOutOfBoundsException) {
            val context = App.getAppContext()
            val toast = Toast.makeText(
                context,
                context.getString(R.string.webapp_not_found),
                Toast.LENGTH_LONG
            )
            toast.setGravity(Gravity.TOP, 0, 100)
            toast.show()
            null
        }
    }

    fun replaceWebApp(webapp: WebApp) {
        val index = webapp.ID
        websites[index] = webapp
        saveWebAppData()
    }

    val activeWebsitesCount: Int
        get() {
            var c = 0
            for (webapp in websites) {
                if (webapp.isActiveEntry) c += 1
            }
            return c
        }

    /** 导出：版本化 JSON（websites + settings + 校验和） */
    fun saveSharedPreferencesToFile(uri: Uri): Boolean {
        var result = false
        try {
            App.getAppContext().contentResolver.openOutputStream(uri).use { stream ->
                stream ?: return false
                appdata = App.getAppContext()
                    .getSharedPreferences(SHARED_PREF_KEY, Context.MODE_PRIVATE)
                loadAppData()

                // 备份内容：版本 + 导出时间 + Web Apps + 全局设置
                val backup = TreeMap<String, Any>()
                backup[BACKUP_KEY_VERSION] = BACKUP_FORMAT_VERSION
                backup["exportedAt"] = System.currentTimeMillis()
                backup[BACKUP_KEY_WEBSITES] = websites
                backup[BACKUP_KEY_SETTINGS] = _settings

                val json = GSON.toJson(backup)
                val checksum = sha256Hex(json)
                val payload = "{\"" + BACKUP_KEY_CHECKSUM + "\":\"" + checksum +
                    "\",\"" + BACKUP_KEY_DATA + "\":" + json + "}"

                stream.write(payload.toByteArray(StandardCharsets.UTF_8))
                result = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    /** 导入：校验和验证 + 版本化 JSON 解析（D15：不兼容旧版格式） */
    fun loadSharedPreferencesFromFile(uri: Uri): Boolean {
        var result = false
        try {
            App.getAppContext().contentResolver.openInputStream(uri).use { stream ->
                stream ?: return false
                val bytes = stream.readBytes()
                if (bytes.isEmpty()) return false
                val payload = String(bytes, StandardCharsets.UTF_8)

                // 解析外层 {checksum, data}
                val root = JsonParser.parseString(payload).asJsonObject
                if (!root.has(BACKUP_KEY_CHECKSUM) || !root.has(BACKUP_KEY_DATA)) {
                    return false // 非本应用备份格式
                }
                val checksum = root.get(BACKUP_KEY_CHECKSUM).asString
                val data = root.get(BACKUP_KEY_DATA).toString()

                // 校验和验证（防篡改/损坏）
                val newChecksum = sha256Hex(data)
                if (checksum != newChecksum) {
                    throw InvalidChecksumException(
                        "Checksums between backup and restored settings do not match."
                    )
                }

                // 解析数据体
                val dataObj = JsonParser.parseString(data).asJsonObject
                if (!dataObj.has(BACKUP_KEY_VERSION) ||
                    !dataObj.has(BACKUP_KEY_WEBSITES) ||
                    !dataObj.has(BACKUP_KEY_SETTINGS)
                ) {
                    return false // 数据体不完整
                }
                val version = dataObj.get(BACKUP_KEY_VERSION).asInt
                if (version != BACKUP_FORMAT_VERSION) {
                    throw InvalidChecksumException(
                        "Unsupported backup format version: " + version
                    )
                }

                val loadedWebsites = GSON.fromJson<ArrayList<WebApp>>(
                    dataObj.get(BACKUP_KEY_WEBSITES),
                    TypeToken.getParameterized(ArrayList::class.java, WebApp::class.java).type
                )
                val loadedSettings = GSON.fromJson<GlobalSettings>(
                    dataObj.get(BACKUP_KEY_SETTINGS), GlobalSettings::class.java
                )

                if (loadedWebsites != null) {
                    websites = loadedWebsites
                    saveWebAppData()
                }
                if (loadedSettings != null) {
                    if (loadedSettings.globalWebApp == null) {
                        loadedSettings.globalWebApp = WebApp("about:blank", Int.MAX_VALUE)
                    }
                    _settings = loadedSettings
                    saveGlobalSettings()
                }
                result = true
            }
        } catch (e: InvalidChecksumException) {
            // RuntimeException 覆盖 JsonSyntaxException（用户选了非备份文件）
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: RuntimeException) {
            e.printStackTrace()
        }
        return result
    }

    fun getSuccessor(i: Int): WebApp {
        val invalid = websites.size
        var neighbor = i
        do {
            neighbor = neighbor + 1
            if (neighbor == invalid) neighbor = 0
        } while (!websites[neighbor].isActiveEntry)
        return websites[neighbor]
    }

    fun getPredecessor(i: Int): WebApp {
        val invalid = -1
        var neighbor = i
        do {
            neighbor = neighbor - 1
            if (neighbor == invalid) neighbor = websites.size - 1
        } while (!websites[neighbor].isActiveEntry)
        return websites[neighbor]
    }

    private fun assertGlobalWebappData() {
        val override = _settings.globalWebApp.isOverrideGlobalSettings
        val container = _settings.globalWebApp.containerId
        if (!override || container != Const.NO_CONTAINER) {
            _settings.globalWebApp.isOverrideGlobalSettings = true
            _settings.globalWebApp.containerId = Const.NO_CONTAINER
            this.saveGlobalSettings()
        }
    }

    companion object {
        // Corresponds to app release version
        private const val SHARED_PREF_KEY = "WEBSITEDATA"
        private const val GENERAL_INFO = "com.cylonid.nativealpha.GENERAL_INFO"
        private const val EULA_ACCEPTED = "eulaAccepted"
        private const val LAST_SHOWN_UPDATE = "lastShownUpdate"
        private const val DATA_FORMAT = "dataFormat"

        private const val SHARED_PREF_MAX_ID = "MAX_ID"

        private const val SHARED_PREF_WEBAPP_DATA = "WEBSITEDATA"
        private const val SHARED_PREF_GLOBAL_SETTINGS = "GLOBALSETTINGS"

        // 迁移兼容：旧版本把全局设置散列存放在 Cache/Cookies/... 键下，
        // 新版本统一存 JSON（GLOBALSETTINGS）。仅在检测到 legacy 键时触发迁移。
        private const val SHARED_PREF_GLOB_CACHE = "Cache"
        private const val SHARED_PREF_GLOB_COOKIE = "Cookies"
        private const val SHARED_PREF_GLOB_2F_MULTITOUCH = "TwoFingerMultiTouch"
        private const val SHARED_PREF_GLOB_MULTITOUCH_RELOAD = "ReloadMultiTouch"
        private const val SHARED_PREF_GLOB_3F_MULTITOUCH = "ThreeFingerMultiTouch"
        private const val SHARED_PREF_GLOB_PROGRESSBAR = "LoadProgressBarAlwaysShown"
        private const val SHARED_PREF_GLOB_UI_THEME = "UITheme"
        private const val SHARED_PREF_GLOBAL_SETTINGS_JSON = "globalSettingsStoredAsJson"

        /** 备份格式版本（D15：版本化 JSON，不兼容旧版） */
        private const val BACKUP_FORMAT_VERSION = 2

        // 备份 JSON 键（导出/导入两侧共用——内联字符串一旦写读不一致即数据丢失）
        private const val BACKUP_KEY_CHECKSUM = "checksum"
        private const val BACKUP_KEY_DATA = "data"
        private const val BACKUP_KEY_VERSION = "version"
        private const val BACKUP_KEY_WEBSITES = "websites"
        private const val BACKUP_KEY_SETTINGS = "settings"

        /** 共享 Gson 实例（线程安全）——避免每次读写都新建（saveWebAppData 为热路径） */
        private val GSON = Gson()

        private val instance = DataManager()

        @JvmStatic
        fun getInstance(): DataManager {
            return instance
        }

        /**
         * SHA-256 摘要（十六进制）。备份完整性校验用。
         *
         * 注意：`%02x` 对负 byte 会符号扩展输出 8 位（Java 既有行为）——
         * 已有备份文件的 checksum 均按此算法生成，此处必须保持逐字节一致，
         * 禁止"修复"为 `b.toInt() and 0xFF`（否则旧备份全部校验失败）。
         */
        private fun sha256Hex(input: String): String {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
                val sb = StringBuilder(hash.size * 2)
                for (b in hash) {
                    sb.append(String.format("%02x", b))
                }
                sb.toString()
            } catch (e: Exception) {
                throw IllegalStateException("SHA-256 not available", e)
            }
        }
    }
}
