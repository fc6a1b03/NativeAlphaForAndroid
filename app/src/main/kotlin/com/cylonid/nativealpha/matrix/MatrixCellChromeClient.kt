package com.cylonid.nativealpha.matrix

import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * 矩阵格 Chrome client：标题回调驱动工具条文案（v1 仅此需求；
 * 文件选择/权限/弹窗等宿主能力不开放给矩阵格）。
 */
internal class MatrixCellChromeClient(
    private val engine: MatrixEngine,
    private val cellIndex: Int
) : WebChromeClient() {

    override fun onReceivedTitle(view: WebView, title: String) {
        engine.onCellTitle(cellIndex, title)
        super.onReceivedTitle(view, title)
    }
}
