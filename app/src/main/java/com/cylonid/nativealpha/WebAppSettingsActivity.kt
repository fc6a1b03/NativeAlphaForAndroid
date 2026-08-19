package com.cylonid.nativealpha

import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TimePicker
import com.cylonid.nativealpha.activities.ToolbarBaseActivity
import com.cylonid.nativealpha.databinding.WebappSettingsBinding
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.DateUtils.convertStringToCalendar
import com.cylonid.nativealpha.util.DateUtils.getHourMinFormat
import com.cylonid.nativealpha.util.Utility
import java.util.Calendar

class WebAppSettingsActivity : ToolbarBaseActivity<WebappSettingsBinding>() {
    var webappID: Int = -1
    var webapp: WebApp? = null
    private var isGlobalWebApp: Boolean = false

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setToolbarTitle(getString(R.string.web_app_settings))

        webappID = intent.getIntExtra(Const.INTENT_WEBAPPID, -1)
        Utility.Assert(webappID != -1, "WebApp ID could not be retrieved.")
        isGlobalWebApp = webappID == DataManager.getInstance().settings.globalWebApp.ID

        if (isGlobalWebApp) {
            webapp = DataManager.getInstance().settings.globalWebApp
            prepareGlobalWebAppScreen()
        } else webapp = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true)

        if (webapp == null) {
            finish()
            return
        }
        val modifiedWebapp = WebApp(webapp!!)
        binding.webapp = modifiedWebapp
        binding.activity = this@WebAppSettingsActivity

        setupSaveAndCancel(modifiedWebapp)
        setupDarkModeElements()
        setupPlusSettings()
        setupDesktopUserAgentHint()
        setupShortcutButton()
        setupSwitchListeners(webapp!!)

    }

    override fun inflateBinding(layoutInflater: LayoutInflater): WebappSettingsBinding {
        return WebappSettingsBinding.inflate(layoutInflater)
    }

    private fun setupSwitchListeners(webapp: WebApp) {
        webapp.onSwitchExpertSettingsChanged(
            binding.switchExpertSettings,
            webapp.isShowExpertSettings
        )
        webapp.onSwitchOverrideGlobalSettingsChanged(
            binding.switchOverrideGlobal,
            webapp.isOverrideGlobalSettings
        )
    }
    
    private fun setupSaveAndCancel(modifiedWebapp: WebApp) {
        binding.btnSave.setOnClickListener {
            // 保存到 DataManager（沙箱已移除，无需进程管理）
            if (isGlobalWebApp) {
                DataManager.getInstance().settings.globalWebApp = modifiedWebapp
                DataManager.getInstance().saveGlobalSettings()
            } else {
                DataManager.getInstance().replaceWebApp(modifiedWebapp)
            }

            val i = Intent(this@WebAppSettingsActivity, MainActivity::class.java)
            i.putExtra(Const.INTENT_WEBAPP_CHANGED, true)
            finish()
            startActivity(i)
        }

        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun setupDarkModeElements() {
        val txtBeginDarkMode = binding.textDarkModeBegin
        val txtEndDarkMode = binding.textDarkModeEnd

        txtBeginDarkMode.setOnClickListener {
            showTimePicker(
                txtBeginDarkMode
            )
        }
        txtEndDarkMode.setOnClickListener {
            showTimePicker(
                txtEndDarkMode
            )
        }

    }

    private fun setupDesktopUserAgentHint() {
        val txt = binding.txthintUserAgent
        txt.text = Html.fromHtml(getString(R.string.hint_user_agent), Html.FROM_HTML_MODE_LEGACY)
        txt.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun setupShortcutButton() {
        binding.btnRecreateShortcut.setOnClickListener {
            val frag = ShortcutDialogFragment.newInstance(webapp)
            frag.show(supportFragmentManager, "SCFetcher-" + webapp!!.ID)
        }
    }

    private fun setupPlusSettings() {
        // 单一 flavor：Plus 功能全部保留，无 flavor 门控（阶段 2 删除沙箱相关 section）
    }

    private fun showTimePicker(txtField: EditText) {
        val c = convertStringToCalendar(txtField.text.toString())
        val timePickerDialog = TimePickerDialog(
            this@WebAppSettingsActivity, R.style.AppTheme,
            { timePicker: TimePicker?, selectedHour: Int, selectedMinute: Int ->
                val datetime = Calendar.getInstance()
                datetime[Calendar.HOUR_OF_DAY] = selectedHour
                datetime[Calendar.MINUTE] = selectedMinute
                txtField.setText(getHourMinFormat().format(datetime.time))
            }, c!![Calendar.HOUR_OF_DAY], c[Calendar.MINUTE], true
        )
        timePickerDialog.show()
    }

    private fun prepareGlobalWebAppScreen() {
        binding.btnRecreateShortcut.visibility = View.GONE
        binding.labelWebAppName.visibility = View.GONE
        binding.txtWebAppName.visibility = View.GONE
        binding.switchOverrideGlobal.visibility = View.GONE
        binding.sectionSSL.visibility = View.GONE
        binding.sectionSandbox.visibility = View.GONE
        binding.labelTitle.visibility = View.GONE
        binding.labelEditableBaseUrl.visibility = View.GONE
        binding.textBaseUrl.visibility = View.GONE

        binding.globalSettingsInfoText.visibility = View.VISIBLE

        setToolbarTitle(getString(R.string.global_web_app_settings))
    }
}


