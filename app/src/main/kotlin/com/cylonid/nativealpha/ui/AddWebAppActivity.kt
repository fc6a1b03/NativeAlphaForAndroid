package com.cylonid.nativealpha.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.util.SystemBars
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.util.AppMaterialTheme
import com.cylonid.nativealpha.util.ThemeUtils
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.UrlUtils
import com.cylonid.nativealpha.util.WebAppDataFetcher
import com.cylonid.nativealpha.util.WebAppIconManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 添加 Web App 向导（两步）：
 *   Step 1: 输入 URL（自动补全 https://，校验格式）
 *   Step 2: 自动识别标题回填显示名称 + 图标选择 + 完成
 *
 * 完成后：保存 WebApp（title 唯一名称）并自动创建桌面快捷方式。
 */
class AddWebAppActivity : AppCompatActivity(), SystemBars.SelfManagedInsets {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)
        // 状态栏/虚拟键跟随主题（切换主题后刷新颜色）
        ThemeUtils.applySystemBarColors(this)
        // 分享深链预填（C-分享）：URL/名称由 MainActivity 经 SiteShareCodec
        // 校验后传入——本页只消费，不再做安全判定
        val prefillUrl = intent.getStringExtra(EXTRA_PREFILL_URL) ?: ""
        val prefillName = intent.getStringExtra(EXTRA_PREFILL_NAME) ?: ""
        val prefillConfig = intent.getStringExtra(EXTRA_PREFILL_CONFIG)
        setContent {
            AppMaterialTheme {
                AddWebAppScreen(
                    onBack = { finish() },
                    initialUrl = prefillUrl,
                    initialName = prefillName,
                    prefillConfigJson = prefillConfig
                )
            }
        }
    }

    companion object {
        /** 分享深链导入的预填 URL（已通过 SiteShareCodec fail-closed 校验） */
        const val EXTRA_PREFILL_URL = "extra_prefill_url"

        /** 分享深链导入的预填名称 */
        const val EXTRA_PREFILL_NAME = "extra_prefill_name"

        /** 分享深链携带的设置差异 JSON（v2 深链；保存前经 copySettings 覆盖设置字段） */
        const val EXTRA_PREFILL_CONFIG = "extra_prefill_config"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddWebAppScreen(
    onBack: () -> Unit,
    initialUrl: String = "",
    initialName: String = "",
    prefillConfigJson: String? = null,
) {
    var step by remember { mutableIntStateOf(1) }
    var urlText by remember { mutableStateOf(initialUrl) }
    var nameText by remember { mutableStateOf(initialName) }
    var urlError by remember { mutableStateOf<String?>(null) }
    var isFetching by remember { mutableStateOf(false) }
    var fetchedTitle by remember { mutableStateOf<String?>(null) }
    var fetchedFavicon by remember { mutableStateOf<Bitmap?>(null) }
    var customIcon by remember { mutableStateOf<Bitmap?>(null) }
    var fetchFailed by remember { mutableStateOf(false) }
    // 保存防抖：坏站 favicon 补拉可达 20s+，期间连点会重复创建条目（重复条目即此 bug 产物）
    var isSaving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    // 预取错误消息（避免在非 Composable 函数中查询资源）
    val msgNoUrl = stringResource(R.string.no_url_entered)
    val msgInvalidUrl = stringResource(R.string.enter_valid_url)
    val msgPickIcon = stringResource(R.string.custom_icon)

    // 相册选图 → 更新 customIcon state（Compose 直接处理，链路完整）
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            val bmp = uri?.let {
                runCatching {
                    // ImageDecoder 替代已废弃的 MediaStore.Images.Media.getBitmap
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, it))
                }.getOrNull()
            }
            if (bmp != null) {
                customIcon = bmp
            } else {
                Toast.makeText(context, R.string.icon_not_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun launchImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
            .setType("image/*")
        imagePicker.launch(Intent.createChooser(intent, msgPickIcon))
    }

    fun normalizeUrl(raw: String): String = UrlUtils.normalize(raw)

    fun validateUrl(raw: String): String? {
        return when (UrlUtils.validate(raw)) {
            "empty" -> msgNoUrl
            "invalid" -> msgInvalidUrl
            else -> null
        }
    }

    fun startFetch(url: String) {
        isFetching = true
        fetchFailed = false
        fetchedTitle = null
        fetchedFavicon = null
        customIcon = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val meta = WebAppDataFetcher.fetch(url)
                val bmp = WebAppDataFetcher.loadBitmap(meta.faviconUrl)
                meta to bmp
            }
            // 协程在 Activity 销毁后回来时不更新 UI
            if (!coroutineContext.isActive) return@launch
            if (result.second == null) {
                // 第一阶段失败（Cloudflare 挑战站等）：保持加载态，后台补拉多源候选
                // （fetchFavicon 纯拉取不持久化——WebApp 未创建，若用临时 WebApp 调
                // loadFavicon 会经 saveIcon 落 webapp_-1 孤儿文件）。
                // 补拉也失败才显示"获取失败"——避免"先闪失败再出图标"的割裂
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        WebAppIconManager.fetchFavicon(url)
                    }
                    if (!coroutineContext.isActive) return@launch
                    if (bmp != null) {
                        fetchedFavicon = bmp
                    }
                    fetchFailed = bmp == null
                    isFetching = false
                }
            } else {
                fetchedFavicon = result.second
                isFetching = false
            }
            // 标题回填：fetch 已结构化识别挑战/安全拦截页（返回 null 标题），
            // 此处仅防空——取不到就不动用户输入（保持 nameText 原值/空）。
            // 分享深链预填的名字同样不覆盖（分享者定的名优先于站点标题）：
            // 仅当名称为空时才回填抓取标题
            val cleanTitle = result.first.title
                ?.let { it.trim() }
                ?.takeIf { it.isNotEmpty() }
            if (cleanTitle != null) {
                fetchedTitle = cleanTitle
                if (nameText.isBlank()) {
                    nameText = cleanTitle
                }
            } else {
                fetchedTitle = null
                // 标题取不到（拦截页/网络失败）：名称框兜底填域名（可改）——
                // 仅在用户未输入时填，不覆盖已输入内容
                if (nameText.isBlank()) {
                    nameText = UrlUtils.displayHost(url)
                }
            }
        }
    }

    fun onStep1Next() {
        val error = validateUrl(urlText)
        if (error != null) {
            urlError = error
            return
        }
        val url = normalizeUrl(urlText)
        urlText = url
        focusManager.clearFocus()
        step = 2
        startFetch(url)
    }

    fun onFinish() {
        if (isSaving) return  // 防抖：保存中忽略重复点击（连点会创建重复条目）
        isSaving = true
        val url = normalizeUrl(urlText)
        val webapp = WebApp(
            url,
            DataManager.getInstance().incrementedID,
            DataManager.getInstance().incrementedOrder
        )
        val appName = nameText.trim()
        if (appName.isNotEmpty()) webapp.title = appName
        webapp.applySettingsForNewWebApp()
        // 分享深链 v2：设置差异覆盖新站默认（copySettings 仅复制设置字段，
        // 统计/快捷键/图标不受染）；显式配置=应用设置为主，否则被全局模板盖掉
        prefillConfigJson?.let { cfg ->
            runCatching {
                com.cylonid.nativealpha.util.SiteShareCodec.decodeConfigDiff(cfg)?.let { shared ->
                    webapp.copySettings(shared)
                    webapp.isOverrideGlobalSettings = true
                }
            }
        }
        // 头像统一源：选中/回填图标持久化到 iconPath（列表/快捷方式统一取用）
        (customIcon ?: fetchedFavicon)?.let { icon ->
            WebAppIconManager.saveIcon(context, webapp, icon)
        }
        DataManager.getInstance().addWebsite(webapp)
        // 图标未就绪（坏站/超时）：不再阻塞等待 favicon 补拉——立即 pin（字母图标）+
        // 返回主界面；favicon 由主界面列表 produceState 后台补拉，拉到后自动更新
        // 桌面快捷方式图标（updateShortcutIcon 同 ID 可刷新）。
        // 旧行为（等待补拉完成才返回）：坏地址 4 候选源 × 4s 超时 = 16s+ 白等，用户以为卡死。
        requestPinShortcut(webapp)
        updateShortcutIcon(context, webapp)
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_webapp)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (step == 1) onBack() else step = 1
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 步骤指示器
            LinearProgressIndicator(
                progress = { if (step == 1) 0.5f else 1f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            )
            Text(
                text = stringResource(if (step == 1) R.string.add_step_1 else R.string.add_step_2),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(bottom = 16.dp)
            )

            Crossfade(targetState = step) { currentStep ->
                when (currentStep) {
                    1 -> Step1Content(
                        urlText = urlText,
                        urlError = urlError,
                        onUrlChange = { urlText = it; urlError = null },
                        onNext = { onStep1Next() }
                    )
                    else -> Step2Content(
                        urlText = urlText,
                        nameText = nameText,
                        onNameChange = { nameText = it },
                        isFetching = isFetching,
                        isSaving = isSaving,
                        fetchFailed = fetchFailed,
                        fetchedFavicon = fetchedFavicon,
                        customIcon = customIcon,
                        onPickImage = { launchImagePicker() },
                        onResetIcon = { customIcon = null },
                        onRetry = { startFetch(normalizeUrl(urlText)) },
                        onFinish = { onFinish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun Step1Content(
    urlText: String,
    urlError: String?,
    onUrlChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = urlText,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.url)) },
            placeholder = { Text("https://example.com") },
            leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
            trailingIcon = {
                if (urlText.isNotEmpty()) {
                    IconButton(onClick = { onUrlChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                    }
                }
            },
            isError = urlError != null,
            supportingText = urlError?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(onNext = { onNext() })
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.add_step1_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        // 下一步按钮（键盘收起时可见；键盘弹出时 imePadding 推至键盘上方，双保险不迷路）
        Button(
            onClick = onNext,
            enabled = urlText.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.next)) }
    }
}
