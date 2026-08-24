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
            if (result.second == null) {
                // 第一阶段失败（Cloudflare 挑战站等）：保持加载态，后台补拉多源候选
                // （fetchFavicon 纯拉取不持久化——WebApp 未创建，若用临时 WebApp 调
                // loadFavicon 会经 saveIcon 落 webapp_-1 孤儿文件）。
                // 补拉也失败才显示"获取失败"——避免"先闪失败再出图标"的割裂
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        com.cylonid.nativealpha.util.WebAppIconManager.fetchFavicon(url)
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
            // 此处仅防空——取不到就不动用户输入（保持 nameText 原值/空）
            val cleanTitle = result.first.title
                ?.let { it.trim() }
                ?.takeIf { it.isNotEmpty() }
            if (cleanTitle != null) {
                fetchedTitle = cleanTitle
                nameText = cleanTitle
            } else {
                fetchedTitle = null
                // 标题取不到（拦截页/网络失败）：名称框兜底填域名（可改）——
                // 仅在用户未输入时填，不覆盖已输入内容
                if (nameText.isBlank()) {
                    nameText = com.cylonid.nativealpha.util.UrlUtils.displayHost(url)
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
        // 并**补拉完成后才 pin 快捷方式**（pin 是一次性冻结——若先 pin 会永久用字母，
        // 列表后有图标但快捷方式没有——正是 linux.do 故障根因）
        if (webapp.iconPath == null) {
            // 补拉 favicon（后台）→ 完成（UI 线程）pin → 返回主界面。
            // pin 延后：quick 方式一次性冻结图标——先 pin 会永久字母（linux.do 根因）
            val contextLocal = context
            scope.launch {
                withContext(Dispatchers.IO) {
                    com.cylonid.nativealpha.util.WebAppIconManager.loadFavicon(contextLocal, webapp)
                }
                // 补拉完成（无论成败）→ 有图标用图标；仍无则字母（已尽力）
                if (coroutineContext.isActive) {
                    requestPinShortcut(webapp)
                    // 图标已就绪：更新到（动态同 ID）快捷方式——桌面图标跟随列表
                    updateShortcutIcon(contextLocal, webapp)
                    onBack()
                }
            }
        } else {
            // 图标已就绪（抓取成功/用户选图）——直接 pin + 更新快捷方式图标 + 返回
            requestPinShortcut(webapp)
            updateShortcutIcon(context, webapp)
            // 返回主界面（onResume 触发刷新）
            onBack()
        }
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
 *  图标与列表同源：resolveIconCached（iconPath→字母渐变，**无网络**——本方法在 UI 线程调用）。
 *  favicon 补拉已在 onFinish 后台完成（成功则 iconPath 已持久化），此处不重复拉网。 */
private fun requestPinShortcut(webapp: WebApp) {
    val context = App.getAppContext()
    val intent = WebViewLauncher.createWebViewIntent(webapp, context) ?: return

    val icon = IconCompat.createWithBitmap(
        com.cylonid.nativealpha.util.WebAppIconManager.resolveIconCached(context, webapp)
    )

    val title = webapp.title
    val safeTitle = if (title.isNullOrBlank()) "Unknown" else title

    val shortId = com.cylonid.nativealpha.util.ShortcutIconUtils.pinnedShortcutId(webapp.ID)  // 稳定 ID（title 会变——快捷方式 id 不变，可后续更新）
    val pinInfo = ShortcutInfoCompat.Builder(context, shortId)
        .setIcon(icon)
        .setShortLabel(safeTitle)
        .setLongLabel(safeTitle)
        .setIntent(intent)
        .build()
    // 1) 注册 Dynamic Shortcut（同 ID）——可随时 updateShortcuts 更新图标（桌面图标跟随列表）
    // 2) requestPinShortcut 弹系统确认（固定到桌面）
    // 注：先 dynamic 后 pin——Launcher 识别同 ID 关联，后续 updateShortcuts 能刷新已 pin 的
    try {
        ShortcutManagerCompat.addDynamicShortcuts(context, listOf(pinInfo))
    } catch (e: Exception) {
        // 动态注册失败不阻塞 pin（部分旧 Launcher 不支持）
    }
    if (ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
        ShortcutManagerCompat.requestPinShortcut(context, pinInfo, null)
    }
}

/** 更新已注册快捷方式的图标（图标就绪时调用——桌面跟随列表图标）。
 *  resolveIconCached 无网络（UI 线程安全）；此时 favicon 补拉已完成，iconPath 要么持久化要么用字母。 */
fun updateShortcutIcon(context: android.content.Context, webapp: WebApp) {
    try {
        val bmp = com.cylonid.nativealpha.util.WebAppIconManager.resolveIconCached(context, webapp)
        val intent = WebViewLauncher.createWebViewIntent(webapp, context) ?: return
        val title = webapp.title
        val safeTitle = if (title.isNullOrBlank()) "Unknown" else title
        val info = ShortcutInfoCompat.Builder(context, com.cylonid.nativealpha.util.ShortcutIconUtils.pinnedShortcutId(webapp.ID))
            .setIcon(IconCompat.createWithBitmap(bmp))
            .setShortLabel(safeTitle)
            .setLongLabel(safeTitle)
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.updateShortcuts(context, listOf(info))
    } catch (e: Exception) {
        // 更新失败静默（下次列表刷新再试）
    }
}
