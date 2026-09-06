package com.cylonid.nativealpha.util

import android.app.DownloadManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.WebView
import com.cylonid.nativealpha.R
import com.google.android.material.snackbar.Snackbar
import java.io.UnsupportedEncodingException
import java.net.URLDecoder

/**
 * WebView 下载监听统一装配（宿主/矩阵格同源，原 WebViewPageChrome 内联
 * 实现抽出——矩阵格此前未装配，点击下载完全无反应）。
 *
 * 行为：application/pdf 外跳浏览器；blob: 前缀解码后经 DownloadManager
 * 入队到公共 Download 目录（minSdk=31 无需存储权限），完成通知 + Snackbar。
 */
internal object WebViewDownloads {

    fun install(webview: WebView, activity: Activity) {
        webview.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, _ ->
            if (mimeType == "application/pdf") {
                val i = Intent(Intent.ACTION_VIEW)
                i.data = Uri.parse(dlUrl)
                activity.startActivity(i)
            } else {
                if (dlUrl.isNotEmpty()) {
                    var target = dlUrl
                    if (target.startsWith("blob:")) {
                        target = target.replace("blob:", "")
                        try {
                            target = URLDecoder.decode(target, "UTF-8")
                        } catch (e: UnsupportedEncodingException) {
                            e.printStackTrace()
                        }
                    }
                    val request = try {
                        DownloadManager.Request(Uri.parse(target))
                    } catch (e: Exception) {
                        NotificationUtils.showInfoSnackbar(
                            activity, activity.getString(R.string.file_download),
                            Snackbar.LENGTH_SHORT
                        )
                        null
                    }
                    if (request != null) {
                        val fileName =
                            Utility.getFileNameFromDownload(target, contentDisposition, mimeType)
                        request.setMimeType(mimeType)
                        request.addRequestHeader(
                            "cookie", CookieManager.getInstance().getCookie(target)
                        )
                        request.addRequestHeader("User-Agent", userAgent)
                        request.setTitle(fileName)
                        request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                        )
                        request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, fileName
                        )
                        // minSdk=31，下载到公共目录无需存储权限，直接入队
                        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE)
                            as DownloadManager?
                        if (dm != null) {
                            dm.enqueue(request)
                            NotificationUtils.showInfoSnackbar(
                                activity, activity.getString(R.string.file_download),
                                Snackbar.LENGTH_SHORT
                            )
                        }
                    }
                }
            }
        }
    }
}
