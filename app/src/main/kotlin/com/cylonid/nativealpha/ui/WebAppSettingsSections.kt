package com.cylonid.nativealpha.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp

/**
 * WebApp 设置页的分区块 UI（从 WebAppSettingsScreen.kt 拆出）：
 * - 每个区块一个 internal Composable，读主文件传入的工作副本，通过
 *   updateSettings / update 回写（状态持有仍在主文件 WebAppSettingsScreen）
 * - 区块顺序即渲染顺序，由主文件编排
 */

/** 基本信息（名称/URL/头像/快捷方式重建/覆盖全局/同步全局），仅非全局显示 */
@Composable
internal fun BasicInfoSection(
    modified: WebApp,
    update: (WebApp.() -> Unit) -> Unit,
    updateSettings: (WebApp.() -> Unit) -> Unit,
    syncFromGlobal: () -> Unit,
    onRecreateShortcut: () -> Unit,
) {
    val context = LocalContext.current
    // 预取字符串（避免在 lambda 里查询资源触发 lint）
    val msgSynced = stringResource(R.string.synced_global_settings)
    WebAppSettingsSectionTitle(stringResource(R.string.label))
    WebAppSettingsCard {
        // 名称（WebApp 本身的显示名称：主界面/快捷方式显示的就是它）。
        // 输入实时镜像本地 state；失焦时空值回退旧名——原 ifBlank 在每次
        // onValueChange 强行保持旧值，删空后 value 与 IME 缓冲撕裂导致输入锁死
        var nameInput by remember(modified.ID) { mutableStateOf(modified.title) }
        OutlinedTextField(
            value = nameInput,
            // 非空实时回写（保存兜底：不点别处直接保存也不丢输入）；
            // 空值只留本地（失焦时回退旧名）
            onValueChange = { nameInput = it; if (it.isNotBlank()) update { title = it } },
            label = { Text(stringResource(R.string.label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                // 失焦回写：非空提交到 modified；空值回退旧名（title 必填）
                .onFocusChanged { focus ->
                    if (!focus.isFocused) {
                        val trimmed = nameInput.trim()
                        if (trimmed.isEmpty()) nameInput = modified.title
                        else update { title = trimmed }
                    }
                }
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
        // 应用头像（统一源 iconPath）：预览 + 点击选择/回填/重置
        HorizontalDivider()
        IconSettingsRow(
            webApp = modified,
            onIconSaved = { savedPath -> update { this.iconPath = savedPath } }
        )
        // 快捷方式重建
        HorizontalDivider()
        WebAppSettingsActionRow(
            icon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
            title = stringResource(R.string.re_create_shortcut),
            onClick = onRecreateShortcut
        )
        // 覆盖全局设置开关（语义：开=应用设置为主，关=跟随全局）
        HorizontalDivider()
        WebAppSettingsSwitchRow(
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
        WebAppSettingsActionRow(
            icon = { Icon(Icons.Default.Sync, contentDescription = null) },
            title = stringResource(R.string.sync_global_settings),
            onClick = {
                syncFromGlobal()
                Toast.makeText(context, msgSynced, Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/** 安全与隐私区块：JS/第三方请求/HTTP/定位/DRM/相机/麦克风 + 安全加固组 */
@Composable
internal fun SecuritySection(
    modified: WebApp,
    updateSettings: (WebApp.() -> Unit) -> Unit,
) {
    WebAppSettingsSectionTitle(stringResource(R.string.webapp_section_security))
    WebAppSettingsCard {
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.allow_javascript),
            checked = modified.isAllowJs,
            onCheckedChange = { updateSettings { isAllowJs = it } },
            description = stringResource(R.string.desc_allow_js),
            warning = if (!modified.isAllowJs) stringResource(R.string.warning_js_disabled) else null
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.block_all_third_party_requests),
            checked = modified.isBlockThirdPartyRequests,
            onCheckedChange = { updateSettings { isBlockThirdPartyRequests = it } },
            description = stringResource(R.string.desc_block_third_party),
            warning = if (modified.isBlockThirdPartyRequests) stringResource(R.string.warning_block_third_party) else null
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.allow_http),
            checked = modified.isAllowHttp,
            onCheckedChange = { updateSettings { isAllowHttp = it } },
            description = stringResource(R.string.desc_allow_http)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.allow_location_access),
            checked = modified.isAllowLocationAccess,
            onCheckedChange = { updateSettings { isAllowLocationAccess = it } },
            description = stringResource(R.string.desc_allow_location)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.allow_drm_content),
            checked = modified.isDrmAllowed,
            onCheckedChange = { updateSettings { isDrmAllowed = it } },
            description = stringResource(R.string.desc_allow_drm)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.allow_camera_access),
            checked = modified.isCameraPermission,
            onCheckedChange = { updateSettings { isCameraPermission = it } },
            description = stringResource(R.string.desc_allow_camera)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.allow_microphone_access),
            checked = modified.isMicrophonePermission,
            onCheckedChange = { updateSettings { isMicrophonePermission = it } },
            description = stringResource(R.string.desc_allow_microphone)
        )
        // 安全加固（默认全开：禁用文件/内容访问、拦截混合内容、限制 JS 弹窗、Safe Browsing 默认关）
        HorizontalDivider()
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.disable_file_access),
            checked = modified.isFileAccessDisabled,
            onCheckedChange = { updateSettings { isFileAccessDisabled = it } },
            description = stringResource(R.string.desc_disable_file_access)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.disable_content_access),
            checked = modified.isContentAccessDisabled,
            onCheckedChange = { updateSettings { isContentAccessDisabled = it } },
            description = stringResource(R.string.desc_disable_content_access)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.block_mixed_content),
            checked = modified.isMixedContentBlocked,
            onCheckedChange = { updateSettings { isMixedContentBlocked = it } },
            description = stringResource(R.string.desc_block_mixed_content)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.restrict_js_popups),
            checked = modified.isJsPopupsRestricted,
            onCheckedChange = { updateSettings { isJsPopupsRestricted = it } },
            description = stringResource(R.string.desc_restrict_js_popups)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.enable_safe_browsing),
            checked = modified.isSafeBrowsing,
            onCheckedChange = { updateSettings { isSafeBrowsing = it } },
            description = stringResource(R.string.desc_enable_safe_browsing)
        )
    }
}

