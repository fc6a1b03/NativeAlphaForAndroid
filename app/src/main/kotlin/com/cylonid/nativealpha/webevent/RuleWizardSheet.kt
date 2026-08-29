package com.cylonid.nativealpha.webevent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import java.util.UUID

/**
 * 规则三步向导（P5，规格 §5.3；易用性约定：步名/提示全大白话，
 * 示例占位符引导，CSS 选择器不露术语表述）。
 *
 * 步骤：什么时候提醒（触发器）→ 满足什么条件（条件）→ 怎么提醒（动作）。
 * 未选/校验不过禁下一步；编辑复用预填；保存交 onSaved（权限请求在宿主层）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RuleWizardSheet(
    webappId: Int,
    initial: EventRule?,
    onDismiss: () -> Unit,
    onSaved: (EventRule) -> Unit
) {
    var step by remember { mutableStateOf(0) }
    var trigger by remember { mutableStateOf(initial?.trigger ?: "") }
    var condition by remember { mutableStateOf(initial?.condition ?: "") }
    var action by remember { mutableStateOf(initial?.action ?: EventRule.ACTION_NOTIFY) }
    var cooldownSec by remember {
        mutableFloatStateOf(((initial?.cooldownMs ?: EventRule.DEFAULT_COOLDOWN_MS) / 1000).toFloat())
    }

    // 步骤 2 条件校验：title/selector 必填，selector 轻量语法检查
    val conditionError = when {
        trigger == EventRule.TRIGGER_TITLE && condition.isBlank() ->
            stringResource(R.string.webevent_cond_required)
        trigger == EventRule.TRIGGER_SELECTOR && !isValidSelector(condition) ->
            stringResource(R.string.webevent_cond_selector_error)
        else -> null
    }
    val canNext = when (step) {
        0 -> trigger.isNotEmpty()
        1 -> conditionError == null
        else -> true
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            // 进度条 + 大白话步名
            LinearProgressIndicator(
                progress = { (step + 1) / 3f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = when (step) {
                    0 -> stringResource(R.string.webevent_step_trigger)
                    1 -> stringResource(R.string.webevent_step_condition)
                    else -> stringResource(R.string.webevent_step_action)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            when (step) {
                0 -> TriggerStep(trigger = trigger, onPick = { trigger = it })
                1 -> ConditionStep(
                    trigger = trigger,
                    condition = condition,
                    error = conditionError,
                    onChange = { condition = it }
                )
                else -> ActionStep(
                    action = action,
                    cooldownSec = cooldownSec,
                    onPick = { action = it },
                    onCooldown = { cooldownSec = it }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                if (step > 0) {
                    TextButton(onClick = { step -= 1 }) {
                        Text(stringResource(R.string.webevent_step_back))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                TextButton(
                    enabled = canNext,
                    onClick = {
                        if (step < 2) {
                            step += 1
                        } else {
                            onSaved(
                                EventRule(
                                    id = initial?.id ?: UUID.randomUUID().toString(),
                                    webappId = webappId,
                                    enabled = initial?.enabled ?: true,
                                    trigger = trigger,
                                    condition = condition.trim(),
                                    action = action,
                                    cooldownMs = (cooldownSec.toLong() * 1000),
                                    createdAt = initial?.createdAt ?: System.currentTimeMillis()
                                )
                            )
                        }
                    }
                ) {
                    Text(
                        text = if (step < 2) {
                            stringResource(R.string.webevent_step_next)
                        } else {
                            stringResource(R.string.webevent_step_save)
                        }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 步骤 1：什么时候提醒——三张场景卡，选中高亮 */
