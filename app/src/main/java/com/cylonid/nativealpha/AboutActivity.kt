package com.cylonid.nativealpha

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.cylonid.nativealpha.databinding.ActivityToolbarBaseBinding
import com.mikepenz.aboutlibraries.LibsBuilder

/**
 * 关于页：版本号 + 许可 + 开源库声明（GPL-3.0 合规）。
 * 保持轻量 View 实现（非核心页面，无需 Compose 化）。
 */
class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val baseBinding = ActivityToolbarBaseBinding.inflate(layoutInflater)
        setContentView(baseBinding.root)

        baseBinding.activityContent.addView(buildAboutView())

        val toolbar = baseBinding.toolbar.topAppBar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.app_info)

        onBackPressedDispatcher.addCallback(this) {
            finish()
        }

        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun buildAboutView(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }

        // 版本号
        container.addView(TextView(this).apply {
            text = "WebNative v" + BuildConfig.VERSION_NAME
            textSize = 18f
        })

        // 开源许可（GPL-3.0，点击打开协议）
        container.addView(TextView(this).apply {
            text = getString(R.string.gnu_license)
            setPadding(0, 24, 0, 0)
            setOnClickListener {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.gnu.org/licenses/gpl-3.0.txt"))
                )
            }
        })

        // 开源库列表（aboutlibraries，GPL 合规声明）
        container.addView(TextView(this).apply {
            text = getString(R.string.open_source_libs)
            setPadding(0, 24, 0, 0)
            setOnClickListener {
                startActivity(
                    LibsBuilder()
                        .withEdgeToEdge(true)
                        .withSearchEnabled(true)
                        .intent(this@AboutActivity)
                )
            }
        })

        return container
    }
}
