package com.cylonid.nativealpha.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.StatAccent
import com.cylonid.nativealpha.util.WebAppIconManager

/** 英雄卡圆角 28dp（视觉语言两档之二，全页唯一大圆角色块） */
private val HeroCardShape = RoundedCornerShape(28.dp)

/** favicon 主色在渐变底中的强度（低饱和，文字对比度不受侵扰） */
private const val HERO_TINT_ALPHA = 0.22f

/**
 * §0 英雄卡：站点身份 + 相伴天数 + 打开次数（CountUp）。
 * 页面唯一大色块——favicon 主色低饱和渐变底（主观个性；色彩三源之一）。
 */
@Composable
internal fun StatsHero(webapp: WebApp, daysTogether: Int, streakWeeks: Int = 0, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val accent = StatAccent.accent(context, webapp)
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    // 图标组合期缓存（R11：resolveIconCached 有磁盘缓存，remember 避免重组重复解码）
    val icon = remember(webapp.ID) { WebAppIconManager.resolveIconCached(context, webapp) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(accent.copy(alpha = HERO_TINT_ALPHA), surface)),
                HeroCardShape
            )
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            bitmap = icon.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                webapp.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            if (daysTogether > 0) {
                Spacer(modifier = Modifier.height(2.dp))
                val daysText = pluralStringResource(R.plurals.stats_hero_days, daysTogether, daysTogether)
                // streak≥2 周才叠加展示（第 1 周人人都有，不构成信号）
                val text = if (streakWeeks >= 2) {
                    stringResource(R.string.stats_hero_streak, daysText, streakWeeks)
                } else daysText
                Text(
                    // 0 天（未使用过）不占位：空态语义交给各数据卡
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                rememberCountUp(webapp.statLaunches).toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.stat_launches),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
