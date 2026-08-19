package com.cylonid.nativealpha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.appcompat.app.AppCompatDelegate
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.DataManager

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
    onSave: (com.cylonid.nativealpha.model.GlobalSettings) -> Unit,
    onExport: () -> Unit = {},
    onImport: () -> Unit = {},
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
                                        com.cylonid.nativealpha.util.ThemeUtils.applyUiMode()
                                        (context as? android.app.Activity)?.recreate()
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
                    onCheckedChange = { modified = modified.copy(isClearCache = it) }
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.global_settings_multitouch_reload),
                    checked = modified.isMultitouchReload,
                    onCheckedChange = { modified = modified.copy(isMultitouchReload = it) }
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.use_two_finger_swipes_for_browser_forward_and_backward_navigation),
                    checked = modified.isTwoFingerMultitouch,
                    onCheckedChange = { modified = modified.copy(isTwoFingerMultitouch = it) }
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.use_three_finger_swipes_to_switch_between_web_apps_experimental),
                    checked = modified.isThreeFingerMultitouch,
                    onCheckedChange = { modified = modified.copy(isThreeFingerMultitouch = it) }
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.show_progress_bar_during_page_load),
                    checked = modified.isShowProgressbar,
                    onCheckedChange = { modified = modified.copy(isShowProgressbar = it) }
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.always_show_software_buttons),
                    checked = modified.alwaysShowSoftwareButtons,
                    onCheckedChange = { modified = modified.copy(alwaysShowSoftwareButtons = it) }
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
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: (@Composable () -> Unit)? = null,
) {
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
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
