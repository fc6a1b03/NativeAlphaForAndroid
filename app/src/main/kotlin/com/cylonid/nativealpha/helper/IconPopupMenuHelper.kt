package com.cylonid.nativealpha.helper

import android.content.Context
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.PopupMenu
import androidx.annotation.MenuRes


object IconPopupMenuHelper {
    @JvmStatic
    fun getMenu(v: View, @MenuRes menuRes: Int, c: Context): PopupMenu {
        val popup = PopupMenu(c, v, Gravity.END)
        // minSdk=31，setForceShowIcon 无需版本判断
        popup.setForceShowIcon(true)
        popup.menuInflater.inflate(menuRes, popup.menu)

        return popup
    }
}