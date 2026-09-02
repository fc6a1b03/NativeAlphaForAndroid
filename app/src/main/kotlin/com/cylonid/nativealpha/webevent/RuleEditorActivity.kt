package com.cylonid.nativealpha.webevent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.cylonid.nativealpha.util.SystemBars
import com.cylonid.nativealpha.util.AppMaterialTheme
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.ThemeUtils

/**
 * 事件规则编辑器宿主（P5）：按站上下文（INTENT_WEBAPPID），主题前置同
 * 宿主范式；UI 全部在 [RuleEditorScreen]。
 */
internal class RuleEditorActivity : ComponentActivity(), SystemBars.SelfManagedInsets {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)
        ThemeUtils.applySystemBarColors(this)
        val webappId = intent.getIntExtra(Const.INTENT_WEBAPPID, -1)

        setContent {
            AppMaterialTheme {
                RuleEditorScreen(
                    webappId = webappId,
                    onBack = { finish() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
