package com.cylonid.nativealpha.webevent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp

/**
 * 事件规则入口行（P5，规格 §5.3「宿主唯一改动」：快捷键组后）。
 *
 * bolt 图标 40dp primaryContainer 容器 + 标题 + chevron + 规则数 badge；
 * 副文案告知后台活动语义（P5-1 电池代价透明化，易用性要求）。
 * 跳转内聚在本组件（宿主零 lambda 接线）。
 */
@Composable
internal fun EventsEntrySection(webApp: WebApp) {
    val rules by EventRuleStore.rules.collectAsStateWithLifecycle()
    val count = rules.count { it.webappId == webApp.ID }
    val context = androidx.compose.ui.platform.LocalContext.current
    val entrySubtitle = stringResource(R.string.webevent_entry_subtitle)
    // hook 失效显式提示：本会话探针回传 false（站点改版致 hook 未挂载）
    // 时给出警示——失效可见，而非静默无效
    val hookStale = WebeventRuntime.hookLiveness(webApp.ID) == false

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(
                    android.content.Intent(context, RuleEditorActivity::class.java)
                        .putExtra(com.cylonid.nativealpha.util.Const.INTENT_WEBAPPID, webApp.ID)
                )
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Bolt,
                contentDescription = stringResource(R.string.webevent_rules_entry),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.webevent_rules_entry),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = entrySubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (count > 0 && hookStale) {
                Text(
                    text = stringResource(R.string.webevent_hook_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (count > 0) {
            Badge { Text(count.toString()) }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
