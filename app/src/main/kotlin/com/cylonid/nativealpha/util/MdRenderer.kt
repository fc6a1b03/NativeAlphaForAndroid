package com.cylonid.nativealpha.util

import android.content.Context
import android.text.Spanned
import io.noties.markwon.Markwon

/**
 * Markdown 渲染工具：更新内容（GitHub Release notes）支持 Markdown 展示。
 *
 * 基于 Markwon（Noties 官方 Android Markdown 渲染库，零 JS 依赖，轻量高性能）：
 * - 纯文本 → Spanned（TextView/AlertDialog 直接设置）
 * - 支持标题/加粗/斜体/列表/代码块/链接（GitHub 常见 md 语法）
 *
 * 单例 lazily 初始化（线程安全），重复调用复用同一实例（低损耗）。
 */
object MdRenderer {

    @Volatile
    private var markwon: Markwon? = null

    /** 渲染 Markdown 为 Spanned（null/空输入返回空 Spannable） */
    @JvmStatic
    fun render(context: Context, md: String?): Spanned {
        if (md.isNullOrBlank()) return android.text.SpannableString("")
        val m = markwon ?: synchronized(this) {
            markwon ?: Markwon.create(context).also { markwon = it }
        }
        return m.toMarkdown(md)
    }
}
