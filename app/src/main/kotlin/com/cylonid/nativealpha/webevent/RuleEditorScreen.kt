package com.cylonid.nativealpha.webevent

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.DataManager
import kotlinx.coroutines.launch

/**
 * 事件规则编辑器（P5，规格 §5.3）。
 *
 * 易用性约定（用户要求）：三步向导全程大白话——「什么时候提醒 →
 * 满足什么条件 → 怎么提醒」；关键字输入带具体示例占位符；CSS 选择器
 * 不露术语（描述为「页面出现特定元素时」+示例）；间隔 Slider 配用途说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleEditorScreen(
    webappId: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val rules by EventRuleStore.rules.collectAsStateWithLifecycle()
    val muted by EventRuleStore.mutedSites.collectAsStateWithLifecycle()
    val siteRules = rules.filter { it.webappId == webappId }
    val isMuted = webappId in muted
    val site = remember(webappId) { DataManager.getInstance().getWebApp(webappId) }
    val siteName = site?.title ?: ""

    val permGranted = remember { mutableStateOf(WebeventNotifier.isPermissionGranted(context)) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permGranted.value = it }

    var editing by remember { mutableStateOf<EventRule?>(null) }
    var wizardOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<EventRule?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val snackbarScope = rememberCoroutineScope()
    val snackbarHostState = androidx.compose.material3.SnackbarHostState()
    val savedMessage = stringResource(R.string.webevent_rule_saved)

    val hasNotifyRule = siteRules.any {
        it.enabled && it.action == EventRule.ACTION_NOTIFY
    }
    val showPermBar = hasNotifyRule && !permGranted.value

    fun openWizard() {
        wizardOpen = true
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.webevent_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    // 站点级静音（P5-4）：与逐规则开关两层粒度
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.webevent_more),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.webevent_mute_all),
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = isMuted,
                                onCheckedChange = {
                                    EventRuleStore.setSiteMuted(webappId, it)
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; openWizard() }) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.webevent_add_rule)
                )
            }
        }
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 站点名上下文条（编辑哪个站的规则一目了然）
            if (siteName.isNotBlank()) {
                Text(
                    text = siteName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            // 静音提示条
            if (isMuted) {
                HintBar(
                    text = stringResource(R.string.webevent_muted_hint),
                    container = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
            // 权限引导条（notify 规则存在且未授权；规格 errorContainer）
            if (showPermBar) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.webevent_perm_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            if (!permGranted.value) {
                                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }) {
                            Text(stringResource(R.string.webevent_perm_action))
                        }
                    }
                }
            }

            if (siteRules.isEmpty()) {
                // 空态：一句话讲清功能（易用性要求，非极客描述）
                EmptyRulesState(onAdd = { editing = null; openWizard() })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(siteRules, key = { it.id }) { rule ->
                        RuleCard(
                            rule = rule,
                            onClick = { editing = rule; openWizard() },
                            onDelete = { deleteTarget = rule },
                            onToggle = { enabled ->
                                EventRuleStore.setRuleEnabled(rule.id, enabled)
                            }
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.webevent_delete_title)) },
            text = { Text(stringResource(R.string.webevent_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    EventRuleStore.deleteRule(target.id)
                    EventRuleEngine.forgetRule(target.id)
                    deleteTarget = null
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    if (wizardOpen) {
        RuleWizardSheet(
            webappId = webappId,
            initial = editing,
            onDismiss = { wizardOpen = false },
            onSaved = { saved ->
                wizardOpen = false
                if (EventRuleStore.saveRule(saved)) {
                    snackbarScope.launch {
                        snackbarHostState.showSnackbar(savedMessage)
                    }
                    // 首条 notify 规则保存时场景化请求权限（规格：禁启动时弹）
                    if (saved.action == EventRule.ACTION_NOTIFY &&
                        !WebeventNotifier.isPermissionGranted(context)
                    ) {
                        permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        )
    }
}

/** 通用提示条 */
@Composable
private fun HintBar(text: String, container: androidx.compose.ui.graphics.Color) {
    Surface(
        color = container,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/** 规则卡：触发器图标 + 摘要 + 副行 + 开关；点击编辑、长按删除 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RuleCard(
    rule: EventRule,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    val triggerDesc = stringResource(triggerLabelRes(rule.trigger))
                    Icon(
                        imageVector = triggerIcon(rule.trigger),
                        contentDescription = triggerDesc,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ruleSummary(rule),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = ruleSubline(rule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.webevent_rule_delete)) },
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

/** 规则摘要：条件原文（数据展示，不加装饰符号） */
private fun ruleSummary(rule: EventRule): String =
    rule.condition.ifBlank { "*" }

private fun triggerLabelRes(trigger: String): Int = when (trigger) {
    EventRule.TRIGGER_NOTIFICATION -> R.string.webevent_trigger_notification
    EventRule.TRIGGER_TITLE -> R.string.webevent_trigger_title
    else -> R.string.webevent_trigger_selector
}

private fun triggerIcon(trigger: String) = when (trigger) {
    EventRule.TRIGGER_NOTIFICATION -> Icons.Default.Notifications
    EventRule.TRIGGER_TITLE -> Icons.Default.Title
    else -> Icons.Default.Visibility
}

/** 副行：动作 · 间隔 */
@Composable
private fun ruleSubline(rule: EventRule): String {
    val action = if (rule.action == EventRule.ACTION_NOTIFY) {
        stringResource(R.string.webevent_action_notify)
    } else {
        stringResource(R.string.webevent_action_toast)
    }
    val seconds = rule.cooldownMs / 1000
    return stringResource(R.string.webevent_rule_subline, action, seconds)
}

/** 空态：一句话讲清功能（易用性：用户不知道「规则」是什么也能上手） */
@Composable
private fun EmptyRulesState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.Bolt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.webevent_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.webevent_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = onAdd) {
            Text(stringResource(R.string.webevent_add_rule))
        }
    }
}
