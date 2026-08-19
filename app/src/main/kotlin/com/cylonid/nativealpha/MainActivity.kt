package com.cylonid.nativealpha

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.ui.MainScreen
import com.cylonid.nativealpha.util.Const
import com.cylonid.nativealpha.util.EntryPointUtils.entryPointReached
import com.cylonid.nativealpha.util.WebViewLauncher

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)

        entryPointReached(this)

        setContent {
            MaterialTheme {
                val refreshKey = MainActivity.refreshTrigger
                val webApps: List<WebApp> = remember(refreshKey) {
                    DataManager.getInstance().activeWebsites.filterNotNull()
                }

                MainScreen(
                    webApps = webApps,
                    onAddClick = {
                        buildAddWebsiteDialog(getString(R.string.add_webapp))
                    },
                    onOpenWebApp = { webApp ->
                        WebViewLauncher.startWebView(webApp, this)
                    },
                    onOpenSettings = { webApp ->
                        val intent = Intent(this, WebAppSettingsActivity::class.java)
                        intent.putExtra(Const.INTENT_WEBAPPID, webApp.ID)
                        startActivity(intent)
                    },
                    onDeleteWebApp = { webApp ->
                        buildDeleteDialog(webApp)
                    },
                    onGlobalSettingsClick = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onAboutClick = {
                        startActivity(Intent(this, AboutActivity::class.java))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 数据可能在设置页被修改，重新加载后刷新 Compose 状态
        DataManager.getInstance().loadAppData()
        refreshTrigger++
    }

    companion object {
        /** 用于触发 Compose 列表刷新的计数器（onResume 时自增） */
        var refreshTrigger: Int = 0
    }

    private fun buildDeleteDialog(webApp: WebApp) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_question))
            .setMessage(webApp.title)
            .setPositiveButton(R.string.ok) { _: DialogInterface?, _: Int ->
                DataManager.getInstance().getWebAppIgnoringGlobalOverride(webApp.ID, true)?.markInactive(this)
                // 刷新列表（删除后立即移除条目）
                refreshTrigger++
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildAddWebsiteDialog(title: String) {
        val input = EditText(this)
        input.hint = getString(R.string.enter_valid_url)
        val nameInput = EditText(this)
        nameInput.hint = getString(R.string.display_name_hint)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(input)
            addView(nameInput)
        }
        val dialog = AlertDialog.Builder(this@MainActivity)
            .setView(container)
            .setTitle(title)
            .setPositiveButton(R.string.ok) { _: DialogInterface, _: Int ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    val urlWithProtocol =
                        if (url.startsWith("https://") || url.startsWith("http://")) url else "https://$url"
                    val newSite = WebApp(
                        urlWithProtocol,
                        DataManager.getInstance().incrementedID,
                        DataManager.getInstance().incrementedOrder
                    )
                    // 用户自定义显示名称（可选，留空则回退 title）
                    val displayName = nameInput.text.toString().trim()
                    if (displayName.isNotEmpty()) {
                        newSite.displayName = displayName
                    }
                    newSite.applySettingsForNewWebApp()
                    DataManager.getInstance().addWebsite(newSite)
                    // 刷新列表（添加后立即显示新条目）
                    refreshTrigger++
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.show()
        val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        okButton.isEnabled = false
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                okButton.isEnabled = !s.isNullOrBlank()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }
}