/** Cookies 区块：接受/第三方（依赖接受开关）/会话隔离 */
@Composable
internal fun CookiesSection(
    modified: WebApp,
    updateSettings: (WebApp.() -> Unit) -> Unit,
) {
    WebAppSettingsSectionTitle(stringResource(R.string.webapp_section_cookies))
    WebAppSettingsCard {
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.accept_cookies),
            checked = modified.isAllowCookies,
            onCheckedChange = { updateSettings { isAllowCookies = it } },
            description = stringResource(R.string.desc_accept_cookies)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.accept_third_party_cookies),
            checked = modified.isAllowThirdPartyCookies,
            enabled = modified.isAllowCookies,
            onCheckedChange = { updateSettings { isAllowThirdPartyCookies = it } },
            description = stringResource(R.string.desc_accept_third_party_cookies)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.isolated_session),
            checked = modified.isIsolatedSession,
            onCheckedChange = { updateSettings { isIsolatedSession = it } },
            description = stringResource(R.string.isolated_session_hint)
        )
    }
}

/** 深色模式区块：强制深色 + 时间段限制（起/止时间选择） */
@Composable
internal fun DarkModeSection(
    modified: WebApp,
    updateSettings: (WebApp.() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    WebAppSettingsSectionTitle(stringResource(R.string.dark_mode))
    WebAppSettingsCard {
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.force_dark_mode),
            checked = modified.isForceDarkMode,
            onCheckedChange = { updateSettings { isForceDarkMode = it } },
            description = stringResource(R.string.desc_force_dark)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.limit_dark_mode_to_time_span),
            checked = modified.isUseTimespanDarkMode,
            enabled = modified.isForceDarkMode,
            onCheckedChange = { updateSettings { isUseTimespanDarkMode = it } },
            description = stringResource(R.string.desc_timespan_dark)
        )
        if (modified.isUseTimespanDarkMode) {
            WebAppSettingsTimeRow(
                label = stringResource(R.string.begin),
                value = modified.timespanDarkModeBegin ?: "22:00",
                onClick = { current ->
                    showTimePicker(context, current) { updateSettings { timespanDarkModeBegin = it } }
                }
            )
            WebAppSettingsTimeRow(
                label = stringResource(R.string.end),
                value = modified.timespanDarkModeEnd ?: "06:00",
                onClick = { current ->
                    showTimePicker(context, current) { updateSettings { timespanDarkModeEnd = it } }
                }
            )
        }
    }
}

/** 数据节省区块：Save-Data 请求 + 禁图 */
@Composable
internal fun DataSavingSection(
    modified: WebApp,
    updateSettings: (WebApp.() -> Unit) -> Unit,
) {
    WebAppSettingsSectionTitle(stringResource(R.string.webapp_section_datasaving))
    WebAppSettingsCard {
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.request_data_saving_page),
            checked = modified.isSendSavedataRequest,
            onCheckedChange = { updateSettings { isSendSavedataRequest = it } },
            description = stringResource(R.string.desc_save_data)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.do_not_load_images),
            checked = modified.isBlockImages,
            onCheckedChange = { updateSettings { isBlockImages = it } },
            description = stringResource(R.string.desc_block_images),
            warning = if (modified.isBlockImages) stringResource(R.string.warning_block_images) else null
        )
    }
}

