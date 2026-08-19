package com.cylonid.nativealpha

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.cylonid.nativealpha.activities.ToolbarBaseActivity
import com.cylonid.nativealpha.databinding.GlobalSettingsBinding
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.GlobalSettings
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.NotificationUtils
import com.cylonid.nativealpha.util.Utility
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : ToolbarBaseActivity<GlobalSettingsBinding>() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setToolbarTitle(getString(R.string.global_settings))

        val settings = DataManager.getInstance().settings
        val modified_settings = settings.copy()
        binding.settings = modified_settings
        binding.btnGlobalWebApp.setOnClickListener { v: View? ->
            val intent = Intent(
                this@SettingsActivity,
                WebAppSettingsActivity::class.java
            )
            intent.putExtra(
                Const.INTENT_WEBAPPID,
                settings.globalWebApp.ID
            )
            intent.setAction(Intent.ACTION_VIEW)
            startActivity(intent)
        }

        setupLanguageSpinner()


        binding.btnExportSettings.setOnClickListener { v: View? ->
            val intent =
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
            val sdf =
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val currentDateTime = sdf.format(Date())
            intent.putExtra(Intent.EXTRA_TITLE, "NativeAlpha_$currentDateTime")
            try {
                startActivityForResult(intent, Const.CODE_WRITE_FILE)
            } catch (e: ActivityNotFoundException) {
                NotificationUtils.showInfoSnackbar(
                    this@SettingsActivity,
                    getString(R.string.no_filemanager),
                    Snackbar.LENGTH_LONG
                )
                e.printStackTrace()
            }
        }

        binding.btnImportSettings.setOnClickListener { v: View? ->
            val intent = Intent().setType("*/*").setAction(Intent.ACTION_GET_CONTENT)
            try {
                startActivityForResult(
                    Intent.createChooser(intent, "Select a file"),
                    Const.CODE_OPEN_FILE
                )
            } catch (e: ActivityNotFoundException) {
                NotificationUtils.showInfoSnackbar(
                    this@SettingsActivity,
                    getString(R.string.no_filemanager),
                    Snackbar.LENGTH_LONG
                )
                e.printStackTrace()
            }
        }

        binding.btnSave.setOnClickListener {
            DataManager.getInstance().settings = modified_settings
            finish()
        }

        binding.btnCancel.setOnClickListener {
            finish()
        }
    }

    override fun inflateBinding(layoutInflater: LayoutInflater): GlobalSettingsBinding {
        return GlobalSettingsBinding.inflate(layoutInflater)
    }

    /** 语言切换：跟随系统 / 中文 / English（AppCompatDelegate 无重启切换） */
    private fun setupLanguageSpinner() {
        val options = resources.getStringArray(R.array.language_options)
        binding.spinnerLanguage.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, options
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // 当前语言定位
        val currentLang = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        binding.spinnerLanguage.setSelection(
            when {
                currentLang.isEmpty() -> 0 // 跟随系统
                currentLang.startsWith("zh") -> 1
                else -> 2
            }
        )

        // 防初始化误触发：Spinner 设置 adapter 后会回调一次 onItemSelected，需跳过
        var isInitializing = true
        binding.spinnerLanguage.post { isInitializing = false }

        binding.spinnerLanguage.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isInitializing) return // 跳过初始化回调
                val tags = when (position) {
                    0 -> LocaleListCompat.getEmptyLocaleList()
                    1 -> LocaleListCompat.forLanguageTags("zh")
                    else -> LocaleListCompat.forLanguageTags("en")
                }
                AppCompatDelegate.setApplicationLocales(tags)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Const.CODE_WRITE_FILE && resultCode == RESULT_OK) {
            val uri = data?.data

            DataManager.getInstance()
                .saveGlobalSettings() //Needed to write legacy settings to new XML

            if (!DataManager.getInstance().saveSharedPreferencesToFile(uri)) {
                NotificationUtils.showInfoSnackbar(
                    this,
                    getString(R.string.export_failed),
                    Snackbar.LENGTH_LONG
                )
            } else {
                NotificationUtils.showInfoSnackbar(
                    this,
                    getString(R.string.export_success),
                    Snackbar.LENGTH_SHORT
                )
            }
        }
        if (requestCode == Const.CODE_OPEN_FILE && resultCode == RESULT_OK) {
            val uri = data?.data

            if (!DataManager.getInstance().loadSharedPreferencesFromFile(uri)) {
                NotificationUtils.showInfoSnackbar(
                    this,
                    getString(R.string.import_failed),
                    Snackbar.LENGTH_LONG
                )
            } else {
                val i = Intent(this@SettingsActivity, MainActivity::class.java)

                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies(null)

                DataManager.getInstance().loadAppData()
                i.putExtra(Const.INTENT_BACKUP_RESTORED, true)
                finish()
                startActivity(i)
            }
        }
    }
}
