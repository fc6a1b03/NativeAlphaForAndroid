package com.cylonid.nativealpha.util

import android.content.Context
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.PageErrorRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统计清空统一编排（A8 命令聚合）：「清空统计」涉及的所有存储面
 * （WebApp 统计字段 / 页面错误日志 / 后续新增的按日快照与 Web Vitals）
 * 只在此一处声明清空范围——防范围扩散后某处漏清（「导出即清空名存实亡」
 * 教训：提示语声称的行为必须与实际清空面一致）。
 */
internal object StatsClearer {

    /**
     * 清空指定站点的全部统计数据（挂起，IO 语境调用）。
     * Phase 2/3 新增存储面（daily_activity / web_vitals）在此追加，
     * 调用方零改动。
     */
    suspend fun clearAll(context: Context, webappId: Int) = withContext(Dispatchers.IO) {
        val original = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappId, true)
        if (original != null) {
            original.statLaunches = 0
            original.statLoadTimeSum = 0L
            original.statLoadTimeCount = 0
            original.statMaxLoadTime = 0L
            original.statErrors = 0
            original.statLastError = null
            original.statLoadTimes = mutableListOf()
            original.statFirstLoadedAt = 0L
            original.statLastUsedAt = 0L
            DataManager.getInstance().replaceWebApp(original)
        }
        // 页面错误明细一并清空（现状语义：清空统计=统计字段+错误日志）
        PageErrorRepository.clearForSite(context, webappId)
    }
}
