package com.cylonid.nativealpha.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.DateUtils
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import java.util.Calendar

/**
 * 单个 WebApp 的设置页（Compose 化）：
 * - 分区块卡片：基本信息（名称/URL/快捷方式）/ 安全与隐私 / Cookies / 深色模式 /
 *   数据节省 / 自动刷新 / 信息亭 / 其他 / 高级
 * - 各开关直接修改传入的 WebApp 副本，保存时由 Activity 层统一写入 DataManager
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
                displayName = webapp.displayName
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
            displayName = modified.displayName
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
            displayName = modified.displayName
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
            displayName = modified.displayName
            copySettings(globalTemplate)
            isOverrideGlobalSettings = false
            copyStatsAndShortcuts(modified)
        }
        modified = copy
    }

    val context = LocalContext.current
    // 预取字符串（避免在 lambda 里查询资源触发 lint）
    val msgSynced = stringResource(R.string.synced_global_settings)

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
                SettingsSectionTitle(stringResource(R.string.label))
                SettingsCard {
                    // 名称（WebApp 本身的显示名称：主界面/快捷方式显示的就是它）
                    OutlinedTextField(
                        value = modified.displayName ?: modified.title,
                        onValueChange = { update { displayName = it.ifBlank { null } } },
                        label = { Text(stringResource(R.string.label)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    // URL
                    OutlinedTextField(
                        value = modified.baseUrl,
                        onValueChange = { update { baseUrl = it } },
                        label = { Text(stringResource(R.string.url)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    // 快捷方式重建
                    HorizontalDivider()
                    SettingsActionRow(
                        icon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                        title = stringResource(R.string.re_create_shortcut),
                        onClick = onRecreateShortcut
                    )
                    // 覆盖全局设置开关（语义：开=应用设置为主，关=跟随全局）
                    HorizontalDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.override_global_settings),
                        checked = modified.isOverrideGlobalSettings,
                        onCheckedChange = { checked ->
                            if (checked) {
                                // 打开覆盖：保持当前显示值，切换为应用设置为主
                                updateSettings { }
                            } else {
                                // 关闭覆盖：一键同步全局设置
                                syncFromGlobal()
                            }
                        }
                    )
                    // 同步全局设置按钮（快速恢复跟随全局）
                    HorizontalDivider()
                    SettingsActionRow(
                        icon = { Icon(Icons.Default.Sync, contentDescription = null) },
                        title = stringResource(R.string.sync_global_settings),
                        onClick = {
                            syncFromGlobal()
                            Toast.makeText(context, msgSynced, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            // 安全与隐私
            SettingsSectionTitle(stringResource(R.string.webapp_section_security))
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.allow_javascript),
                    checked = modified.isAllowJs,
                    onCheckedChange = { updateSettings { isAllowJs = it } },
                    description = stringResource(R.string.desc_allow_js),
                    warning = if (!modified.isAllowJs) stringResource(R.string.warning_js_disabled) else null
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.block_all_third_party_requests),
                    checked = modified.isBlockThirdPartyRequests,
                    onCheckedChange = { updateSettings { isBlockThirdPartyRequests = it } },
                    description = stringResource(R.string.desc_block_third_party),
                    warning = if (modified.isBlockThirdPartyRequests) stringResource(R.string.warning_block_third_party) else null
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.allow_http),
                    checked = modified.isAllowHttp,
                    onCheckedChange = { updateSettings { isAllowHttp = it } },
                    description = stringResource(R.string.desc_allow_http)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.allow_location_access),
                    checked = modified.isAllowLocationAccess,
                    onCheckedChange = { updateSettings { isAllowLocationAccess = it } },
                    description = stringResource(R.string.desc_allow_location)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.allow_drm_content),
                    checked = modified.isDrmAllowed,
                    onCheckedChange = { updateSettings { isDrmAllowed = it } },
                    description = stringResource(R.string.desc_allow_drm)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.allow_camera_access),
                    checked = modified.isCameraPermission,
                    onCheckedChange = { updateSettings { isCameraPermission = it } },
                    description = stringResource(R.string.desc_allow_camera)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.allow_microphone_access),
                    checked = modified.isMicrophonePermission,
                    onCheckedChange = { updateSettings { isMicrophonePermission = it } },
                    description = stringResource(R.string.desc_allow_microphone)
                )
                // 安全加固（默认全开：禁用文件/内容访问、拦截混合内容、限制 JS 弹窗、Safe Browsing 默认关）
                HorizontalDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.disable_file_access),
                    checked = modified.isFileAccessDisabled,
                    onCheckedChange = { updateSettings { isFileAccessDisabled = it } },
                    description = stringResource(R.string.desc_disable_file_access)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.disable_content_access),
                    checked = modified.isContentAccessDisabled,
                    onCheckedChange = { updateSettings { isContentAccessDisabled = it } },
                    description = stringResource(R.string.desc_disable_content_access)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.block_mixed_content),
                    checked = modified.isMixedContentBlocked,
                    onCheckedChange = { updateSettings { isMixedContentBlocked = it } },
                    description = stringResource(R.string.desc_block_mixed_content)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.restrict_js_popups),
                    checked = modified.isJsPopupsRestricted,
                    onCheckedChange = { updateSettings { isJsPopupsRestricted = it } },
                    description = stringResource(R.string.desc_restrict_js_popups)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.enable_safe_browsing),
                    checked = modified.isSafeBrowsing,
                    onCheckedChange = { updateSettings { isSafeBrowsing = it } },
                    description = stringResource(R.string.desc_enable_safe_browsing)
                )
            }

            // Cookies
            SettingsSectionTitle(stringResource(R.string.webapp_section_cookies))
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.accept_cookies),
                    checked = modified.isAllowCookies,
                    onCheckedChange = { updateSettings { isAllowCookies = it } },
                    description = stringResource(R.string.desc_accept_cookies)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.accept_third_party_cookies),
                    checked = modified.isAllowThirdPartyCookies,
                    enabled = modified.isAllowCookies,
                    onCheckedChange = { updateSettings { isAllowThirdPartyCookies = it } },
                    description = stringResource(R.string.desc_accept_third_party_cookies)
                )
            }

            // 深色模式
            SettingsSectionTitle(stringResource(R.string.dark_mode))
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.force_dark_mode),
                    checked = modified.isForceDarkMode,
                    onCheckedChange = { updateSettings { isForceDarkMode = it } },
                    description = stringResource(R.string.desc_force_dark)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.limit_dark_mode_to_time_span),
                    checked = modified.isUseTimespanDarkMode,
                    enabled = modified.isForceDarkMode,
                    onCheckedChange = { updateSettings { isUseTimespanDarkMode = it } },
                    description = stringResource(R.string.desc_timespan_dark)
                )
                if (modified.isUseTimespanDarkMode) {
                    SettingsTimeRow(
                        label = stringResource(R.string.begin),
                        value = modified.timespanDarkModeBegin ?: "22:00",
                        onClick = { current ->
                            showTimePicker(context, current) { updateSettings { timespanDarkModeBegin = it } }
                        }
                    )
                    SettingsTimeRow(
                        label = stringResource(R.string.end),
                        value = modified.timespanDarkModeEnd ?: "06:00",
                        onClick = { current ->
                            showTimePicker(context, current) { updateSettings { timespanDarkModeEnd = it } }
                        }
                    )
                }
            }

            // 数据节省
            SettingsSectionTitle(stringResource(R.string.webapp_section_datasaving))
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.request_data_saving_page),
                    checked = modified.isSendSavedataRequest,
                    onCheckedChange = { updateSettings { isSendSavedataRequest = it } },
                    description = stringResource(R.string.desc_save_data)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.do_not_load_images),
                    checked = modified.isBlockImages,
                    onCheckedChange = { updateSettings { isBlockImages = it } },
                    description = stringResource(R.string.desc_block_images),
                    warning = if (modified.isBlockImages) stringResource(R.string.warning_block_images) else null
                )
            }

            // 自动刷新
            SettingsSectionTitle(stringResource(R.string.webapp_autoreload))
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.webapp_autoreload_switch),
                    checked = modified.isAutoreload,
                    onCheckedChange = { updateSettings { isAutoreload = it } },
                    description = stringResource(R.string.desc_autoreload)
                )
                if (modified.isAutoreload) {
                    OutlinedTextField(
                        value = modified.timeAutoreload.toString(),
                        onValueChange = { updateSettings { timeAutoreload = it.toIntOrNull() ?: 0 } },
                        label = { Text(stringResource(R.string.webapp_interval_for_reload)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // 信息亭
            SettingsSectionTitle(stringResource(R.string.webapp_section_kiosk))
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.show_fullscreen),
                    checked = modified.isShowFullscreen,
                    onCheckedChange = { updateSettings { isShowFullscreen = it } },
                    description = stringResource(R.string.desc_fullscreen)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.keep_screen_awake),
                    checked = modified.isKeepAwake,
                    onCheckedChange = { updateSettings { isKeepAwake = it } },
                    description = stringResource(R.string.desc_keep_awake)
                )
            }

            // 其他
            SettingsSectionTitle(stringResource(R.string.webapp_section_misc))
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.request_website_in_desktop_version),
                    checked = modified.isRequestDesktop,
                    onCheckedChange = { updateSettings { isRequestDesktop = it } },
                    description = stringResource(R.string.desc_request_desktop)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.open_external_links_in_browser_app),
                    checked = modified.isOpenUrlExternal,
                    onCheckedChange = { updateSettings { isOpenUrlExternal = it } },
                    description = stringResource(R.string.desc_open_external)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.activate_two_finger_zoom),
                    checked = modified.isEnableZooming,
                    onCheckedChange = { updateSettings { isEnableZooming = it } },
                    description = stringResource(R.string.desc_two_finger_zoom)
                )
                SettingsSliderRow(
                    title = stringResource(R.string.text_zoom),
                    description = stringResource(R.string.desc_text_zoom),
                    value = modified.textZoom,
                    onValueChange = { updateSettings { textZoom = it } }
                )
                SettingsSliderRow(
                    title = stringResource(R.string.page_zoom),
                    description = stringResource(R.string.desc_page_zoom),
                    value = modified.pageZoom,
                    onValueChange = { updateSettings { pageZoom = it } }
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.use_standard_context_menu_permanently),
                    checked = modified.alwaysUseFallbackContextMenu,
                    onCheckedChange = { updateSettings { alwaysUseFallbackContextMenu = it } },
                    description = stringResource(R.string.desc_standard_menu)
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.allow_media_playback_in_background),
                    checked = modified.isAllowMediaPlaybackInBackground,
                    onCheckedChange = { updateSettings { isAllowMediaPlaybackInBackground = it } },
                    description = stringResource(R.string.desc_media_background)
                )
            }

            // 快捷键（手机点选录入，管理入口）
            SettingsSectionTitle(stringResource(R.string.shortcuts_section))
            SettingsCard {
                Text(
                    stringResource(R.string.shortcut_send_to_page),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                // 已绑定列表
                if (modified.keyShortcuts.isEmpty()) {
                    Text(
                        stringResource(R.string.shortcut_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                } else {
                    // 已绑定列表：自适应网格卡片（键名+删除紧凑，自动换行填满宽度）
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        modified.keyShortcuts.forEach { shortcut ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    shortcut,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                // 删除按钮紧贴卡片右侧
                                IconButton(
                                    onClick = {
                                        updateSettings { keyShortcuts = keyShortcuts.filter { it != shortcut }.toMutableList() }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.shortcut_delete_desc, shortcut),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                // 添加（点选录入）
                ShortcutAddRow(
                    existing = modified.keyShortcuts,
                    onAdd = { combo ->
                        updateSettings { keyShortcuts = (keyShortcuts + combo).toMutableList() }
                    }
                )
            }

            // 高级
            SettingsSectionTitle(stringResource(R.string.expert_settings))
            SettingsCard {
                SettingsSwitchRow(
                    title = stringResource(R.string.show_expert_settings),
                    checked = modified.isShowExpertSettings,
                    onCheckedChange = { updateSettings { isShowExpertSettings = it } },
                    description = stringResource(R.string.desc_expert_settings)
                )
                if (modified.isShowExpertSettings) {
                    HorizontalDivider()
                    // 自定义 UA
                    SettingsSwitchRow(
                        title = stringResource(R.string.use_custom_user_agent),
                        checked = modified.isUseCustomUserAgent,
                        onCheckedChange = { updateSettings { isUseCustomUserAgent = it } },
                        description = stringResource(R.string.desc_custom_ua)
                    )
                    if (modified.isUseCustomUserAgent) {
                        OutlinedTextField(
                            value = modified.userAgent ?: "",
                            onValueChange = { update { userAgent = it } },
                            label = { Text(stringResource(R.string.insert_custom_user_agent)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    // SSL
                    SettingsSwitchRow(
                        title = stringResource(R.string.ignore_ssl_errors),
                        checked = modified.isIgnoreSslErrors,
                        onCheckedChange = { updateSettings { isIgnoreSslErrors = it } },
                        description = stringResource(R.string.desc_ignore_ssl),
                        warning = if (modified.isIgnoreSslErrors) stringResource(R.string.warning_ignore_ssl) else null
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/** 时间选择行（深色模式时间段） */
@Composable
private fun SettingsTimeRow(
    label: String,
    value: String,
    onClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(value) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 显示时间选择器 */
private fun showTimePicker(
    context: android.content.Context,
    current: String,
    onResult: (String) -> Unit,
) {
    val c = DateUtils.convertStringToCalendar(current) ?: Calendar.getInstance()
    val picker = TimePickerDialog(
        context,
        { _, hour, minute ->
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            onResult(DateUtils.getHourMinFormat().format(cal.time))
        },
        c.get(Calendar.HOUR_OF_DAY),
        c.get(Calendar.MINUTE),
        true
    )
    picker.show()
}

// 复用 SettingsScreen.kt 的私有组件（同一包内可见）
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
        ) { icon() }
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
    enabled: Boolean = true,
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
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                if (description != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
        // 高风险开关警示：调用方传入 warning 时显示（JS 关闭 / 拦截开启 / 禁图开启）
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

/** 滑杆设置行（字体/页面缩放），50~200% 步进 10 */
@Composable
private fun SettingsSliderRow(
    title: String,
    description: String? = null,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            Text(
                "$value%",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(50, 200)) },
            valueRange = 50f..200f,
            steps = 14
        )
    }
}

