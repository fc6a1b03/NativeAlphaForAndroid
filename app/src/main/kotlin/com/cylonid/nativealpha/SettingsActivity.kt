package com.cylonid.nativealpha

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.LayoutInflater
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.cylonid.nativealpha.model.AppErrorEntry
import com.cylonid.nativealpha.model.AppErrorLogRepository
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.ui.GlobalSettingsScreen
import com.cylonid.nativealpha.util.AppMaterialTheme
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.DateUtils
import com.cylonid.nativealpha.util.MdRenderer
import com.cylonid.nativealpha.util.NotificationUtils
import com.cylonid.nativealpha.util.ThemeUtils
import com.cylonid.nativealpha.util.UpdateChecker
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter

/**
 * 全局设置页：Compose 实现（分区块卡片：通用 / 备份）。
 */
class SettingsActivity : AppCompatActivity() {

    // 导出错误日志（SAF 新 API，替代 startActivityForResult）
    private val exportAppErrorsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            exportAppErrorsToUri(uri)
        }
    }

    // 备份导出（SAF 新 API）
    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            DataManager.getInstance().saveGlobalSettings()
            if (DataManager.getInstance().saveSharedPreferencesToFile(uri)) {
                NotificationUtils.showInfoSnackbar(
                    this,
                    getString(R.string.export_success),
                    Snackbar.LENGTH_SHORT
                )
            } else {
                NotificationUtils.showInfoSnackbar(
                    this,
                    getString(R.string.export_failed),
                    Snackbar.LENGTH_LONG
                )
            }
        }
    }

    // 备份导入（SAF 新 API）
    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && DataManager.getInstance().loadSharedPreferencesFromFile(uri)) {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            DataManager.getInstance().loadAppData()
            // 成功提示用 Toast：页面即将 finish，Snackbar 无法显示
            Toast.makeText(
                this,
                resources.getQuantityString(
                    R.plurals.import_success,
                    DataManager.getInstance().activeWebsitesCount,
                    DataManager.getInstance().activeWebsitesCount
                ),
                Toast.LENGTH_LONG
            ).show()
            val i = Intent(this, MainActivity::class.java)
            i.putExtra(Const.INTENT_BACKUP_RESTORED, true)
            finish()
            startActivity(i)
        } else {
            NotificationUtils.showInfoSnackbar(
                this,
                getString(R.string.import_failed),
                Snackbar.LENGTH_LONG
            )
        }
    }

    // Android 8+ 安装未知应用权限申请
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 用户从系统设置页返回时 Activity 可能已销毁
        if (isFinishing || isDestroyed) {
            pendingDownload = null
            return@registerForActivityResult
        }
        // minSdk=31，canRequestPackageInstalls() 永远可用
        if (packageManager.canRequestPackageInstalls()) {
            pendingDownload?.let { (url, tag) ->
                pendingDownload = null
                doDownload(url, tag)
            }
        } else {
            NotificationUtils.showInfoSnackbar(
                this,
                getString(R.string.update_install_permission_denied),
                Snackbar.LENGTH_LONG
            )
        }
    }

    /** 申请安装权限时暂存的下载参数（URL to tag） */
    private var pendingDownload: Pair<String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)
        // 状态栏/虚拟键跟随主题（切换主题后刷新颜色）
        ThemeUtils.applySystemBarColors(this)
        // 进设置页主动检查更新（一天一次）
        autoCheckUpdateOnceADay()
        setContent {
            AppMaterialTheme {
                GlobalSettingsScreen(
                    onBack = { finish() },
                    onSave = { modified ->
                        // 落库 + 立即应用 UI 模式
                        DataManager.getInstance().settings = modified
                        DataManager.getInstance().saveGlobalSettings()
                        ThemeUtils.applyUiMode()
                        finish()
                    },
                    onExport = { export() },
                    onImport = { importBackup() },
                    onExportAppErrors = {
                        exportAppErrorsGuarded()
                    },
                    onCheckUpdate = { onDone -> checkUpdate(onDone) },
                    onGlobalWebApp = {
                        val intent = Intent(this, WebAppSettingsActivity::class.java)
                        intent.putExtra(
                            Const.INTENT_WEBAPPID,
                            DataManager.getInstance().settings.globalWebApp.ID
                        )
                        intent.setAction(Intent.ACTION_VIEW)
                        startActivity(intent)
                    }
                )
            }
        }
    }

    /**
     * 检查版本更新：GitHub API 查最新 Release → 有更新则提示下载（异步后台），
     * 下载完成后自动触发安装。onDone：检查结束回调（Compose 侧复位 loading 状态）。
     */
    private fun checkUpdate(onDone: () -> Unit = {}) {
        UpdateChecker.check(this) { result ->
            onDone()
            // 异步回调回来时 Activity 可能已销毁，避免操作已销毁窗口崩溃
            if (isFinishing || isDestroyed) return@check
            when (result) {
                is UpdateChecker.Result.HasUpdate -> showUpdateDialog(result)
                is UpdateChecker.Result.NoUpdate -> {
                    NotificationUtils.showInfoSnackbar(
                        this,
                        getString(R.string.update_latest_with_version, result.currentVersion),
                        Snackbar.LENGTH_LONG
                    )
                }
                is UpdateChecker.Result.Error -> {
                    // 网络不可达（大陆直连 GitHub 常态）不写错误日志——环境性
                    // 高频事件，ERROR+堆栈只会污染日志；仅应用侧真实异常留档
                    if (result.kind != UpdateChecker.KIND_NETWORK) {
                        logUpdateCheckError(result)
                    }
                    NotificationUtils.showInfoSnackbar(
                        this,
                        result.displayMessage,
                        Snackbar.LENGTH_LONG
                    )
                }
            }
        }
    }

    /** 显示更新弹窗：md 渲染 release notes + 下载/取消 */
    private fun showUpdateDialog(result: UpdateChecker.Result.HasUpdate) {
        if (isFinishing || isDestroyed) return
        val contentView = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(
                (24 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                0
            )
            text = MdRenderer.render(this@SettingsActivity, result.notes)
            movementMethod = ScrollingMovementMethod()
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_msg, result.tag))
            .setView(contentView)
            .setPositiveButton(getString(R.string.update_download)) { _, _ ->
                startDownload(result.downloadUrl, result.tag)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 开始下载：先检查安装未知应用权限（minSdk=31，无需版本判断） */
    private fun startDownload(downloadUrl: String, tag: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingDownload = downloadUrl to tag
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                ("package:$packageName").toUri()
            )
            try {
                installPermissionLauncher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                pendingDownload = null
                NotificationUtils.showInfoSnackbar(
                    this,
                    getString(R.string.update_install_permission_denied),
                    Snackbar.LENGTH_LONG
                )
            }
            return
        }
        doDownload(downloadUrl, tag)
    }

    /** 真正 enqueue DownloadManager 下载 */
    private fun doDownload(downloadUrl: String, tag: String) {
        val id = UpdateChecker.download(this, downloadUrl, tag)
        if (id != -1L) {
            NotificationUtils.showInfoSnackbar(
                this, getString(R.string.update_downloading), Snackbar.LENGTH_LONG
            )
        } else {
            NotificationUtils.showInfoSnackbar(
                this, getString(R.string.update_download_failed), Snackbar.LENGTH_LONG
            )
        }
    }

    /** 记录更新检查错误到应用错误日志 */
    private fun logUpdateCheckError(result: UpdateChecker.Result.Error) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AppErrorLogRepository.append(
                    applicationContext,
                    AppErrorEntry(
                        time = System.currentTimeMillis(),
                        level = AppErrorEntry.LEVEL_WARNING,
                        tag = "UpdateChecker",
                        message = "Update check failed (${result.kind}): ${result.detail}",
                        stackTrace = ""
                    )
                )
            } catch (_: Exception) {
                // 日志写入失败静默：不影响主功能
            }
        }
    }

    /**
     * 主动检查一次（一天一次）：SettingsActivity 打开时调用；
     * SharedPreferences 记录上次检查日期，同一天不重复。
     */
    private fun autoCheckUpdateOnceADay() {
        try {
            val prefs = getSharedPreferences("update_check", MODE_PRIVATE)
            val today = DateUtils.compactDate()
            val lastCheck = prefs.getString("last_check_date", "")
            if (lastCheck == today) return // 今天已检查
            prefs.edit { putString("last_check_date", today) }
            checkUpdate()
        } catch (e: Exception) {
            // 静默
        }
    }

    /**
     * 导出应用错误日志（近 3 天）：先查后导——无日志直接提示，不弹文件选择器（不执行导出）；
     * 有日志 → SAF 创建文件 → 写 JSON。导出只读不清除（3 天窗口完整保留，每次导出内容一致）。
     * 三态：成功 / 失败 / 近 3 天无日志（不创建空文件）。
     * 查询期间防重入（exitingLogsExporting）：连点只弹一次 SAF 窗口。
     */
    private val exportingLogs = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun exportAppErrorsGuarded() {
        if (!exportingLogs.compareAndSet(false, true)) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val recent = AppErrorLogRepository.getRecent(applicationContext)
                if (recent.isEmpty()) {
                    // 无日志：不执行导出（不弹 SAF 选择器、不创建空文件），仅提示
                    runOnUiThread {
                        NotificationUtils.showInfoSnackbar(
                            this@SettingsActivity,
                            getString(R.string.app_errors_none),
                            Snackbar.LENGTH_LONG
                        )
                    }
                    return@launch
                }
                runOnUiThread {
                    // CreateDocument 注册器已指定 application/json，launch 传文件名即可
                    try {
                        exportAppErrorsLauncher.launch("WebNative_app_errors_" + DateUtils.compactDate() + ".json")
                    } catch (e: Exception) {
                        NotificationUtils.showInfoSnackbar(
                            this@SettingsActivity,
                            getString(R.string.no_filemanager),
                            Snackbar.LENGTH_LONG
                        )
                    }
                }
            } finally {
                exportingLogs.set(false)
            }
        }
    }

    /** 写错误日志到所选 URI（异步：读 DataStore 不阻塞主线程；只读不清除） */
    private fun exportAppErrorsToUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val recent = AppErrorLogRepository.getRecent(applicationContext)
            runOnUiThread {
                if (recent.isEmpty()) {
                    // 兜底：选择文件后窗口内仍无日志（理论不发生）——不写空内容
                    NotificationUtils.showInfoSnackbar(
                        this@SettingsActivity,
                        getString(R.string.app_errors_none),
                        Snackbar.LENGTH_LONG
                    )
                    return@runOnUiThread
                }
                try {
                    val stream = contentResolver.openOutputStream(uri)
                    if (stream == null) {
                        NotificationUtils.showInfoSnackbar(
                            this@SettingsActivity,
                            getString(R.string.app_errors_export_failed),
                            Snackbar.LENGTH_LONG
                        )
                        return@runOnUiThread
                    }
                    OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                        writer.write(AppErrorEntry.toJson(recent))
                    }
                    NotificationUtils.showInfoSnackbar(
                        this@SettingsActivity,
                        getString(R.string.app_errors_export_success),
                        Snackbar.LENGTH_SHORT
                    )
                } catch (e: Exception) {
                    NotificationUtils.showInfoSnackbar(
                        this@SettingsActivity,
                        getString(R.string.app_errors_export_failed),
                        Snackbar.LENGTH_LONG
                    )
                }
            }
        }
    }

    private fun export() {
        try {
            exportBackupLauncher.launch("WebNative_" + DateUtils.compactTimestamp() + ".json")
        } catch (e: ActivityNotFoundException) {
            NotificationUtils.showInfoSnackbar(
                this,
                getString(R.string.no_filemanager),
                Snackbar.LENGTH_LONG
            )
        }
    }

    private fun importBackup() {
        try {
            importBackupLauncher.launch(arrayOf("*/*"))
        } catch (e: ActivityNotFoundException) {
            NotificationUtils.showInfoSnackbar(
                this,
                getString(R.string.no_filemanager),
                Snackbar.LENGTH_LONG
            )
        }
    }
}
