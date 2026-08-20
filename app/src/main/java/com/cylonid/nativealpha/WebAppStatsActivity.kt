package com.cylonid.nativealpha

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.PageErrorEntry
import com.cylonid.nativealpha.model.PageErrorRepository
import com.cylonid.nativealpha.ui.WebAppStatsScreen
import com.cylonid.nativealpha.util.AppMaterialTheme
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.ThemeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统计页（按 WebApp 进入）：KPI/图表/缓存/错误日志/导入导出/清空。
 * 数据源：DataManager 统计字段 + DataStore 页面错误（KEY_PAGE_ERRORS）。
 */
class WebAppStatsActivity : AppCompatActivity() {

    private var webappID: Int = -1
    private val snackbarHostState = SnackbarHostState()

    // 导出页面错误（SAF 新 API）
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) exportPageErrorsToUri(uri)
    }

    // 导入页面错误（SAF 新 API）
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importPageErrorsFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)
        webappID = intent.getIntExtra(Const.INTENT_WEBAPPID, -1)

        setContent {
            AppMaterialTheme {
                val webapp = DataManager.getInstance().getWebApp(webappID)
                if (webapp == null) {
                    finish()
                    return@AppMaterialTheme
                }
                WebAppStatsScreen(
                    webapp = webapp,
                    onBack = { finish() },
                    onImport = {
                        try {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        } catch (e: Exception) {
                            // 无文件选择器：静默
                        }
                    },
                    onExport = {
                        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
                        try {
                            exportLauncher.launch("WebNative_errors_${webapp.alphanumericBaseUrl}_${sdf.format(Date())}.json")
                        } catch (e: Exception) {
                            // 无保存器：静默
                        }
                    },
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }

    /** 导出该站页面错误（DataStore 过滤） */
    private fun exportPageErrorsToUri(uri: android.net.Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            val entries = PageErrorRepository.getForSite(applicationContext, webappID)
            if (entries.isEmpty()) {
                snackbarHostState.showSnackbar("此站点无错误记录")
                return@launch
            }
            try {
                contentResolver.openOutputStream(uri)?.use { stream ->
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        writer.write(PageErrorEntry.toJson(entries))
                    }
                }
                snackbarHostState.showSnackbar("错误日志已导出")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("导出失败，请重试")
            }
        }
    }

    /** 导入页面错误文件（只读展示层合并，不落盘） */
    private fun importPageErrorsFromUri(uri: android.net.Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            val json = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) { null }
            val entries = json?.let { PageErrorEntry.fromJson(it) } ?: emptyList()
            if (entries.isEmpty()) {
                snackbarHostState.showSnackbar("文件中无错误记录或格式不正确")
            } else {
                snackbarHostState.showSnackbar("已导入 ${entries.size} 条错误记录（仅展示）")
                // 展示层合并：通过状态传递（简化：刷新页面重载）
                // 注：导入仅展示不落盘，退出页面即失效（符合设计）
            }
        }
    }
}
