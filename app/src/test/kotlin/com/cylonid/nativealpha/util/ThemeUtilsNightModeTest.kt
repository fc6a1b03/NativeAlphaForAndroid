package com.cylonid.nativealpha.util

import androidx.appcompat.app.AppCompatDelegate
import com.cylonid.nativealpha.model.DataManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 应用内主题 → AppCompat 夜间模式映射契约（全应用唯一口径）。
 * 锁死三档映射：任何页面运行期设置 localNightMode 都必须走此函数，
 * 不得自行写死 FOLLOW_SYSTEM（会覆盖用户的强制浅/深选择）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeUtilsNightModeTest {

    private val originalThemeId: Int = DataManager.getInstance().settings.themeId

    @After
    fun restoreTheme() {
        DataManager.getInstance().settings.themeId = originalThemeId
    }

    @Test
    fun lightThemeIdMapsToNightNo() {
        DataManager.getInstance().settings.themeId = 1
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeUtils.appNightMode())
    }

    @Test
    fun darkThemeIdMapsToNightYes() {
        DataManager.getInstance().settings.themeId = 2
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeUtils.appNightMode())
    }

    @Test
    fun followSystemIdMapsToFollowSystem() {
        DataManager.getInstance().settings.themeId = 0
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeUtils.appNightMode())
    }
}
