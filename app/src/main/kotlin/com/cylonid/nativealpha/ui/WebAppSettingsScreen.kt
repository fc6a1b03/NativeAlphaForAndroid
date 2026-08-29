package com.cylonid.nativealpha.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp

/**
 * 单个 WebApp 的设置页入口（Compose 化）：
 * - 状态编排：工作副本（neverEqualPolicy 强制刷新）+ 统一修改入口
 *   （update / updateSettings / syncFromGlobal），保存时由 Activity 层统一写入 DataManager
 * - 分区块 UI 见 WebAppSettingsSections.kt；基础行组件见 WebAppSettingsComponents.kt；
 *   快捷键点选编辑器见 ShortcutKeyEditor.kt
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebAppSettingsScreen(
    webapp: WebApp,
    isGlobal: Boolean,
    onBack: () -> Unit,
    onSave: (WebApp) -> Unit,
    onRecreateShortcut: () -> Unit,
) {
    // 全局设置模板（新 WebApp 与"同步全局"的来源）
    val globalTemplate = DataManager.getInstance().settings.globalWebApp
    // 工作副本：初始化时立即完整拷贝（避免首帧空窗导致保存不完整数据）
    // 关键：非全局且未覆盖时，显示全局设置的值（运行时实际生效的就是它）
    // 必须用 neverEqualPolicy：WebApp 的 data class equals 只比较 baseUrl/ID，
    // 设置字段变化时默认策略检测不到，开关/输入框永不刷新
    var modified by remember {
        mutableStateOf(
            WebApp(webapp.baseUrl, webapp.ID, webapp.order).apply {
                title = webapp.title
                iconPath = webapp.iconPath  // 初始化保留自身图标（copySettings(#source) 不含 iconPath）
                // 跟随全局时显示全局值，否则显示 WebApp 自己的值
                val source = if (!isGlobal && !webapp.isOverrideGlobalSettings) globalTemplate else webapp
                copySettings(source)
                isOverrideGlobalSettings = webapp.isOverrideGlobalSettings
                // 统计/快捷键不参与全局合并：始终保留 WebApp 自身值（防设置保存清空）
                copyStatsAndShortcuts(webapp)
            },
            neverEqualPolicy()
        )
    }
    // 统一修改入口（名称/URL 等 WebApp 自身字段）：不改变覆盖状态
    fun update(block: WebApp.() -> Unit) {
        val copy = WebApp(modified.baseUrl, modified.ID, modified.order).apply {
            title = modified.title
            iconPath = modified.iconPath  // iconPath 自身专有——副本显式保留（copySettings 不含）
            copySettings(modified)
            isOverrideGlobalSettings = modified.isOverrideGlobalSettings
            copyStatsAndShortcuts(modified)
        }
        copy.block()
        modified = copy
    }
    // 设置字段修改入口：任何设置变更自动切换为"应用设置为主"（覆盖全局）
    fun updateSettings(block: WebApp.() -> Unit) {
        val copy = WebApp(modified.baseUrl, modified.ID, modified.order).apply {
            title = modified.title
            iconPath = modified.iconPath  // 显式保留（copySettings 不含 iconPath）
            copySettings(modified)
            copyStatsAndShortcuts(modified)
        }
        copy.isOverrideGlobalSettings = true
        copy.block()
        modified = copy
    }
    // 同步全局设置：一键恢复跟随全局（复制全局模板值 + 关闭覆盖）
    fun syncFromGlobal() {
        if (isGlobal) return
        val copy = WebApp(modified.baseUrl, modified.ID, modified.order).apply {
            title = modified.title
            iconPath = modified.iconPath  // 同步全局不丢自身图标（copySettings(globalTemplate) 不含 iconPath）
            copySettings(globalTemplate)
            isOverrideGlobalSettings = false
            copyStatsAndShortcuts(modified)
        }
        modified = copy
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (isGlobal) R.string.global_web_app_settings else R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(onClick = { onSave(modified) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // 基本信息（仅非全局显示：全局模板的名称/URL 只是占位符，无实际用途，隐藏）
            if (!isGlobal) {
                BasicInfoSection(
                    modified = modified,
                    update = ::update,
                    updateSettings = ::updateSettings,
                    syncFromGlobal = ::syncFromGlobal,
                    onRecreateShortcut = onRecreateShortcut,
                )
            }
            // 安全与隐私
            SecuritySection(modified = modified, updateSettings = ::updateSettings)
            // Cookies
            CookiesSection(modified = modified, updateSettings = ::updateSettings)
            // 深色模式
            DarkModeSection(modified = modified, updateSettings = ::updateSettings)
            // 数据节省
            DataSavingSection(modified = modified, updateSettings = ::updateSettings)
            // 自动刷新
            AutoReloadSection(modified = modified, updateSettings = ::updateSettings)
            // 信息亭
            KioskSection(modified = modified, updateSettings = ::updateSettings)
            // 其他
            MiscSection(modified = modified, updateSettings = ::updateSettings)
            // 快捷键（手机点选录入，管理入口）
            ShortcutsSection(modified = modified, updateSettings = ::updateSettings)
            // 事件规则（P5：宿主唯一改动，规格 §5.3）
            if (!isGlobal) {
                com.cylonid.nativealpha.webevent.EventsEntrySection(webApp = webapp)
            }
            // 高级
            ExpertSection(modified = modified, update = ::update, updateSettings = ::updateSettings)
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
