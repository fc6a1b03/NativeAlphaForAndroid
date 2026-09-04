package com.cylonid.nativealpha

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.ui.ReviewData
import com.cylonid.nativealpha.ui.StatsReviewScreen
import com.cylonid.nativealpha.util.AppMaterialTheme
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.FeatureMetrics
import com.cylonid.nativealpha.util.StatsDailyStore
import com.cylonid.nativealpha.util.SystemBars
import com.cylonid.nativealpha.util.ThemeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 月度回顾页（Phase 4）：按月聚合的相处之最（Wrapped 式单页叙事）。
 * 数据门槛在 [StatsReviewData.build]（活跃 <7 天返回 null → 直接 finish，
 * 入口在统计页同样按门槛隐藏——双侧一致防「能进但空白」）。
 */
class StatsReviewActivity : AppCompatActivity(), SystemBars.SelfManagedInsets {

    private var webappID: Int = -1
    private val snackbarHostState = SnackbarHostState()
    private var webappState by mutableStateOf<WebApp?>(null)
    private var reviewState by mutableStateOf<ReviewData?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)
        ThemeUtils.applySystemBarColors(this)
        webappID = intent.getIntExtra(Const.INTENT_WEBAPPID, -1)
        webappState = DataManager.getInstance().getWebApp(webappID)

        lifecycleScope.launch(Dispatchers.IO) {
            val context = applicationContext
            val daily = StatsDailyStore.snapshot(context)
            val notificationShown = FeatureMetrics.moduleSnapshot("webevent")["notification_shown"] ?: 0L
            val webapp = DataManager.getInstance().getWebApp(webappID)
            reviewState = webapp?.let { ReviewData.build(it, daily, notificationShown) }
        }

        setContent {
            AppMaterialTheme {
                val webapp = webappState
                val review = reviewState
                if (webapp == null || review == null) {
                    // 门槛不足（活跃 <7 天）或站点缺失：无内容可回顾，静默返回
                    finish()
                    return@AppMaterialTheme
                }
                StatsReviewScreen(
                    webapp = webapp,
                    review = review,
                    onBack = { finish() },
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }
}