@Composable
private fun TriggerStep(trigger: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TriggerCard(
            selected = trigger == EventRule.TRIGGER_NOTIFICATION,
            icon = Icons.Default.Notifications,
            title = stringResource(R.string.webevent_trigger_notification),
            desc = stringResource(R.string.webevent_trigger_notification_desc),
            onClick = { onPick(EventRule.TRIGGER_NOTIFICATION) }
        )
        TriggerCard(
            selected = trigger == EventRule.TRIGGER_TITLE,
            icon = Icons.Default.Title,
            title = stringResource(R.string.webevent_trigger_title),
            desc = stringResource(R.string.webevent_trigger_title_desc),
            onClick = { onPick(EventRule.TRIGGER_TITLE) }
        )
        TriggerCard(
            selected = trigger == EventRule.TRIGGER_SELECTOR,
            icon = Icons.Default.Visibility,
            title = stringResource(R.string.webevent_trigger_selector),
            desc = stringResource(R.string.webevent_trigger_selector_desc),
            onClick = { onPick(EventRule.TRIGGER_SELECTOR) }
        )
    }
}

@Composable
private fun TriggerCard(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/** 步骤 2：满足什么条件——按触发器动态表单 + 示例占位符 */
@Composable
private fun ConditionStep(
    trigger: String,
    condition: String,
    error: String?,
    onChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (trigger) {
            EventRule.TRIGGER_NOTIFICATION -> {
                Text(
                    text = stringResource(R.string.webevent_cond_notification_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = condition,
                    onValueChange = onChange,
                    placeholder = { Text(stringResource(R.string.webevent_cond_keyword_hint)) },
                    supportingText = {
                        Text(stringResource(R.string.webevent_cond_keyword_hint))
                    },
                    isError = error != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            EventRule.TRIGGER_TITLE -> {
                Text(
                    text = stringResource(R.string.webevent_cond_title_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = condition,
                    onValueChange = onChange,
                    placeholder = { Text(stringResource(R.string.webevent_cond_title_example)) },
                    isError = error != null,
                    supportingText = {
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            else -> {
                Text(
                    text = stringResource(R.string.webevent_cond_selector_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = condition,
                    onValueChange = onChange,
                    placeholder = { Text(stringResource(R.string.webevent_cond_selector_example)) },
                    isError = error != null,
                    supportingText = {
                        if (error != null) {
                            Text(error, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(stringResource(R.string.webevent_cond_selector_hint))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 步骤 3：怎么提醒——动作二卡 + 大白话间隔说明 */
@Composable
private fun ActionStep(
    action: String,
    cooldownSec: Float,
    onPick: (String) -> Unit,
    onCooldown: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TriggerCard(
            selected = action == EventRule.ACTION_NOTIFY,
            icon = Icons.Default.Notifications,
            title = stringResource(R.string.webevent_action_notify),
            desc = stringResource(R.string.webevent_action_notify_desc),
            onClick = { onPick(EventRule.ACTION_NOTIFY) }
        )
        TriggerCard(
            selected = action == EventRule.ACTION_TOAST,
            icon = Icons.Default.CheckCircle,
            title = stringResource(R.string.webevent_action_toast),
            desc = stringResource(R.string.webevent_action_toast_desc),
            onClick = { onPick(EventRule.ACTION_TOAST) }
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.webevent_cond_cooldown_label),
            style = MaterialTheme.typography.bodyLarge
        )
        Slider(
            value = cooldownSec,
            onValueChange = onCooldown,
            valueRange = 0f..60f,
            steps = 11
        )
        Text(
            text = stringResource(
                R.string.webevent_cooldown_value, cooldownSec.toInt()
            ) + "\n" + stringResource(R.string.webevent_cond_cooldown_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 轻量选择器语法检查（v1：拦常见笔误，不做完整 CSS 解析）。
 * 注意：> + ~ 是合法 CSS 组合子，不得拦——只拦真非法字符。 */
internal fun isValidSelector(selector: String): Boolean {
    val s = selector.trim()
    if (s.isEmpty() || s.length > 200) return false
    // 拦真非法字符；'>' 是合法组合子不能进本集（'<' 由段首检查自然拦截）
    if (s.contains(Regex("[(\\){}\\[\\]=]"))) return false
    // 组合选择器按空白/逗号/组合子拆段，每段首个字符须为合法起始
    return s.split(Regex("[\\s,>+~]+")).all { seg ->
        seg.isEmpty() || seg.first().isLetterOrDigit() ||
            seg.first() in listOf('.', '#', '*', ':', '[', '"', '\'')
    }
}
