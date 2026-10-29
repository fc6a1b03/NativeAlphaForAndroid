package com.cylonid.nativealpha.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文件选择三分发决策单测（FileChooserDelegate.decidePath 纯函数）：
 * 实机「文件管理器跳相册选截图上传不上」修复的回归锚点——Photo Picker
 * 不可用的设备（Android 11/12 无 GMS backport）单选图片必须走 ACTION_PICK
 * 直连相册，不再交给厂商文件管理器转发。
 */
class FileChooserDelegatePathTest {

    @Test
    fun `image accept with picker goes photo picker`() {
        assertEquals(
            FileChooserDelegate.Path.PHOTO_PICKER,
            FileChooserDelegate.decidePath(arrayOf("image/*"), multiple = false, photoPickerAvailable = true)
        )
    }

    @Test
    fun `image accept without picker single select goes gallery`() {
        assertEquals(
            FileChooserDelegate.Path.GALLERY,
            FileChooserDelegate.decidePath(arrayOf("image/*"), multiple = false, photoPickerAvailable = false)
        )
    }

    @Test
    fun `image accept without picker multi select falls back to legacy`() {
        // ACTION_PICK 不支持多选
        assertEquals(
            FileChooserDelegate.Path.LEGACY,
            FileChooserDelegate.decidePath(arrayOf("image/*"), multiple = true, photoPickerAvailable = false)
        )
    }

    @Test
    fun `image accept with picker multi select still photo picker`() {
        assertEquals(
            FileChooserDelegate.Path.PHOTO_PICKER,
            FileChooserDelegate.decidePath(arrayOf("image/*"), multiple = true, photoPickerAvailable = true)
        )
    }

    @Test
    fun `empty or blank accept means any file goes legacy`() {
        // v2.4.8 语义修正：无 accept=任意文件（HTML 规范），Photo Picker
        // ImageOnly 会拒收非图片——实机实证 zcode 无 accept 输入上传过
        // Downloads 文档。Chromium 对无 accept 输入传 [""] 而非 []，两形态同判
        assertEquals(
            FileChooserDelegate.Path.LEGACY,
            FileChooserDelegate.decidePath(emptyArray(), multiple = false, photoPickerAvailable = true)
        )
        assertEquals(
            FileChooserDelegate.Path.LEGACY,
            FileChooserDelegate.decidePath(emptyArray(), multiple = false, photoPickerAvailable = false)
        )
        assertEquals(
            FileChooserDelegate.Path.LEGACY,
            FileChooserDelegate.decidePath(arrayOf(""), multiple = true, photoPickerAvailable = true)
        )
        assertEquals(
            FileChooserDelegate.Path.LEGACY,
            FileChooserDelegate.decidePath(arrayOf("  "), multiple = false, photoPickerAvailable = true)
        )
    }

    @Test
    fun `non image accept always legacy`() {
        assertEquals(
            FileChooserDelegate.Path.LEGACY,
            FileChooserDelegate.decidePath(arrayOf("application/pdf"), multiple = false, photoPickerAvailable = true)
        )
        assertEquals(
            FileChooserDelegate.Path.LEGACY,
            FileChooserDelegate.decidePath(arrayOf("application/pdf"), multiple = false, photoPickerAvailable = false)
        )
    }

    @Test
    fun `mixed accept with any image counts as image intent`() {
        assertEquals(
            FileChooserDelegate.Path.PHOTO_PICKER,
            FileChooserDelegate.decidePath(
                arrayOf("image/*", "application/pdf"), multiple = false, photoPickerAvailable = true
            )
        )
        assertEquals(
            FileChooserDelegate.Path.GALLERY,
            FileChooserDelegate.decidePath(
                arrayOf("image/*", "application/pdf"), multiple = false, photoPickerAvailable = false
            )
        )
    }
}