/** 自动刷新区块：开关 + 间隔输入（开时显示） */
@Composable
internal fun AutoReloadSection(
    modified: WebApp,
    updateSettings: (WebApp.() -> Unit) -> Unit,
) {
    WebAppSettingsSectionTitle(stringResource(R.string.webapp_autoreload))
    WebAppSettingsCard {
        WebAppSettingsSwitchRow(
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
}

/** 信息亭区块：全屏 + 保持唤醒 */
@Composable
internal fun KioskSection(
    modified: WebApp,
    updateSettings: (WebApp.() -> Unit) -> Unit,
) {
    WebAppSettingsSectionTitle(stringResource(R.string.webapp_section_kiosk))
    WebAppSettingsCard {
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.show_fullscreen),
            checked = modified.isShowFullscreen,
            onCheckedChange = { updateSettings { isShowFullscreen = it } },
            description = stringResource(R.string.desc_fullscreen)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.keep_screen_awake),
            checked = modified.isKeepAwake,
            onCheckedChange = { updateSettings { isKeepAwake = it } },
            description = stringResource(R.string.desc_keep_awake)
        )
    }
}

/** 其他区块：桌面版/外链/缩放手势/字体与页面缩放滑杆/标准菜单/媒体后台 */
@Composable
internal fun MiscSection(
    modified: WebApp,
    updateSettings: (WebApp.() -> Unit) -> Unit,
) {
    WebAppSettingsSectionTitle(stringResource(R.string.webapp_section_misc))
    WebAppSettingsCard {
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.request_website_in_desktop_version),
            checked = modified.isRequestDesktop,
            onCheckedChange = { updateSettings { isRequestDesktop = it } },
            description = stringResource(R.string.desc_request_desktop)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.open_external_links_in_browser_app),
            checked = modified.isOpenUrlExternal,
            onCheckedChange = { updateSettings { isOpenUrlExternal = it } },
            description = stringResource(R.string.desc_open_external)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.activate_two_finger_zoom),
            checked = modified.isEnableZooming,
            onCheckedChange = { updateSettings { isEnableZooming = it } },
            description = stringResource(R.string.desc_two_finger_zoom)
        )
        WebAppSettingsSliderRow(
            title = stringResource(R.string.text_zoom),
            description = stringResource(R.string.desc_text_zoom),
            value = modified.textZoom,
            onValueChange = { updateSettings { textZoom = it } }
        )
        WebAppSettingsSliderRow(
            title = stringResource(R.string.page_zoom),
            description = stringResource(R.string.desc_page_zoom),
            value = modified.pageZoom,
            onValueChange = { updateSettings { pageZoom = it } }
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.use_standard_context_menu_permanently),
            checked = modified.alwaysUseFallbackContextMenu,
            onCheckedChange = { updateSettings { alwaysUseFallbackContextMenu = it } },
            description = stringResource(R.string.desc_standard_menu)
        )
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.allow_media_playback_in_background),
            checked = modified.isAllowMediaPlaybackInBackground,
            onCheckedChange = { updateSettings { isAllowMediaPlaybackInBackground = it } },
            description = stringResource(R.string.desc_media_background)
        )
    }
}

/** 高级区块：总开关 + 自定义 UA + 忽略 SSL（开时展开） */
@Composable
internal fun ExpertSection(
    modified: WebApp,
    update: (WebApp.() -> Unit) -> Unit,
    updateSettings: (WebApp.() -> Unit) -> Unit,
) {
    WebAppSettingsSectionTitle(stringResource(R.string.expert_settings))
    WebAppSettingsCard {
        WebAppSettingsSwitchRow(
            title = stringResource(R.string.show_expert_settings),
            checked = modified.isShowExpertSettings,
            onCheckedChange = { updateSettings { isShowExpertSettings = it } },
            description = stringResource(R.string.desc_expert_settings)
        )
        if (modified.isShowExpertSettings) {
            HorizontalDivider()
            // 自定义 UA
            WebAppSettingsSwitchRow(
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
            WebAppSettingsSwitchRow(
                title = stringResource(R.string.ignore_ssl_errors),
                checked = modified.isIgnoreSslErrors,
                onCheckedChange = { updateSettings { isIgnoreSslErrors = it } },
                description = stringResource(R.string.desc_ignore_ssl),
                warning = if (modified.isIgnoreSslErrors) stringResource(R.string.warning_ignore_ssl) else null
            )
        }
    }
}
