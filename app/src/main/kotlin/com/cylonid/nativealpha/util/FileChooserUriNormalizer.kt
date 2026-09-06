package com.cylonid.nativealpha.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 文件选择返回 URI 的归一化（FileChooserDelegate 的回执前置层）。
 *
 * 定位（诚实声明）：**主修复是分发层绕开断链入口**（decidePath 的
 * ACTION_PICK 直连相册）；本对象是防御+取证层——
 * - 可读的 content URI 直通（正常路径，零拷贝）；
 * - file://（app 有读权限的场景，如 app 私有/缓存文件）拷贝后经
 *   FileProvider 重发，交一个必可读的 content URI 给 WebView；
 * - 不可读的 content URI（厂商文件管理器转发断链）：应用进程读不到字节
 *   物理上就无法补救（Chrome 同样失败），拷贝必然失败 → 原样透传 + Log.e
 *   取证（真正的解法是入口层不走到这条路径）。
 * 全程 Log.d/w/e 留痕：实机 logcat -s FileChooser 可完整还原「返回了什么、
 * 断在哪」。
 */
internal object FileChooserUriNormalizer {

    private const val TAG = "FileChooser"

    /** 流式拷贝缓冲（64KB） */
    private const val BUFFER_BYTES = 64 * 1024

    /** 归一化结果：uris=回执 WebView 的最终 URI；degraded=物化失败被迫透传的
     *  原 URI（物理不可读，调用方应提示用户——页面端只会表现为静默不上传） */
    data class Result(val uris: Array<Uri>?, val degraded: List<Uri>)

    private val EMPTY = Result(null, emptyList())

    /** 逐个确保可读：可读直通，否则尽力物化，仍失败透传并记入 degraded */
    internal fun normalize(context: Context, uris: List<Uri>?): Result {
        if (uris.isNullOrEmpty()) return EMPTY
        val degraded = mutableListOf<Uri>()
        val out = uris.map { ensureWebViewReadable(context, it, degraded) }.toTypedArray()
        return Result(out, degraded)
    }

    internal fun ensureWebViewReadable(
        context: Context,
        uri: Uri,
        degraded: MutableList<Uri>
    ): Uri {
        if (uri.scheme == "content" && canRead(context.contentResolver, uri)) {
            Log.d(TAG, "uri passthrough: $uri")
            return uri
        }
        val copied = copyToCache(context, uri)
        if (copied != null) {
            Log.w(TAG, "uri materialized via FileProvider: $copied (origin=$uri)")
            return copied
        }
        // 物理读不到字节（grant 断链/file 无权限）：无法本地补救，透传让
        // 内核再试一次；记入 degraded 由调用方提示用户+错误日志取证
        Log.e(TAG, "uri unreadable and cache copy failed, passing through: $uri")
        degraded.add(uri)
        return uri
    }

    /** 应用进程能否读取该 URI（WebView 渲染进程经应用进程代理读取，判定等价） */
    private fun canRead(resolver: ContentResolver, uri: Uri): Boolean = try {
        resolver.openInputStream(uri)?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    /** 流式拷贝到 cache/chooser，经 FileProvider 发 content URI（扩展名保 MIME）。
     *  任何一环失败（含 FileProvider 配置异常）都降级为 null→透传：兜底层自己
     *  不允许成为崩溃源。 */
    private fun copyToCache(context: Context, uri: Uri): Uri? {
        val input = try {
            context.contentResolver.openInputStream(uri) ?: return null
        } catch (e: Exception) {
            Log.e(TAG, "copy openInputStream failed for $uri", e)
            return null
        }
        return input.use { stream ->
            try {
                val dir = File(context.cacheDir, "chooser").apply { mkdirs() }
                val mime = context.contentResolver.getType(uri)
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                    // file:// 从原文件名保扩展名（getType 对 file scheme 为 null）
                    ?: uri.lastPathSegment?.substringAfterLast('.', "")
                        ?.takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } }
                    ?: "dat"
                val out = File(dir, "upload_${System.currentTimeMillis()}.$ext")
                out.outputStream().use { output -> stream.copyTo(output, BUFFER_BYTES) }
                // authority 须与 manifest 的 androidx.core.content.FileProvider 声明一致
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", out)
            } catch (e: Exception) {
                Log.e(TAG, "cache materialize failed for $uri", e)
                null
            }
        }
    }
}
