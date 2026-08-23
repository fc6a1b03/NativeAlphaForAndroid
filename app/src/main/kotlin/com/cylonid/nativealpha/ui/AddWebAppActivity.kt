package com.cylonid.nativealpha.ui

import android.app.Activity
import android.content.Intent
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.IconCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.util.AppMaterialTheme
import com.cylonid.nativealpha.util.ThemeUtils
import com.cylonid.nativealpha.model.DataManager
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.App
import com.cylonid.nativealpha.util.IconGenerator
import com.cylonid.nativealpha.util.NotificationUtils
import com.cylonid.nativealpha.util.UrlUtils
import com.cylonid.nativealpha.util.WebAppDataFetcher
import com.cylonid.nativealpha.util.WebViewLauncher
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
class AddWebAppActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyUiMode()
        setTheme(ThemeUtils.resolveTheme())
        super.onCreate(savedInstanceState)
        // 状态栏/虚拟键跟随主题（切换主题后刷新颜色）
        ThemeUtils.applySystemBarColors(this)
        setContent {
            AppMaterialTheme {
                AddWebAppScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddWebAppScreen(
    onBack: () -> Unit,
) {
    var step by remember { mutableStateOf(1) }
    var urlText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var isFetching by remember { mutableStateOf(false) }
    var fetchedTitle by remember { mutableStateOf<String?>(null) }
    var fetchedFavicon by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var customIcon by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var fetchFailed by remember { mutableStateOf(false) }
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
            isFetching = false
            if (result.second == null) {
                fetchFailed = true
                // 创建即失败（Cloudflare 挑战站 linux.do 等）：立即后台补拉 favicon（与列表同源，
                // 真机可达——创建时图标直接可用，不依赖"创建完列表才有"的割裂体验）
                val contextLocal = context
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        com.cylonid.nativealpha.util.WebAppIconManager.loadFavicon(
                            contextLocal,
                            com.cylonid.nativealpha.model.WebApp(url, -1)
                        )
                    }
                    if (bmp != null && coroutineContext.isActive) {
                        fetchedFavicon = bmp
                        fetchFailed = false
                    }
                }
            } else {
                fetchedFavicon = result.second
            }
            // 标题回填：仅当抓到真实站点标题（过滤 Cloudflare 挑战页等脏值）才回填，
            // 取不到就不动用户输入（保持 nameText 原值/空，不强行填默认文本）
            val cleanTitle = result.first.title
                ?.let { it.trim() }
                ?.takeIf { it.isNotEmpty() && !WebAppDataFetcher.isChallengeTitle(it) }
            if (cleanTitle != null) {
                fetchedTitle = cleanTitle
                nameText = cleanTitle
            } else {
                fetchedTitle = null
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
        val url = normalizeUrl(urlText)
        val webapp = WebApp(
            url,
            DataManager.getInstance().incrementedID,
            DataManager.getInstance().incrementedOrder
        )
        val appName = nameText.trim()
        if (appName.isNotEmpty()) webapp.title = appName
        webapp.applySettingsForNewWebApp()
        // 头像统一源：选中/回填图标持久化到 iconPath（列表/快捷方式统一取用）
        (customIcon ?: fetchedFavicon)?.let { icon ->
            com.cylonid.nativealpha.util.WebAppIconManager.saveIcon(context, webapp, icon)
        }
        DataManager.getInstance().addWebsite(webapp)
        // 兜底：favicon 直抓失败（如 Cloudflare 挑战站 linux.do）时，
        // 再用列表同款 loadFavicon（Google s2）拉一次——成功则写回 iconPath 持久化，
        // 解决「添加时没图标、列表却有、重启又丢」的场景。
        if (webapp.iconPath == null) {
            val contextLocal = context
            scope.launch {
                withContext(Dispatchers.IO) {
                    com.cylonid.nativealpha.util.WebAppIconManager.loadFavicon(contextLocal, webapp)
                }
            }
        }
        // 自动创建桌面快捷方式：图标与列表同源（resolveIcon——iconPath→favicon→字母），
        // 桌面快捷方式与列表图标保持一致（此前用创建时抓取结果——失败则不同步）
        requestPinShortcut(webapp)
        // 返回主界面（onResume 触发刷新）
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

@Composable
private fun Step2Content(
    urlText: String,
    nameText: String,
    onNameChange: (String) -> Unit,
    isFetching: Boolean,
    fetchFailed: Boolean,
    fetchedFavicon: android.graphics.Bitmap?,
    customIcon: android.graphics.Bitmap?,
    onPickImage: () -> Unit,
    onResetIcon: () -> Unit,
    onRetry: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 名称（自动识别回填，可编辑）
        OutlinedTextField(
            value = nameText,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.display_name_hint)) },
            placeholder = { Text(stringResource(R.string.display_name_auto)) },
            leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onFinish() })
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 图标区域
        Text(
            text = stringResource(R.string.shortcut_icon),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            when {
                isFetching -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.fetching_icon),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                customIcon != null || fetchedFavicon != null -> {
                    val iconBmp = customIcon ?: fetchedFavicon
                    if (iconBmp != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconPreview(bmp = iconBmp)
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(onClick = onResetIcon) {
                                Text(stringResource(R.string.use_dynamic_icon))
                            }
                        }
                    } else {
                        // 两个源都为空（异常分支）：提示用动态图标
                        Text(
                            text = stringResource(R.string.icon_will_be_generated),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                fetchFailed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.fetch_failed_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.retry))
                    }
                }

                else -> Text(
                    text = stringResource(R.string.icon_will_be_generated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 自定义图标入口
        OutlinedButton(
            onClick = onPickImage,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Image, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.custom_icon))
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 完成按钮
        Button(
            onClick = onFinish,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.add_to_home_screen))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = urlText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun IconPreview(bmp: android.graphics.Bitmap) {
    Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = null,
        modifier = Modifier
            .size(96.dp)
            .clip(RoundedCornerShape(20.dp))
    )
}

/** 创建桌面快捷方式（复用 ShortcutDialogFragment 的 pin 逻辑）。
 *  图标与列表同源：resolveIcon（iconPath→favicon→字母渐变）——桌面快捷方式=列表图标。
 *  网络拉取异步：先 pin（临时用当前 iconPath/字母），后台拉 favicon 成功后更新（不阻塞创建）。 */
private fun requestPinShortcut(webapp: WebApp) {
    val context = App.getAppContext()
    val intent = WebViewLauncher.createWebViewIntent(webapp, context) ?: return

    // 图标：优先已持久化 iconPath；无则字母（favicon 后台拉取后 resolveIcon 立即更新）
    val icon = if (webapp.iconPath != null) {
        val bmp = com.cylonid.nativealpha.util.WebAppIconManager.loadIcon(context, webapp)
        if (bmp != null) IconCompat.createWithBitmap(bmp)
        else IconCompat.createWithBitmap(
            com.cylonid.nativealpha.util.WebAppIconManager.resolveIcon(context, webapp)
        )
    } else {
        IconCompat.createWithBitmap(
            com.cylonid.nativealpha.util.WebAppIconManager.resolveIcon(context, webapp)
        )
    }

    val title = webapp.title
    val safeTitle = if (title.isNullOrBlank()) "Unknown" else title

    if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        val pinInfo = ShortcutInfoCompat.Builder(context, safeTitle)
            .setIcon(icon)
            .setShortLabel(safeTitle)
            .setLongLabel(safeTitle)
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, pinInfo, null)
    }
}
