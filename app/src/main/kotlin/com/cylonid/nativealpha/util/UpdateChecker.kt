package com.cylonid.nativealpha.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import android.webkit.MimeTypeMap
import com.cylonid.nativealpha.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 版本更新检查器：GitHub API 查最新 Release → 对比当前版本 → 异步下载 APK → 提示安装。
 *
 * 设计：
 * - 检查：GitHub API（releases/latest）拿 tag + APK 下载 URL（后台 IO 协程，不阻塞 UI）
 * - 下载：DownloadManager（系统下载，后台进行，无需用户等待；完成后长留安装包，
 *   用户安装后自动删除——见 onPause/onDestroy 清理）
 * - 安装：下载完成提示「安装」（Intent.ACTION_VIEW apk，Android 8+ 需 FileProvider）
 * - 版本对比：versionName 语义化比较（如 2.1.13 > 2.1.12）
 *
 * GitHub 仓库：fc6a1b03/NativeAlphaForAndroid（与项目 URL 一致）
 */
object UpdateChecker {

    private const val REPO = "fc6a1b03/NativeAlphaForAndroid"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** 仓库根 URL（APK 下载拼装） */
    private const val REPO_URL = "https://github.com/$REPO"

    /** 是否检查中（防重复触发） */
    @Volatile
    var checking = false
        private set

    /** 最新下载的 APK 文件（安装后删除） */
    @Volatile
    var downloadedApk: File? = null
        private set

    /** 语义化版本比较：a > b 返回 1，a == b 返回 0，a < b 返回 -1 */
    fun compareVersions(a: String, b: String): Int {
        try {
            val pa = a.trim().removePrefix("v").split(".")
            val pb = b.trim().removePrefix("v").split(".")
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val va = pa.getOrNull(i)?.toIntOrNull() ?: 0
                val vb = pb.getOrNull(i)?.toIntOrNull() ?: 0
                if (va > vb) return 1
                if (va < vb) return -1
            }
            return 0
        } catch (e: Exception) {
            return 0
        }
    }

    /** 检查最新版本（异步）。回调：onResult(hasUpdate, latestTag, downloadUrl, releaseNotes) */
    fun check(context: Context, onResult: (Boolean, String, String, String) -> Unit) {
        if (checking) return
        checking = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val (latestTag, downloadUrl, notes) = fetchLatestRelease()
                val current = currentVersionName(context)
                if (downloadUrl.isEmpty()) {
                    withContext(Dispatchers.Main) { onResult(false, latestTag, "", notes) }
                } else {
                    val hasUpdate = compareVersions(latestTag, current) > 0
                    withContext(Dispatchers.Main) {
                        onResult(hasUpdate, latestTag, downloadUrl, notes)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(false, "", "", "") }
            } finally {
                checking = false
            }
        }
    }

    /** 从 GitHub API 拿最新 tag、APK 下载 URL、更新说明（release body） */
    private suspend fun fetchLatestRelease(): Triple<String, String, String> = withContext(Dispatchers.IO) {
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
            val notes = json.optString("body", "").take(500)
            // 找 APK 资产（优先 arm64-v8a，其次任意 .apk）
            val assets = json.optJSONArray("assets")
            if (assets == null || assets.length() == 0) return@withContext Triple(tag, "", notes)
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
            if (apkUrl.isEmpty()) {
                // 兜底：GitHub release 下载 URL（AGP 产物名 WebNative-v*.apk）
                apkUrl = "$REPO_URL/releases/download/${tag}/WebNative-${tag}.apk"
            }
            Triple(tag, apkUrl, notes)
        } catch (e: Exception) {
            Triple("", "", "")
        }
    }

    /** 当前应用 versionName */
    private fun currentVersionName(context: Context): String {
        return try {
            val pkg = context.packageManager.getPackageInfo(
                context.packageName, 0
            )
            pkg.versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 异步下载 APK（DownloadManager 系统下载，后台进行） */
    fun download(context: Context, downloadUrl: String): Boolean {
        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val request = DownloadManager.Request(downloadUrl.toUri())
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setTitle(context.getString(R.string.update_notification_title))
            request.setDescription(context.getString(R.string.update_notification_desc))
            // 下载到公共下载目录（长留，安装后删除）
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "WebNative-latest.apk"
            )
            dm.enqueue(request)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 提示安装：打开下载完成的 APK（FileProvider 兼容） */
    fun promptInstall(context: Context) {
        try {
            val file = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "WebNative-latest.apk"
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
}