/** 快捷键点选录入行（手机触摸操作，无需物理键盘） */
@Composable
private fun ShortcutAddRow(
    existing: List<String>,
    onAdd: (String) -> Unit,
) {
    val context = LocalContext.current
    var ctrl by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    // 主键下拉（字母/数字/功能键）
    var key by remember { mutableStateOf("S") }
    var keyExpanded by remember { mutableStateOf(false) }

    fun buildCombo(): String? {
        val parts = buildList {
            if (ctrl) add("Ctrl")
            if (shift) add("Shift")
            if (alt) add("Alt")
            add(key)
        }
        return if (parts.size >= 2) parts.joinToString("+") else null
    }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        // 修饰键点选
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = ctrl,
                onClick = { ctrl = !ctrl },
                label = { Text("Ctrl") }
            )
            FilterChip(
                selected = shift,
                onClick = { shift = !shift },
                label = { Text("Shift") }
            )
            FilterChip(
                selected = alt,
                onClick = { alt = !alt },
                label = { Text("Alt") }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 主键下拉 + 确认
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                OutlinedButton(onClick = { keyExpanded = true }) { Text(stringResource(R.string.shortcut_key_label, key)) }
                DropdownMenu(expanded = keyExpanded, onDismissRequest = { keyExpanded = false }) {
                    ShortcutKeys.forEach { k ->
                        DropdownMenuItem(
                            text = { Text(k) },
                            onClick = { key = k; keyExpanded = false }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    val combo = buildCombo() ?: run {
                        android.widget.Toast.makeText(context, context.getString(R.string.shortcut_need_modifier), android.widget.Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (existing.contains(combo)) {
                        android.widget.Toast.makeText(context, context.getString(R.string.shortcut_exist), android.widget.Toast.LENGTH_SHORT).show()
                    } else if (existing.size >= MAX_KEY_SHORTCUTS) {
                        android.widget.Toast.makeText(context, context.getString(R.string.shortcut_max_reached, MAX_KEY_SHORTCUTS), android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        onAdd(combo)
                        ctrl = false; shift = false; alt = false
                        android.widget.Toast.makeText(context, context.getString(R.string.shortcut_bound, combo), android.widget.Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = ctrl || shift || alt
            ) { Text("确认绑定") }
        }
    }
}

/** 每 WebApp 最大快捷键数（防冗余） */
private const val MAX_KEY_SHORTCUTS = 5

/** 可选主键（字母/数字/功能键） */
private val ShortcutKeys = listOf(
    "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
    "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
    "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
    "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12"
)
