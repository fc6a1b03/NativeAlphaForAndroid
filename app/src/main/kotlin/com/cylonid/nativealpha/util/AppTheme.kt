package com.cylonid.nativealpha.util

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.cylonid.nativealpha.model.DataManager

/**
 * Compose 主题：按全局 UI 模式（themeId）提供 ColorScheme。
 *
 * 关键：Compose 的 MaterialTheme 不读 XML 主题，必须显式传 colorScheme。
 * 深/浅两套靛蓝色板与 res/values(-night)/colors.xml 同源。
 */

private val LightIndigo = lightColorScheme(
    primary = Color(0xFF4A47D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0DFFF),
    onPrimaryContainer = Color(0xFF000066),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF77536D),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD7F2),
    onTertiaryContainer = Color(0xFF2D1228),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC7C5D0),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF2EFF7),
    inversePrimary = Color(0xFFC1BFFF),
    surfaceDim = Color(0xFFDCD9E2),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F2FB),
    surfaceContainer = Color(0xFFEFEDF6),
    surfaceContainerHigh = Color(0xFFE9E7F0),
    surfaceContainerHighest = Color(0xFFE4E1EA)
)

private val DarkIndigo = darkColorScheme(
    primary = Color(0xFFC1BFFF),
    onPrimary = Color(0xFF1A1B80),
    primaryContainer = Color(0xFF3232AD),
    onPrimaryContainer = Color(0xFFE0DFFF),
    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFE9B9DC),
    onTertiary = Color(0xFF462840),
    tertiaryContainer = Color(0xFF5D3C55),
    onTertiaryContainer = Color(0xFFFFD7F2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1EA),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1EA),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF91909A),
    outlineVariant = Color(0xFF46464F),
    inverseSurface = Color(0xFFE4E1EA),
    inverseOnSurface = Color(0xFF303036),
    inversePrimary = Color(0xFF4A47D6),
    surfaceDim = Color(0xFF131318),
    surfaceBright = Color(0xFF393940),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF292930),
    surfaceContainerHighest = Color(0xFF34343B)
)

/** 当前 UI 模式对应的 Compose ColorScheme */
@Composable
fun appColorScheme(): ColorScheme {
    DataManager.getInstance().loadAppData()
    return when (DataManager.getInstance().settings.themeId) {
        2 -> DarkIndigo
        1 -> LightIndigo
        else -> {
            // 跟随系统：读当前系统夜间模式
            val isDark = isSystemInDarkTheme()
            if (isDark) DarkIndigo else LightIndigo
        }
    }
}

/** 包一层 MaterialTheme，统一应用 UI 模式 */
@Composable
fun AppMaterialTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = appColorScheme(), content = content)
}
