package com.cylonid.nativealpha

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.util.AppMaterialTheme
import com.cylonid.nativealpha.util.ThemeUtils
import com.cylonid.nativealpha.ui.GlobalSettingsScreen
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.NotificationUtils
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局设置页：Compose 实现（分区块卡片：通用 / 备份）。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)
        setContent {
            AppMaterialTheme {
                GlobalSettingsScreen(
                    onBack = { finish() },
                    onSave = { modified ->
                        // 落库 + 立即应用 UI 模式
                        DataManager.getInstance().settings = modified
                        DataManager.getInstance().saveGlobalSettings()
                        ThemeUtils.applyUiMode()
                        finish()
                    },
                    onExport = { export() },
                    onImport = { importBackup() },
                    onGlobalWebApp = {
                        val intent = Intent(this, WebAppSettingsActivity::class.java)
                        intent.putExtra(
                            Const.INTENT_WEBAPPID,
                            DataManager.getInstance().settings.globalWebApp.ID
                        )
                        intent.setAction(Intent.ACTION_VIEW)
                        startActivity(intent)
                    }
                )
            }
        }
    }

    private fun export() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        intent.putExtra(Intent.EXTRA_TITLE, "WebNative_" + sdf.format(Date()))
        try {
            startActivityForResult(intent, Const.CODE_WRITE_FILE)
        } catch (e: ActivityNotFoundException) {
            NotificationUtils.showInfoSnackbar(
                this,
                getString(R.string.no_filemanager),
                Snackbar.LENGTH_LONG
            )
        }
    }

    private fun importBackup() {
        val intent = Intent().setType("*/*").setAction(Intent.ACTION_GET_CONTENT)
        try {
            startActivityForResult(
                Intent.createChooser(intent, "Select a file"),
                Const.CODE_OPEN_FILE
            )
        } catch (e: ActivityNotFoundException) {
            NotificationUtils.showInfoSnackbar(
                this,
                getString(R.string.no_filemanager),
                Snackbar.LENGTH_LONG
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Const.CODE_WRITE_FILE && resultCode == RESULT_OK) {
            val uri = data?.data
            DataManager.getInstance().saveGlobalSettings()
            if (uri != null && DataManager.getInstance().saveSharedPreferencesToFile(uri)) {
                NotificationUtils.showInfoSnackbar(
                    this,
                    getString(R.string.export_success),
                    Snackbar.LENGTH_SHORT
                )
            } else {
                NotificationUtils.showInfoSnackbar(
                    this,
                    getString(R.string.export_failed),
                    Snackbar.LENGTH_LONG
                )
            }
        }
        if (requestCode == Const.CODE_OPEN_FILE && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null && DataManager.getInstance().loadSharedPreferencesFromFile(uri)) {
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies(null)
                DataManager.getInstance().loadAppData()
                // 成功提示用 Toast：页面即将 finish，Snackbar 无法显示
                Toast.makeText(
                    this,
                    getString(R.string.import_success, DataManager.getInstance().getActiveWebsitesCount()),
                    Toast.LENGTH_LONG
                ).show()
                val i = Intent(this, MainActivity::class.java)
                i.putExtra(Const.INTENT_BACKUP_RESTORED, true)
                finish()
                startActivity(i)
            } else {
                NotificationUtils.showInfoSnackbar(
                    this,
                    getString(R.string.import_failed),
                    Snackbar.LENGTH_LONG
                )
            }
        }
    }
}
