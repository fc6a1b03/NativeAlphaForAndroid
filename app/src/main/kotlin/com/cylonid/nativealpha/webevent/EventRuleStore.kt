package com.cylonid.nativealpha.webevent

import android.content.Context
import android.annotation.SuppressLint
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 网页事件规则存储（P5，规格 §5.2「持久化自持」+ P4 单写者范式）。
 *
 * 自持 DataStore `webevent_rules`：独立文件、不参与宿主备份。三个 key：
 * - rules：规则 JSON 数组
 * - muted_sites：站点级静音集合 JSON（P5-4：站点级标志，逐规则 enabled 独立）
 * - （无 per-site 独立文件——单文件三 key 事务一致）
 *
 * 纪律（P4 沉淀）：
 * - 内存缓存 StateFlow：onTrimMemory 等主线程消费方禁同步读 DataStore（P5-7）
 * - 单写者 conflate 队列：变更即写且按序、最新胜出（P4 实测写竞态教训）
 * - 损坏 JSON fail-safe 回默认（不崩溃不阻塞 UI）
 */
internal object EventRuleStore {

    private val Context.webeventDataStore by preferencesDataStore(name = "webevent_rules")

    private val KEY_RULES = stringPreferencesKey("webevent_rules_json")
    private val KEY_MUTED_SITES = stringPreferencesKey("webevent_muted_sites_json")

    private val gson = com.google.gson.Gson()
    private val rulesType = object : TypeToken<List<EventRule>>() {}.type
    private val mutedType = object : TypeToken<Set<Int>>() {}.type

    /** 内存缓存（进程级；初始化异步首载，写入走单写者队列） */
    private val _rules = MutableStateFlow<List<EventRule>>(emptyList())
    val rules: StateFlow<List<EventRule>> = _rules.asStateFlow()

    private val _mutedSites = MutableStateFlow<Set<Int>>(emptySet())
    val mutedSites: StateFlow<Set<Int>> = _mutedSites.asStateFlow()

    /** 单写者持久化队列（conflate：写期间新快照合并，最新胜出且有序） */
    private val persistQueue = MutableStateFlow<Snapshot?>(null)

    private data class Snapshot(val rules: List<EventRule>, val muted: Set<Int>)

    @SuppressLint("StaticFieldLeak") // 持有 App.onCreate 注入的 applicationContext，非 Activity Context
    private var contextRef: Context? = null

    /**
     * 初始化（App.onCreate 幂等调用）：载入磁盘快照到内存缓存 + 启动
     * 单写者落盘循环。
     */
    fun init(appContext: Context) {
        if (contextRef != null) return
        contextRef = appContext
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Main.immediate)
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { readSnapshot(appContext) }
            _rules.value = loaded.first
            _mutedSites.value = loaded.second
        }
        scope.launch {
            persistQueue.collect { snapshot ->
                if (snapshot != null) {
                    withContext(Dispatchers.IO) { writeSnapshot(appContext, snapshot) }
                }
            }
        }
    }

    /** 站点是否有「生效中」规则（enabled 且站点未静音）——豁免判定入口（P5-1） */
    fun hasActiveRules(webappId: Int): Boolean =
        webappId !in _mutedSites.value && _rules.value.any {
            it.webappId == webappId && it.enabled
        }

    /** 全量规则（编辑器列表态） */
    fun rulesForSite(webappId: Int): List<EventRule> =
        _rules.value.filter { it.webappId == webappId }.sortedBy { it.createdAt }

    fun isSiteMuted(webappId: Int): Boolean = webappId in _mutedSites.value

    /** 保存（新增或替换，按 id）；超上限拒绝并返回 false */
    fun saveRule(rule: EventRule): Boolean {
        val current = _rules.value
        val replaced = current.any { it.id == rule.id }
        if (!replaced) {
            val siteCount = current.count { it.webappId == rule.webappId }
            if (siteCount >= EventRule.MAX_RULES_PER_SITE) return false
        }
        _rules.value = (current.filter { it.id != rule.id } + rule)
            .sortedBy { it.createdAt }
        enqueuePersist()
        return true
    }

    /** 删除单条（长按菜单） */
    fun deleteRule(ruleId: String) {
        _rules.value = _rules.value.filter { it.id != ruleId }
        enqueuePersist()
    }

    /** 切换单条启用 */
    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        _rules.value = _rules.value.map { if (it.id == ruleId) it.copy(enabled = enabled) else it }
        enqueuePersist()
    }

    /** 站点级静音切换（P5-4） */
    fun setSiteMuted(webappId: Int, muted: Boolean) {
        _mutedSites.value = if (muted) {
            _mutedSites.value + webappId
        } else {
            _mutedSites.value - webappId
        }
        enqueuePersist()
    }

    /** 级联删除站点全部规则（P5-3：删除站点调用；无变更时零写盘） */
    fun cascadeDeleteForSite(webappId: Int) {
        val rulesBefore = _rules.value
        val mutedBefore = webappId in _mutedSites.value
        _rules.value = rulesBefore.filter { it.webappId != webappId }
        _mutedSites.value = _mutedSites.value - webappId
        if (_rules.value != rulesBefore || mutedBefore) {
            enqueuePersist()
        }
    }

    /** 备份导入恢复（C1）：整体替换内存态并落盘（走单写者队列保持纪律） */
    fun restoreForBackup(rules: List<EventRule>, mutedSites: Set<Int>) {
        _rules.value = rules.sortedBy { it.createdAt }
        _mutedSites.value = mutedSites
        enqueuePersist()
    }

    private fun enqueuePersist() {
        persistQueue.value = Snapshot(_rules.value.toList(), _mutedSites.value.toSet())
    }

    // ===== 磁盘 IO（IO 线程；损坏 fail-safe） =====

    private suspend fun readSnapshot(context: Context): Pair<List<EventRule>, Set<Int>> {
        val prefs = context.webeventDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()
        val rules = decodeList(prefs[KEY_RULES], rulesType, emptyList<EventRule>())
        val muted = decodeList(prefs[KEY_MUTED_SITES], mutedType, emptySet<Int>())
        return rules to muted
    }

    private suspend fun writeSnapshot(context: Context, snapshot: Snapshot) {
        context.webeventDataStore.edit { prefs ->
            prefs[KEY_RULES] = gson.toJson(snapshot.rules)
            prefs[KEY_MUTED_SITES] = gson.toJson(snapshot.muted)
        }
    }

    private inline fun <reified T> decodeList(json: String?, type: java.lang.reflect.Type, fallback: T): T {
        if (json.isNullOrEmpty()) return fallback
        return try {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson<Any>(json, type) as? T ?: fallback
        } catch (ignored: JsonParseException) {
            fallback
        }
    }
}
