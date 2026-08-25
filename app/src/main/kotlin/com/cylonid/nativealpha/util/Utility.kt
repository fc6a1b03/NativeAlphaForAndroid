package com.cylonid.nativealpha.util

import android.webkit.URLUtil
import java.util.regex.Pattern

object Utility {

    /** 条件断言：不满足抛 AssertionError（内部不变量检查，非用户输入校验） */
    @JvmStatic
    fun assertTrue(condition: Boolean, message: String) {
        if (!condition) {
            throw AssertionError(message)
        }
    }

    @JvmStatic
    fun getFileNameFromDownload(url: String, contentDisposition: String?, mimeType: String?): String {
        var fileName: String? = null
        if (!contentDisposition.isNullOrEmpty()) {
            val pattern = Pattern.compile(
                "attachment; filename=\"(.*)\"; filename\\*=UTF-8''(.*)",
                Pattern.CASE_INSENSITIVE
            )
            val m = pattern.matcher(contentDisposition)
            fileName = if (m.matches()) m.group(2) else null
        }
        if (fileName == null) {
            fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        }
        return fileName
    }
}
