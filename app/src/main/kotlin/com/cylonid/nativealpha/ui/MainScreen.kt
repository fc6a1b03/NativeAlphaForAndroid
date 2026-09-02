package com.cylonid.nativealpha.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cylonid.nativealpha.R
import com.cylonid.nativealpha.model.WebApp
import com.cylonid.nativealpha.util.SiteHealthRegistry
import com.cylonid.nativealpha.util.ScanResultRouter
import com.cylonid.nativealpha.util.WebAppIconManager
import com.cylonid.nativealpha.util.WebViewLauncher
import com.cylonid.nativealpha.ScanCaptureActivity
import android.graphics.Bitmap

/**
 * WebNative 主界面（Compose）。
 *
 * 现代化设计：大标题 + 副标题、渐变品牌 FAB、圆角阴影卡片、品牌空状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    webApps: List<WebApp>,
    onAddClick: () -> Unit,
    onOpenWebApp: (WebApp) -> Unit,
    onOpenSettings: (WebApp) -> Unit,
    onOpenStats: (WebApp) -> Unit,
    onDeleteWebApp: (WebApp) -> Unit,
    onCopyUrl: (WebApp) -> Unit,
    onGlobalSettingsClick: () -> Unit,
    onMatrixClick: () -> Unit,
) {
    // 搜索过滤：名称/URL 模糊匹配
    var searchQuery by remember { mutableStateOf("") }
    // 卡片菜单「分享」目标（C-分享：主页入口与设置页共用同一对话框组件）
    var shareTarget by remember { mutableStateOf<WebApp?>(null) }
    shareTarget?.let { target ->
        SiteShareDialog(webApp = target, onDismiss = { shareTarget = null })
    }
    // key 必须感知 WebApp 内容变化：WebApp.equals() 只比较 baseUrl/ID，
    // 标题/URL 改了 remember 会误判列表未变，导致卡片不刷新
    val filteredApps = remember(webApps.map { Triple(it.ID, it.title, it.baseUrl) }, searchQuery) {
        if (searchQuery.isBlank()) webApps
        else webApps.filter { app ->
            val name = app.title.lowercase()
            val url = app.baseUrl.lowercase()
            name.contains(searchQuery.lowercase()) || url.contains(searchQuery.lowercase())
        }
    }
    // 背景装饰：顶部柔和品牌光晕（低透明度，不干扰内容，缓解纯白空旷感）
    Box(modifier = Modifier.fillMaxSize()) {
        // 顶部靛蓝光晕
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF4F46E5).copy(alpha = 0.10f),
                            Color.Transparent
                        )
                    )
                )
        )
        // 右上角紫色光斑
        Box(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.TopEnd)
                .offset(x = 80.dp, y = (-80).dp)
                .background(
                    Brush.radialGradient(
                        listOf(
                            Color(0xFF7C3AED).copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
        )

    Scaffold(
        topBar = {
            // 现代化大标题（Large Title，iOS/现代 Android 主流）
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle, webApps.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // 扫码入口（C-扫码：矩阵图标左侧；webnative→添加，http(s)→临时浏览）
                    val context = LocalContext.current
                    val scanInvalidHint = stringResource(R.string.share_invalid_link)
                    val scanLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        when (val action = ScanResultRouter.route(
                            result.data?.getStringExtra(ScanCaptureActivity.EXTRA_SCAN_RESULT)
                        )) {
                            is ScanResultRouter.Action.AddSite ->
                                context.startActivity(
                                    Intent(context, AddWebAppActivity::class.java)
                                        .putExtra(AddWebAppActivity.EXTRA_PREFILL_URL, action.url)
                                        .putExtra(AddWebAppActivity.EXTRA_PREFILL_NAME, action.name)
                                        .putExtra(AddWebAppActivity.EXTRA_PREFILL_CONFIG, action.configJson)
                                )
                            is ScanResultRouter.Action.OpenPage ->
                                WebViewLauncher.startRawUrl(action.url, context)
                            ScanResultRouter.Action.Invalid ->
                                Toast.makeText(context, scanInvalidHint, Toast.LENGTH_LONG).show()
                            ScanResultRouter.Action.Ignore -> Unit
                        }
                    }
                    IconButton(onClick = {
                        context.startActivity(Intent(context, ScanCaptureActivity::class.java))
                    }) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = stringResource(R.string.scan_entry_desc),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 矩阵入口（E1：设置图标左侧；空站点 Toast 拦截不进矩阵）
                    val hasActiveSites = webApps.any { it.isActiveEntry }
                    val emptyHint = stringResource(R.string.matrix_empty_hint)
                    IconButton(onClick = {
                        if (hasActiveSites) {
                            onMatrixClick()
                        } else {
                            Toast.makeText(context, emptyHint, Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(
                            Icons.Default.GridView,
                            contentDescription = stringResource(R.string.matrix_entry_desc),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onGlobalSettingsClick) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.global_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        floatingActionButton = {
            // 品牌渐变 FAB（靛蓝→紫，Material 3 渐变小面积点缀）
            FloatingActionButton(
                onClick = onAddClick,
                modifier = Modifier.testTag("fab_add"),
                containerColor = Color.Transparent,
                shape = RoundedCornerShape(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF4F46E5), Color(0xFF7C3AED))
                            ),
                            RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_webapp),
                        tint = Color.White
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 搜索框（名称/URL 模糊查找；Material 3 统一填充样式）
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        stringResource(R.string.search_webapps),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.clear_search)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .testTag("search_field")
            )
            if (webApps.isEmpty()) {
                EmptyState(
                    modifier = Modifier
                        .fillMaxSize()
                )
            } else if (filteredApps.isEmpty()) {
                // 搜索无结果提示
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_search_result),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        filteredApps,
                        key = { it.ID },
                        contentType = { "webapp" }  // 同类型项共享组合策略，滑动不重建
                    ) { webApp ->
                        WebAppCard(
                            webApp = webApp,
                            onClick = { onOpenWebApp(webApp) },
                            onSettings = { onOpenSettings(webApp) },
                            onStats = { onOpenStats(webApp) },
                            onDelete = { onDeleteWebApp(webApp) },
                            onCopyUrl = { onCopyUrl(webApp) },
                            onShare = { shareTarget = webApp }
                        )
                    }
                }
            }
        }
    }
}
}

@Composable
private fun WebAppCard(
    webApp: WebApp,
    onClick: () -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onDelete: () -> Unit,
    onCopyUrl: () -> Unit,
    onShare: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("webapp_card")
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 统一图标（能力全在 WebAppIconManager，此处只编排）：
            // IO 线程 resolveIcon（iconPath→favicon→字母）；失败 5s 重试一次（以 iconPath 判成功）；
            // title 作 key——改名后字母图标跟随新名称重组
            val iconBitmap = produceState<Bitmap?>(null, webApp.baseUrl, webApp.iconPath, webApp.title) {
                repeat(2) { attempt ->
                    value = withContext(Dispatchers.IO) {
                        WebAppIconManager.resolveIcon(context, webApp)
                    }
                    // 真图标就绪或已重试过 → 同步桌面快捷方式（同 ID 可更新——桌面跟随列表）
                    if (webApp.iconPath != null || attempt == 1) {
                        updateShortcutIcon(context, webApp)
                        return@produceState
                    }
                    delay(5_000L)
                }
            }.value
            // 异步空窗兜底：同一能力（resolveIconCached——iconPath→字母，无网络）
            val finalIcon = iconBitmap ?: remember(webApp.title, webApp.baseUrl) {
                WebAppIconManager.resolveIconCached(context, webApp)
            }
            Image(
                bitmap = finalIcon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = webApp.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = webApp.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 站点健康（presence 语义）：本会话加载失败过才显示——
                // null=未观测、true=健康均不占视觉（诚实且零噪音）
                if (SiteHealthRegistry.statusOf(webApp.ID) == false) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.site_health_recent_failure),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // 更多菜单
            var menuExpanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_webapp_settings)) },
                        onClick = {
                            menuExpanded = false
                            onSettings()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_share)) },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.stats)) },
                        onClick = {
                            menuExpanded = false
                            onStats()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy_url)) },
                        onClick = {
                            menuExpanded = false
                            onCopyUrl()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 品牌图标（渐变圆形容器）
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF4F46E5), Color(0xFF7C3AED))
                    ),
                    RoundedCornerShape(28.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Public,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.White
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.welcome_msg),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.add_webapp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
