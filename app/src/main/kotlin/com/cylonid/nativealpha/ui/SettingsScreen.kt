package com.cylonid.nativealpha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.appcompat.app.AppCompatDelegate
import android.app.Activity
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.GlobalSettings
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.ThemeUtils

/**
 * 全局设置页 Compose 组件：
 * - 分区块卡片：通用（全局 WebApp / 语言 / UI 模式 / 浏览行为）/ 备份（导出/导入）
 * - 语言 / UI 模式用下拉菜单，行为开关用 Switch
 * - 保存按钮由 Activity 层处理
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsScreen(
    onBack: () -> Unit,
    onSave: (GlobalSettings) -> Unit,
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
    onExportAppErrors: () -> Unit = {},
    onCheckUpdate: (onDone: () -> Unit) -> Unit = { _ -> },
    onGlobalWebApp: () -> Unit = {},
) {
    val settings = DataManager.getInstance().settings
    var modified by remember { mutableStateOf(settings.copy()) }
    val context = LocalContext.current

    // 语言选择（跟随系统 / 中文 / English）
    val langOptions = context.resources.getStringArray(R.array.language_options)
    val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    var langSelection by remember {
        mutableStateOf(
            when {
                currentLang.isEmpty() -> 0
                currentLang.startsWith("zh") -> 1
                else -> 2
            }
        )
    }
    var langExpanded by remember { mutableStateOf(false) }

    // 检查更新 loading：请求期间行内转圈+禁点（UpdateChecker.checking 防逻辑重复，
    // 此处状态驱动 UI——用户能看见"正在检查"而不是点了没反应）
    var updateChecking by remember { mutableStateOf(false) }

    // UI 模式（跟随系统 / 浅色 / 深色）
    val uiModes = context.resources.getStringArray(R.array.ui_modes)
    var uiSelection by remember { mutableStateOf(modified.themeId.coerceIn(0, uiModes.size - 1)) }
    var uiExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.global_settings)) },
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
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = { onSave(modified) },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.save)) }
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
            // 通用区块
            SettingsSectionTitle(stringResource(R.string.general))
            SettingsCard {
                SettingsActionRow(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    title = stringResource(R.string.global_web_app_settings),
                    subtitle = stringResource(R.string.these_settings_are_applied_globally_and_override_app_specific_settings),
                    onClick = onGlobalWebApp
                )

                HorizontalDivider()

                // 语言
                SettingsLabeledControl(
                    label = stringResource(R.string.language),
                    icon = { Icon(Icons.Default.Language, contentDescription = null) }
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { langExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(langOptions[langSelection]) }
                        DropdownMenu(expanded = langExpanded, onDismissRequest = { langExpanded = false }) {
                            langOptions.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        langExpanded = false
                                        langSelection = index
                                        val tags = when (index) {
                                            0 -> LocaleListCompat.getEmptyLocaleList()
                                            1 -> LocaleListCompat.forLanguageTags("zh")
                                            else -> LocaleListCompat.forLanguageTags("en")
                                        }
                                        AppCompatDelegate.setApplicationLocales(tags)
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // UI 模式
                SettingsLabeledControl(
                    label = stringResource(R.string.select_ui_mode),
                    icon = { Icon(Icons.Default.Palette, contentDescription = null) }
                ) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { uiExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(uiModes[uiSelection]) }
                        DropdownMenu(expanded = uiExpanded, onDismissRequest = { uiExpanded = false }) {
                            uiModes.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        uiExpanded = false
                                        uiSelection = index
                                        modified = modified.copy(themeId = index)
                                        // 立即应用：落库 + 同步 AppCompat + 重建当前 Activity（resolveTheme 按新值选主题）
                                        DataManager.getInstance().settings = modified
                                        ThemeUtils.applyUiMode()
                                        (context as? Activity)?.recreate()
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // 浏览行为开关
                SettingsSwitchRow(
                    title = stringResource(R.string.clear_cache_after_usage),
                    checked = modified.isClearCache,
                    onCheckedChange = { modified = modified.copy(isClearCache = it) },
                    description = stringResource(R.string.desc_clear_cache)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.global_settings_multitouch_reload),
                    checked = modified.isMultitouchReload,
                    onCheckedChange = { modified = modified.copy(isMultitouchReload = it) },
                    description = stringResource(R.string.desc_multitouch_reload)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.use_two_finger_swipes_for_browser_forward_and_backward_navigation),
                    checked = modified.isTwoFingerMultitouch,
                    onCheckedChange = { modified = modified.copy(isTwoFingerMultitouch = it) },
                    description = stringResource(R.string.desc_two_finger_nav)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.use_three_finger_swipes_to_switch_between_web_apps_experimental),
                    checked = modified.isThreeFingerMultitouch,
                    onCheckedChange = { modified = modified.copy(isThreeFingerMultitouch = it) },
                    description = stringResource(R.string.desc_three_finger_switch)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.show_progress_bar_during_page_load),
                    checked = modified.isShowProgressbar,
                    onCheckedChange = { modified = modified.copy(isShowProgressbar = it) },
                    description = stringResource(R.string.desc_show_progressbar)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.always_show_software_buttons),
                    checked = modified.alwaysShowSoftwareButtons,
                    onCheckedChange = { modified = modified.copy(alwaysShowSoftwareButtons = it) },
                    description = stringResource(R.string.desc_always_show_buttons)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 安全区块（作用于全局 WebApp 模板，新 WebApp 默认继承）
            SettingsSectionTitle(stringResource(R.string.security_section))
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.disable_file_access),
                    checked = modified.globalWebApp.isFileAccessDisabled,
                    onCheckedChange = { modified = modified.copy(globalWebApp = modified.globalWebApp.apply { isFileAccessDisabled = it }) },
                    description = stringResource(R.string.desc_disable_file_access)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.disable_content_access),
                    checked = modified.globalWebApp.isContentAccessDisabled,
                    onCheckedChange = { modified = modified.copy(globalWebApp = modified.globalWebApp.apply { isContentAccessDisabled = it }) },
                    description = stringResource(R.string.desc_disable_content_access)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.block_mixed_content),
                    checked = modified.globalWebApp.isMixedContentBlocked,
                    onCheckedChange = { modified = modified.copy(globalWebApp = modified.globalWebApp.apply { isMixedContentBlocked = it }) },
                    description = stringResource(R.string.desc_block_mixed_content)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.restrict_js_popups),
                    checked = modified.globalWebApp.isJsPopupsRestricted,
                    onCheckedChange = { modified = modified.copy(globalWebApp = modified.globalWebApp.apply { isJsPopupsRestricted = it }) },
                    description = stringResource(R.string.desc_restrict_js_popups)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.enable_safe_browsing),
                    checked = modified.globalWebApp.isSafeBrowsing,
                    onCheckedChange = { modified = modified.copy(globalWebApp = modified.globalWebApp.apply { isSafeBrowsing = it }) },
                    description = stringResource(R.string.desc_enable_safe_browsing)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 备份区块
            SettingsSectionTitle(stringResource(R.string.backup))
            SettingsCard {
                SettingsActionRow(
                    icon = { Icon(Icons.Default.Backup, contentDescription = null) },
                    title = stringResource(R.string.export_settings_web_apps),
                    onClick = onExport
                )
                HorizontalDivider()
                SettingsActionRow(
                    icon = { Icon(Icons.Default.Restore, contentDescription = null) },
                    title = stringResource(R.string.import_settings_web_apps),
                    onClick = onImport
                )
                HorizontalDivider()
                SettingsActionRow(
                    icon = { Icon(Icons.Default.BugReport, contentDescription = null) },
                    title = stringResource(R.string.export_app_errors),
                    subtitle = stringResource(R.string.desc_export_app_errors),
                    onClick = onExportAppErrors
                )
                HorizontalDivider()
                SettingsActionRow(
                    icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                    title = if (updateChecking) stringResource(R.string.update_checking)
                            else stringResource(R.string.check_update),
                    subtitle = stringResource(R.string.desc_check_update),
                    enabled = !updateChecking,
                    trailing = {
                        if (updateChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    onClick = {
                        if (updateChecking) return@SettingsActionRow
                        updateChecking = true
                        onCheckUpdate { updateChecking = false }
                    }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun SettingsActionRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(12.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
        trailing()
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: (@Composable () -> Unit)? = null,
    description: String? = null,
    warning: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) { icon() }
                Spacer(modifier = Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (description != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
        // 警示提示（可能导致页面异常的高风险开关）
        if (warning != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SettingsLabeledControl(
    label: String,
    icon: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) { icon() }
                Spacer(modifier = Modifier.width(14.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(modifier = Modifier.height(8.dp))
        content()
    }
}
