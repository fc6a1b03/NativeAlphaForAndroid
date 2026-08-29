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
 * 关键方言对齐：GitHub（GFM）把「单个换行」渲染为换行，而 CommonMark 标准
 * 渲染为空格——不处理会导致 release notes 在 GitHub 显示正常换行的内容进
 * App 后全部挤成一行（用户实测反馈）。项目内置的 Atlassian commonmark
 * 0.13.0 没有 breaks 解析器选项（jar javap 实证），故在渲染入口做文本预处理：
 * 孤立单换行 → 行尾双空格（CommonMark 硬换行语法），行为与 GFM breaks 等价。
 * 围栏代码块内的换行被同样处理，但行尾空格在代码块中不可见，无副作用。
 *
 * 单例 lazily 初始化（线程安全），重复调用复用同一实例（低损耗）。
 */
object MdRenderer {

    /** 孤立单换行（前后均非换行）→ CommonMark 硬换行（行尾双空格） */
    private val SOFT_BREAK = Regex("(?<!\n)\n(?!\n)")

    @Volatile
    private var markwon: Markwon? = null

    /** 渲染 Markdown 为 Spanned（null/空输入返回空 Spannable） */
    @JvmStatic
    fun render(context: Context, md: String?): Spanned {
        if (md.isNullOrBlank()) return android.text.SpannableString("")
        val m = markwon ?: synchronized(this) {
            markwon ?: Markwon.create(context).also { markwon = it }
        }
        return m.toMarkdown(SOFT_BREAK.replace(md, "  \n"))
    }
}
