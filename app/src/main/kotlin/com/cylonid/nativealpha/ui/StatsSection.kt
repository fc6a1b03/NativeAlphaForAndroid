package com.cylonid.nativealpha.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R

/**
 * 统计区块统一抽象（A1 组合+模板）：圆角/边距/折叠头/零值灰化的唯一实现。
 * 各卡片只声明内容与数据是否为空，不重复排版样板——「样式统一」的机制保障。
 *
 * 视觉语言常量（内容卡 20dp 圆角）仅此一处；英雄卡 28dp 在 StatsHero 自持
 * （全页唯二圆角档位，禁止第三种）。
 */

/** 内容卡圆角（视觉语言两档之一的唯一出处） */
internal val StatsCardShape = RoundedCornerShape(20.dp)

/** 零值态灰化不透明度（0 值卡「不是坏了，是还没有」的可视区分） */
private const val EMPTY_DIM_ALPHA = 0.45f

/**
 * 统计区块容器。
 *
 * @param title 区块标题（折叠态仍可见）
 * @param isEmpty 数据是否为空：true 时整卡灰化（统一零值视觉语言）
 * @param collapsible 是否允许折叠（默认 false；收纳章节传 true）
 * @param initiallyCollapsed 首屏折叠态（仅收纳章节用）
 * @param content 区块内容
 */
@Composable
internal fun StatsSection(
    title: String,
    modifier: Modifier = Modifier,
    isEmpty: Boolean = false,
    collapsible: Boolean = false,
    initiallyCollapsed: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var collapsed by remember { mutableStateOf(initiallyCollapsed) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (isEmpty) EMPTY_DIM_ALPHA else 1f },
        shape = StatsCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (collapsible) Modifier.clickable { collapsed = !collapsed }
                        else Modifier
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (collapsible) {
                    Icon(
                        if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            AnimatedVisibility(visible = !collapsed) {
                Column(content = content)
            }
        }
    }
}
