package com.cylonid.nativealpha.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.net.toUri
import com.cylonid.nativealpha.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 版本更新检查器：GitHub API 查最新 Release → 对比当前版本 → 异步下载 APK → 提示安装。
 *
 * 设计：
 * - 检查：GitHub API（releases/latest）拿 tag + APK 下载 URL（后台 IO 协程，不阻塞 UI）
 * - 下载：DownloadManager（系统下载，后台进行，无需用户等待；完成后长留安装包，
 *   用户安装后自动删除——见 onPause/onDestroy 清理）
 * - 安装：下载完成广播接收器触发 [promptInstall]，使用 FileProvider 生成 content:// URI
 * - 版本对比：语义化版本比较（如 2.1.13 < 2.1.14；2.1.14-beta < 2.1.14）
 *
 * GitHub 仓库：fc6a1b03/NativeAlphaForAndroid（与项目 URL 一致）
 */
object UpdateChecker {

    private const val REPO = "fc6a1b03/NativeAlphaForAndroid"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** 是否检查中（防重复触发） */
    @Volatile
    var checking = false
        private set

    /** DownloadManager 下载 ID → 版本 tag 映射（安装广播用） */
    private val downloadIdToTag = Collections.synchronizedMap(HashMap<Long, String>())

    /** 下载完成广播：DownloadManager 通知后触发安装 */
    class DownloadCompleteReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == -1L) return
            val tag = synchronized(downloadIdToTag) { downloadIdToTag.remove(id) } ?: return
            // 校验下载真实成功：过滤伪造广播或失败任务
            if (!isDownloadSuccessful(context, id)) return
            promptInstall(context, tag)
        }

        /** 查询 DownloadManager 确认指定 ID 的状态为 STATUS_SUCCESSFUL */
        private fun isDownloadSuccessful(context: Context, id: Long): Boolean {
            return try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(id)
                dm.query(query)?.use { cursor ->
                    if (!cursor.moveToFirst()) return false
                    val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    statusIdx >= 0 && cursor.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL
                } ?: false
            } catch (e: Exception) {
                false
            }
        }
    }

    /** 更新检查结果 */
    sealed class Result {
        /** 有更新 */
        data class HasUpdate(
            val tag: String,
            val downloadUrl: String,
            val notes: String
        ) : Result()

        /** 已是最新 */
        data class NoUpdate(val currentVersion: String) : Result()

        /**
         * 检查失败。
         *
         * @property kind 失败类别——[KIND_NETWORK]（GitHub API 不可达/空响应，
         * 大陆网络常态）**不应写入错误日志**（非应用缺陷，环境性高频事件，
         * ERROR+堆栈只会污染日志）；[KIND_NO_ASSET]/[KIND_UNEXPECTED] 才值得留档
         */
        data class Error(
            val kind: String,
            val detail: String,
            val displayMessage: String
        ) : Result()
    }

    // 失败类别（network_unavailable=环境常态，调用方不应写错误日志）
    const val KIND_NETWORK = "network_unavailable"
    const val KIND_NO_ASSET = "no_apk_asset"
    const val KIND_UNEXPECTED = "unexpected"

    /**
     * 语义化版本比较：a > b 返回 1，a == b 返回 0，a < b 返回 -1。
     *
     * 支持 `v2.1.34`、`2.1.34-beta`、`2.1.34-rc1` 等形式。
     * 数字段相同时，预发布版本（带 `-` 后缀）始终小于正式版本。
     */
    fun compareVersions(a: String, b: String): Int {
        fun parse(version: String): Pair<List<Int>, String?> {
            val trimmed = version.trim().removePrefix("v")
            val parts = trimmed.split("-", limit = 2)
            val numbers = parts[0].split(".").mapNotNull { it.toIntOrNull() }
            val prerelease = parts.getOrNull(1)
            return numbers to prerelease
        }

        val (numbersA, preA) = parse(a)
        val (numbersB, preB) = parse(b)

        val maxParts = maxOf(numbersA.size, numbersB.size)
        for (i in 0 until maxParts) {
            val va = numbersA.getOrNull(i) ?: 0
            val vb = numbersB.getOrNull(i) ?: 0
            if (va > vb) return 1
            if (va < vb) return -1
        }

        // 数字相同：预发布 < 正式；都有预发布则按字符串比较
        return when {
            preA == null && preB == null -> 0
            preA == null -> 1
            preB == null -> -1
            else -> preA.compareTo(preB)
        }
    }

    /** 当前应用 versionName */
    @Suppress("DEPRECATION") // minSdk 31 < 33，legacy getPackageInfo 分支仍需覆盖 API 31/32
    fun currentVersionName(context: Context): String {
        return try {
            val pm = context.packageManager
            val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                pm.getPackageInfo(context.packageName, 0)
            }
            pkg.versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 检查最新版本（异步）。回调参数为 [Result]。 */
    fun check(context: Context, onResult: (Result) -> Unit) {
        if (checking) return
        checking = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (latestTag, downloadUrl, notes) = fetchLatestRelease()
                val current = currentVersionName(context)
                when {
                    latestTag.isEmpty() -> {
                        // GitHub API 不可达/空响应：网络环境常态（大陆直连被墙），
                        // 降级为 network_unavailable——不携带异常堆栈、不写错误日志
                        withContext(Dispatchers.Main) {
                            onResult(
                                Result.Error(
                                    KIND_NETWORK,
                                    "GitHub API unreachable or empty response",
                                    context.getString(R.string.update_check_failed)
                                )
                            )
                        }
                    }
                    downloadUrl.isEmpty() -> {
                        withContext(Dispatchers.Main) {
                            onResult(
                                Result.Error(
                                    KIND_NO_ASSET,
                                    "No APK asset found for $latestTag",
                                    context.getString(R.string.update_check_failed)
                                )
                            )
                        }
                    }
                    compareVersions(latestTag, current) > 0 -> {
                        withContext(Dispatchers.Main) {
                            onResult(Result.HasUpdate(latestTag, downloadUrl, notes))
                        }
                    }
                    else -> {
                        withContext(Dispatchers.Main) {
                            onResult(Result.NoUpdate(current))
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(
                        Result.Error(
                            KIND_UNEXPECTED,
                            e.message ?: "unexpected",
                            context.getString(R.string.update_check_failed)
                        )
                    )
                }
            } finally {
                checking = false
            }
        }
    }

    /** 从 GitHub API 拿最新 tag、APK 下载 URL、更新说明（release body） */
    private suspend fun fetchLatestRelease(): Triple<String, String, String> =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(API_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "WebNative-UpdateChecker")
                val code = conn.responseCode
                if (code != 200) return@withContext Triple("", "", "")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "")
                // 更新说明（release body，可能含 Markdown；截断防过长）
                // 完整 release body（不截断——硬截 500 字符会把更新预览的变更记录
                // 掐成残缺 id）。展示端 MdRenderer 滚动渲染完整内容；宽松上限
                // 仅防御异常巨体（64K 字符远超正常 release body）
                val notes = json.optString("body", "").take(65536)
                // 找 APK 资产（优先 arm64-v8a，其次任意 .apk）
                val assets = json.optJSONArray("assets")
                if (assets == null || assets.length() == 0) {
                    return@withContext Triple(tag, "", notes)
                }
                var apkUrl = ""
                // 从 assets 找 .apk
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
                Triple(tag, apkUrl, notes)
            } catch (e: Exception) {
                Triple("", "", "")
            }
        }

    /**
     * 异步下载 APK（DownloadManager 系统下载，后台进行）。
     *
     * @param tag 版本 tag，用于命名安装包和下载完成后的安装定位
     * @return 下载任务 ID，-1 表示失败
     */
    fun download(context: Context, downloadUrl: String, tag: String): Long {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(downloadUrl.toUri())
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            request.setTitle(context.getString(R.string.update_notification_title))
            request.setDescription(context.getString(R.string.update_notification_desc))
            val fileName = apkFileName(tag)
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
            val id = dm.enqueue(request)
            synchronized(downloadIdToTag) { downloadIdToTag[id] = tag }
            id
        } catch (e: Exception) {
            -1L
        }
    }

    /** 提示安装：打开下载完成的 APK（FileProvider 兼容） */
    fun promptInstall(context: Context, tag: String) {
        try {
            val fileName = apkFileName(tag)
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            if (!file.exists()) return
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // 安装失败静默（用户可手动找安装包）
        }
    }

    /** 根据版本 tag 生成 APK 文件名 */
    private fun apkFileName(tag: String): String = "WebNative-${tag}.apk"
}
