package com.cylonid.nativealpha

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.PageErrorEntry
import com.cylonid.nativealpha.model.PageErrorRepository
import com.cylonid.nativealpha.model.WebApp
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
    // 当前 WebApp（响应式：清缓存/清空统计后刷新重组）
    private var webappState by mutableStateOf<WebApp?>(null)
    // 导入的错误记录（只读展示层合并，不落盘；mutableStateOf 触发 Compose 重组）
    private var importedErrors by androidx.compose.runtime.mutableStateOf<List<PageErrorEntry>>(emptyList())

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
        // 初始加载（后续清缓存/清空统计后更新触发重组）
        webappState = DataManager.getInstance().getWebApp(webappID)

        setContent {
            AppMaterialTheme {
                val webapp = webappState
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
                    onClearCache = { clearCache() },
                    onClearStats = { clearStats() },
                    onClearImported = { importedErrors = emptyList() },
                    importedErrors = importedErrors,
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }

    /**
     * 清缓存：清空 WebStorage（localStorage 等）+ WebView cacheDir，重置缓存统计字段。
     * 异步执行（IO），完成主线程提示。
     */
    private fun clearCache() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                android.webkit.WebStorage.getInstance().deleteAllData()
                // 清空 cacheDir（WebView HTTP 缓存）
                val cacheDir = cacheDir
                cacheDir.listFiles()?.forEach { file ->
                    file.deleteRecursively()
                }
                // 重置缓存统计字段（原对象）
                val original = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true)
                if (original != null) {
                    original.statCacheHttpBytes = 0L
                    original.statCacheStoreBytes = 0L
                    DataManager.getInstance().replaceWebApp(original)
                    // 刷新统计页数据（重组重读最新值）
                    webappState = DataManager.getInstance().getWebApp(webappID)
                }
                snackbarHostState.showSnackbar(getString(R.string.cache_cleared))
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(getString(R.string.cache_clear_failed))
            }
        }
    }

    /**
     * 清空统计：重置打开次数/加载耗时/错误计数 + 清空该站页面错误日志。
     * 异步执行（IO），完成主线程提示。
     */
    private fun clearStats() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val original = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true)
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
                    // 刷新统计页数据（重组重读最新值）
                    webappState = DataManager.getInstance().getWebApp(webappID)
                }
                // 清空该站页面错误日志（DataStore）
                PageErrorRepository.clearForSite(applicationContext, webappID)
                snackbarHostState.showSnackbar(getString(R.string.stats_cleared))
            } catch (e: Exception) {
                snackbarHostState.showSnackbar(getString(R.string.stats_clear_failed))
            }
        }
    }

    /** 导出该站页面错误（DataStore 过滤） */
    private fun exportPageErrorsToUri(uri: android.net.Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            val entries = PageErrorRepository.getForSite(applicationContext, webappID)
            if (entries.isEmpty()) {
                snackbarHostState.showSnackbar(getString(R.string.stats_no_errors_site))
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
                snackbarHostState.showSnackbar(getString(R.string.export_failed_generic))
            }
        }
    }

    /** 导入页面错误文件（只读展示层合并，不落盘；应用错误文件仅导出不导入，识别提示） */
    private fun importPageErrorsFromUri(uri: android.net.Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            // 应用错误文件识别：文件名含 app_errors → 提示不导入（只导出）
            val displayName = try {
                contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (e: Exception) { null }
            if (displayName?.contains("app_errors") == true) {
                snackbarHostState.showSnackbar(getString(R.string.stats_app_errors_export_only))
                return@launch
            }
            val json = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) { null }
            val entries = json?.let { PageErrorEntry.fromJson(it) } ?: emptyList()
            if (entries.isEmpty()) {
                snackbarHostState.showSnackbar(getString(R.string.stats_import_invalid))
            } else {
                // 展示层合并：更新状态触发 Compose 重组（仅展示不落盘，退出页面即失效）
                importedErrors = entries.sortedByDescending { it.time }
                snackbarHostState.showSnackbar(getString(R.string.imported_done, entries.size))
            }
        }
    }
}
