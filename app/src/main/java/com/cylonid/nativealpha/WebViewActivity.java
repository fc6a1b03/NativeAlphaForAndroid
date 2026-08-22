package com.cylonid.nativealpha;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Application;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ComponentCallbacks2;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.HttpAuthHandler;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;
import android.widget.FrameLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ShareCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import android.content.pm.PackageManager;

import com.cylonid.nativealpha.databinding.DialogHttpAuthBinding;
import com.cylonid.nativealpha.helper.IconPopupMenuHelper;
import com.cylonid.nativealpha.model.DataManager;
import com.cylonid.nativealpha.model.ErrorType;
import com.cylonid.nativealpha.model.WebApp;
import com.cylonid.nativealpha.ui.ShortcutMenuOverlayKt;
import com.cylonid.nativealpha.ui.WebViewMenuOverlayKt;
import com.cylonid.nativealpha.util.Const;
import com.cylonid.nativealpha.util.DateUtils;
import com.cylonid.nativealpha.util.EntryPointUtils;
import com.cylonid.nativealpha.util.LocaleUtils;
import com.cylonid.nativealpha.util.NotificationUtils;
import com.cylonid.nativealpha.util.StatsRecorder;
import com.cylonid.nativealpha.util.Utility;
import com.cylonid.nativealpha.util.WebViewLauncher;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.lang.reflect.Field;
import java.util.stream.Stream;

import static com.cylonid.nativealpha.util.Const.CODE_OPEN_FILE;

public class WebViewActivity extends AppCompatActivity {

    //Constants for touchlistener
    private static final int NONE = 0;
    private static final int SWIPE = 1;
    private static final int SINGLE_FINGER = 2;
    private static final int TRESHOLD = 100;

    /**
     * 双击空白判定 JS：检测双击点是否真正落在可见文本字符上。
     * caretRangeFromPoint（浏览器内部命中）+ 文本节点矩形命中验证——
     * 空白处 caretRangeFromPoint 会返回"最近插入位置"，必须加矩形命中
     * 才能区分「点在字符上」vs「点在空白但邻近文本」。
     * 兼容 Taro/WebComponents：自定义组件文字系统可选中，但标准 DOM 探测
     * 命中不到——此时按 text 处理（双击文字不弹菜单，交还系统选中单词）。
     * 返回 'text' 或 'blank'。
     */
    private static final String LONGPRESS_JS =
            "(function(){"
            + "var px=%1$f,py=%2$f;"
            + "var dpr=window.devicePixelRatio||1;"
            + "var innerW=window.innerWidth||document.documentElement.clientWidth;"
            + "var outerW=window.outerWidth||innerW;"
            + "var scale=dpr*outerW/innerW;"
            + "if(!(scale>0)||scale===1){scale=1;}"
            + "var x=px/scale,y=py/scale;"
            + "var e=document.elementFromPoint(x,y);"
            + "if(!e)return 'blank';"
            + "var tag=e.tagName?e.tagName.toLowerCase():'';"
            + "if(tag==='html'||tag==='body')return 'blank';"
            + "var te=e;"
            + "while(te&&te!==document.body){"
            + "var tt=te.tagName?te.tagName.toLowerCase():'';"
            + "if(tt==='img'||tt==='canvas'||tt==='svg'||tt==='video'||tt==='iframe')return 'media';"
            + "te=te.parentElement;"
            + "}"
            + "var range=null;"
            + "if(document.caretRangeFromPoint){range=document.caretRangeFromPoint(x,y);}"
            + "if(range&&range.startContainer){"
            + "var n=range.startContainer;"
            + "if(n.nodeType===3){"
            + "var len=n.length||0;"
            + "if(range.startOffset>0&&range.startOffset<len){"
            + "var full=document.createRange();"
            + "full.selectNodeContents(n);"
            + "var rect=full.getBoundingClientRect();"
            + "if(x>=rect.left&&x<=rect.right&&y>=rect.top&&y<=rect.bottom){"
            + "return 'text';"
            + "}"
            + "}"
            + "}"
            + "}"
            + "return 'blank';})()";

    /**
     * media 长按检测 JS：返回长按点命中的 img/video 的绝对 URL（src/currentSrc），
     * 没命中返回 'null'。供长按图片/视频弹「保存图片/保存视频」菜单用。
     * 与 LONGPRESS_JS 的坐标换算保持一致（devicePixelRatio + 视口缩放）。
     */
    private static final String MEDIA_LONGPRESS_JS =
            "(function(){"
            + "var px=%1$f,py=%2$f;"
            + "var dpr=window.devicePixelRatio||1;"
            + "var innerW=window.innerWidth||document.documentElement.clientWidth;"
            + "var outerW=window.outerWidth||innerW;"
            + "var scale=dpr*outerW/innerW;"
            + "if(!(scale>0)||scale===1){scale=1;}"
            + "var x=px/scale,y=py/scale;"
            + "var e=document.elementFromPoint(x,y);"
            + "if(!e)return 'null';"
            + "var te=e;"
            + "while(te&&te!==document.body){"
            + "var tt=te.tagName?te.tagName.toLowerCase():'';"
            + "if(tt==='img'){var s=te.currentSrc||te.src;"
            + "if(s&&s.indexOf('data:')!==0)return s;"
            + "return 'null';}"
            + "if(tt==='video'){var v=te.currentSrc||te.src;"
            + "if(v&&v.indexOf('data:')!==0)return v;"
            + "return 'null';}"
            + "te=te.parentElement;"
            + "}"
            + "return 'null';})()";

    int webappID = -1;
    int webappTabIndex = 0;
    private WebView wv;
    private ProgressBar progressBar;
    private android.widget.ImageView loadingAnimal;
    private boolean currently_reloading = true;
    private GeolocationPermissions.Callback mGeoPermissionRequestCallback = null;
    private String mGeoPermissionRequestOrigin = null;
    private DownloadManager.Request dl_request = null;
    private Map<String, String> CUSTOM_HEADERS;
    protected ValueCallback<Uri[]> filePathCallback;

    private boolean quitOnNextBackpress = false;
    private Handler reload_handler = null;
    private WebApp webapp = null;
    private String urlOnFirstPageload = "";
    // 错误页重试目标（onReceivedError 主框架失败时记录，webnative://retry 用它重新加载）
    private String retryUrl = "";
    private boolean fallbackToDefaultLongClickBehaviour = false;
    private PopupMenu mPopupMenu = null;
    // 长按动态分流：系统是否已启动文本/链接选择 ActionMode（区分「有内容」vs「空白处」）
    private boolean actionModeActive = false;
    // 当前系统文本选择 ActionMode（空白长按时 finish 取消）
    private android.view.ActionMode currentActionMode = null;
    // 权限审计：记录已发起过系统请求的权限（区分「首次请求」vs「永久拒绝」）
    private final Set<String> requestedPermissions = new HashSet<>();
    // 白屏检测：当前加载最后进度 + 进度推进时间戳（无推进超时判定白屏）
    private int lastProgress = 0;
    private long lastProgressTime = 0L;
    private final Handler blankScreenHandler = new Handler();
    private final Runnable blankScreenCheck = this::handleBlankScreen;
    private boolean pageLoadFinished = false;
    // 统计埋点：页面加载开始时间（onPageStarted 到 onPageFinished 计算耗时）
    private long pageLoadStartTime = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleIntent(getIntent());
    }

    /**
     * 复用实例时（documentLaunchMode=intoExisting 或任务栈复用）：
     * 更新 webappID 并重新加载对应 WebApp——防止打开新应用时仍显示旧错误页。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /** 初始化/重载：按 intent 的 webappID 加载 WebApp（复用实例时先清理旧 WebView） */
    private void handleIntent(Intent intent) {
        webappID = intent.getIntExtra(Const.INTENT_WEBAPPID, -1);
        webappTabIndex = intent.getIntExtra(Const.INTENT_TAB_INDEX, 0);
        EntryPointUtils.entryPointReached(this);
        // 重置错误页重试目标（新应用加载，避免残留旧地址）
        retryUrl = "";
        webapp = DataManager.getInstance().getWebApp(webappID);
        // 登录态隔离：开启隔离的 WebApp 恢复自己的 Cookie 会话（异步，多标签按 tabIndex）
        com.cylonid.nativealpha.util.CookieSessionManager.INSTANCE.restoreSnapshot(this, webappID, webappTabIndex);
        if (webapp == null) {
            // Toast is shown in getWebApp method
            finish();
        } else {
            // 复用实例（onNewIntent）时旧 WebView 还在：先销毁释放，再新建
            if (wv != null) {
                cancelBlankScreenCheck();
                wv.removeAllViews();
                wv.destroy();
                wv = null;
            }
            // 统计埋点：记录打开次数
            StatsRecorder.INSTANCE.recordLaunch(webappID);
            setupWebView();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupWebView() {

        setContentView(R.layout.full_webview);

        if(webapp.isKeepAwake()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        String url = webapp.getBaseUrl();

        progressBar = findViewById(R.id.progressBar);
        loadingAnimal = findViewById(R.id.loadingAnimal);

        wv = findViewById(R.id.webview);

        // 移除 WebView 字段名注入的 UA 尾巴（找不到字段时静默跳过，避免 NPE）
        String fieldName = Stream.of(WebViewActivity.class.getDeclaredFields())
                .filter(f -> f.getType() == WebView.class)
                .findFirst()
                .map(Field::getName)
                .orElse("");
        if (!fieldName.isEmpty()) {
            String uaString = wv.getSettings().getUserAgentString().replace("; " + fieldName, "");
            wv.getSettings().setUserAgentString(uaString);
        }
        if (webapp.isUseCustomUserAgent()) {
            if(webapp.getUserAgent() != null && !webapp.getUserAgent().equals("")) {
                wv.getSettings().setUserAgentString(webapp.getUserAgent().replace("\0", "").replace("\n", "").replace("\r", ""));
            }
        }

        if (webapp.isShowFullscreen()) {
            this.hideSystemBars();
        } else if(DataManager.getInstance().getSettings().getAlwaysShowSoftwareButtons()) {
            this.showSystemBars();
        }

        // 异形屏自适应（targetSdk 35+ 强制 edge-to-edge，setDecorFitsSystemWindows 已失效）：
        // 非全屏模式 WebView 内容避开系统栏（顶部状态栏/挖孔、底部导航栏/手势条），
        // 全屏沉浸模式保持铺满（用户显式选择）。
        // insets 挂根布局而非 WebView：WebView 的 insets 分发可能被父容器消费，
        // 且三键导航/手势条切换时根布局 insets 更可靠。
        if (!webapp.isShowFullscreen()) {
            View root = findViewById(R.id.webviewActivity);
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
                Insets bars = windowInsets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                // 键盘弹出时避开（防御：部分输入法/机型 adjustResize 不生效）
                Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
                int bottom = Math.max(bars.bottom, ime.bottom);
                v.setPadding(0, bars.top, 0, bottom);
                return windowInsets;
            });
        }
        wv.setWebViewClient(new CustomBrowser());
        // ===== 安全加固（WebApp 设置项，默认全开） =====
        // 恶意网站防护：默认关（AGENTS.md 既有设计：用户可添加非 HTTPS 站点，按需开启）
        wv.getSettings().setSafeBrowsingEnabled(webapp.isSafeBrowsing());
        // 禁用文件访问：防止恶意站点读取本地文件
        wv.getSettings().setAllowFileAccess(!webapp.isFileAccessDisabled());
        // 禁用内容提供器访问：防止站点访问系统 content:// 资源
        wv.getSettings().setAllowContentAccess(!webapp.isContentAccessDisabled());
        // 混合内容拦截：HTTPS 页面禁止加载 HTTP 子资源
        wv.getSettings().setMixedContentMode(webapp.isMixedContentBlocked()
                ? WebSettings.MIXED_CONTENT_NEVER_ALLOW
                : WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // JS 弹窗限制：禁止页面自动 window.open（用户手势触发的弹窗仍可用）
        wv.getSettings().setJavaScriptCanOpenWindowsAutomatically(!webapp.isJsPopupsRestricted());
        wv.getSettings().setDomStorageEnabled(true);
        wv.getSettings().setDatabaseEnabled(true);
        wv.getSettings().setBlockNetworkLoads(false);

        // ===== 禁用浏览器自带滚动条（网页内容本身的自定义滚动条不受影响） =====
        wv.setVerticalScrollBarEnabled(false);
        wv.setHorizontalScrollBarEnabled(false);
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // 隐藏滚动条占位（WebView 默认 overlay 模式，但仍显式关闭占位）
        wv.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);

        // ===== PWA 高频文本流渲染优化（流式输出/长文档滚动场景） =====
        // 渲染优先级拉满（文本流/长文档滚动核心）
        wv.getSettings().setRenderPriority(WebSettings.RenderPriority.HIGH);
        // 硬件加速强制（避免软件层合成拖慢流式更新）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            wv.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        // 文字缩放（用户可调：50~200%，默认 100）
        wv.getSettings().setTextZoom(webapp.getTextZoom());
        // 页面缩放（用户可调：50~200%，默认 100）：onPageFinished 里 zoomBy 应用
        // 预栅格化：减少滚动时白块/抖动（流式长文本滚动流畅）
        if (WebViewFeature.isFeatureSupported(WebViewFeature.OFF_SCREEN_PRERASTER)) {
            WebSettingsCompat.setOffscreenPreRaster(wv.getSettings(), true);
        }
        // 缓存策略：默认模式，流式页面不强制离线/不缓存
        wv.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        // 布局算法：NORMAL 对文本流最稳（SINGLE_COLUMN 会触发整页重排，流式更新开销大）
        wv.getSettings().setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NORMAL);
        // 编码：UTF-8 显式声明（中文流式文本解析正确，避免编码重排）
        wv.getSettings().setDefaultTextEncodingName("UTF-8");
        // 关闭边缘高亮减少合成开销
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // 滚动条优化（长文本流式滚动）
        wv.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
        wv.setScrollbarFadingEnabled(true);
        // ===== PWA 渲染优化结束 =====

        this.setDarkModeIfNeeded();

        wv.getSettings().setJavaScriptEnabled(webapp.isAllowJs());

        CookieManager.getInstance().setAcceptCookie(webapp.isAllowCookies());
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, webapp.isAllowThirdPartyCookies());

        if (webapp.isBlockImages())
            wv.getSettings().setBlockNetworkImage(true);

        if (webapp.isRequestDesktop()) {
            wv.getSettings().setUserAgentString(Const.DESKTOP_USER_AGENT);
            wv.getSettings().setUseWideViewPort(true);
            wv.getSettings().setLoadWithOverviewMode(true);

            wv.getSettings().setSupportZoom(true);
            wv.getSettings().setBuiltInZoomControls(true);
            wv.getSettings().setDisplayZoomControls(false);

            wv.setScrollBarStyle(WebView.SCROLLBARS_OUTSIDE_OVERLAY);
            wv.setScrollbarFadingEnabled(false);

        }
        if(webapp.isEnableZooming()) {
            wv.getSettings().setSupportZoom(true);
            wv.getSettings().setBuiltInZoomControls(true);
        }

        CUSTOM_HEADERS = initCustomHeaders(webapp.isSendSavedataRequest());
        loadURL(wv, url);
        wv.setWebChromeClient(new CustomWebChromeClient());
        wv.setDownloadListener((dl_url, userAgent, contentDisposition, mimeType, contentLength) -> {

            if (mimeType.equals("application/pdf")) {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(dl_url));
                startActivity(i);
            } else {
                if(dl_url != null && !dl_url.equals("")) {
                    if(dl_url.startsWith("blob:")) {
                        dl_url = dl_url.replace("blob:", "");
                        try {
                            dl_url = URLDecoder.decode(dl_url, "UTF-8");
                        } catch (UnsupportedEncodingException e) {
                            e.printStackTrace();
                        }
                    }
                    DownloadManager.Request request = null;
                    try {
                        request = new DownloadManager.Request(
                                Uri.parse(dl_url));
                    }
                    catch(Exception e) {
                        NotificationUtils.showInfoSnackbar(this, getString(R.string.file_download), Snackbar.LENGTH_SHORT);
                    }
                  String file_name = Utility.getFileNameFromDownload(dl_url, contentDisposition, mimeType);

                  request.setMimeType(mimeType);
                  request.addRequestHeader("cookie", CookieManager.getInstance().getCookie(dl_url));
                  request.addRequestHeader("User-Agent", userAgent);
                  request.setTitle(file_name);
                  request.allowScanningByMediaScanner();
                  request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                  request.setDestinationInExternalPublicDir(
                          Environment.DIRECTORY_DOWNLOADS, file_name);

                  DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);

                  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                      String[] perms = {Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};
                      boolean allGranted = true;
                      for (String perm : perms) {
                          if (ContextCompat.checkSelfPermission(WebViewActivity.this, perm) != PackageManager.PERMISSION_GRANTED) {
                              allGranted = false;
                              break;
                          }
                      }
                      if (!allGranted) {
                          dl_request = request;
                          ActivityCompat.requestPermissions(WebViewActivity.this, perms, Const.PERMISSION_RC_STORAGE);
                      } else {
                          if (dm != null) {
                              dm.enqueue(request);
                              NotificationUtils.showInfoSnackbar(this, getString(R.string.file_download), Snackbar.LENGTH_SHORT);
                          }
                      }
                  }
                  //No storage permission needed for Android 10+
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                      if (dm != null) {
                          dm.enqueue(request);
                          NotificationUtils.showInfoSnackbar(this, getString(R.string.file_download), Snackbar.LENGTH_SHORT);
                      }
                  }
                }

            }
        });
        wv.setOnTouchListener(new View.OnTouchListener() {
            private int mode = NONE;
            private float startX;
            private float stopX;
            private float startY;
            private float stopY;
            // 双击检测：双击空白 → 弹小菜单。
            // 设计：长按完全交还系统（文字选中 100% 正常，不再与系统 ActionMode
            // 竞争——此前空白长按判定在真实站点频繁误判，是历史 bug 根因）。
            // 双击是纯手势识别（300ms 内同点二次按下），系统在空白处无默认行为。
            // 自实现不用 GestureDetector：其内部状态机对注入事件/快速连点
            // 识别不稳定，自实现时间戳+坐标判定简单可靠。
            private long lastDownTime = 0;
            private float lastDownX = -1f;
            private float lastDownY = -1f;

            /** 双击空白判定：JS 检测双击点是否落在文本字符上，空白则弹小菜单 */
            private void checkBlankAndShowMenu(float px, float py) {
                if (isFinishing() || wv == null) return;
                final String js = String.format(Locale.US, LONGPRESS_JS, px, py);
                final String mediaJs = String.format(Locale.US, MEDIA_LONGPRESS_JS, px, py);
                wv.evaluateJavascript(js, value -> {
                    String type = value != null ? value.replace("\"", "") : "blank";
                    if ("null".equals(type) || type.isEmpty()) type = "blank";
                    android.util.Log.d("LongPress", "doubleTap jsType=" + type);
                    final boolean isBlank = "blank".equals(type);
                    if (isBlank && !isFinishing() && wv != null) {
                        // 空白检测通过，再探测是否命中 media（双击图片时弹保存菜单而非小菜单）
                        wv.evaluateJavascript(mediaJs, mediaValue -> {
                            String mediaUrl = mediaValue != null ? mediaValue.replace("\"", "") : "null";
                            android.util.Log.d("LongPress", "doubleTap mediaUrl=" + mediaUrl);
                            if ("null".equals(mediaUrl) || mediaUrl.isEmpty() || !isBlank) {
                                // 非 media（空白/文本）→ 走原有小菜单
                                runOnUiThread(() -> {
                                    if (wv != null) {
                                        // 双击弹菜单时收起输入法：blur 失焦（键盘必收且小菜单
                                        // 关闭后不弹回）+ hideSoftInput 兜底
                                        wv.evaluateJavascript("window.getSelection().removeAllRanges();", null);
                                        // 输入框失焦：键盘必然收起且不再弹
                                        wv.evaluateJavascript(
                                                "var el=document.activeElement;"
                                                + "if(el&&(el.tagName==='INPUT'||el.tagName==='TEXTAREA'||el.isContentEditable)){el.blur();}",
                                                null);
                                    }
                                    // 收起输入法（兜底，blur 已处理主要路径）
                                    hideSoftKeyboard();
                                    showWebViewMenuSheet();
                                });
                            } else {
                                // 命中图片/视频 → 弹「保存图片/保存视频」菜单
                                final String url = mediaUrl;
                                runOnUiThread(() -> showMediaSaveMenu(url));
                            }
                        });
                    }
                });
            }

            /** 长按/双击命中图片或视频：弹统一保存菜单（保存图片/保存视频） */
            private void showMediaSaveMenu(String mediaUrl) {
                if (isFinishing() || wv == null || mediaUrl == null) return;
                View center = findViewById(R.id.anchorCenterScreen);
                PopupMenu popup = IconPopupMenuHelper.getMenu(center, R.menu.wv_media_menu, WebViewActivity.this);
                // 命中类型判断：video 标签 → 保存视频；否则保存图片
                boolean isVideo = mediaUrl.contains(".mp4") || mediaUrl.contains(".webm")
                        || mediaUrl.contains(".m3u8") || mediaUrl.contains(".ogg")
                        || mediaUrl.contains(".mov") || mediaUrl.contains(".mkv");
                popup.getMenu().findItem(R.id.cmMediaSaveImage).setVisible(!isVideo);
                popup.getMenu().findItem(R.id.cmMediaSaveVideo).setVisible(isVideo);
                popup.setOnMenuItemClickListener(menuItem -> {
                    int id = menuItem.getItemId();
                    if (id == R.id.cmMediaSaveImage || id == R.id.cmMediaSaveVideo) {
                        downloadMedia(mediaUrl);
                        return true;
                    }
                    return false;
                });
                popup.show();
            }

            /** 保存图片/视频：复用 DownloadManager（与全站下载一致的统一处理） */
            private void downloadMedia(String url) {
                if (url == null || url.isEmpty()) {
                    NotificationUtils.showToast(WebViewActivity.this,
                            getString(R.string.file_download), Toast.LENGTH_SHORT);
                    return;
                }
                String dl_url = url;
                if (dl_url.startsWith("blob:")) {
                    dl_url = dl_url.replace("blob:", "");
                    try {
                        dl_url = URLDecoder.decode(dl_url, "UTF-8");
                    } catch (UnsupportedEncodingException ignored) {
                    }
                }
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(dl_url));
                    if (dl_url.endsWith(".mp4") || dl_url.endsWith(".webm") || dl_url.endsWith(".mov")
                            || dl_url.endsWith(".ogg") || dl_url.endsWith(".mkv") || dl_url.endsWith(".m3u8")) {
                        request.setMimeType("video/*");
                    } else {
                        request.setMimeType("image/*");
                    }
                    String file_name = Utility.getFileNameFromDownload(dl_url, null, null);
                    if (file_name == null || file_name.isEmpty()) {
                        file_name = "media_" + System.currentTimeMillis();
                        if (dl_url.endsWith(".mp4") || dl_url.endsWith(".webm") || dl_url.endsWith(".mov")
                                || dl_url.endsWith(".ogg") || dl_url.endsWith(".mkv") || dl_url.endsWith(".m3u8")) {
                            file_name += ".mp4";
                        } else {
                            file_name += ".png";
                        }
                    }
                    request.setTitle(file_name);
                    request.allowScanningByMediaScanner();
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file_name);
                    DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(request);
                        NotificationUtils.showInfoSnackbar(WebViewActivity.this, getString(R.string.file_download), Snackbar.LENGTH_SHORT);
                    }
                } catch (Exception e) {
                    NotificationUtils.showToast(WebViewActivity.this,
                            getString(R.string.file_download), Toast.LENGTH_SHORT);
                }
            }

            /** 收起软键盘（双击空白弹小菜单时调用，避免输入法和小菜单打架） */
            private void hideSoftKeyboard() {
                try {
                    android.view.inputmethod.InputMethodManager imm =
                            (android.view.inputmethod.InputMethodManager)
                                    getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(wv.getWindowToken(), 0);
                    }
                } catch (Exception ignored) {
                    // 收起失败不影响小菜单弹出
                }
            }

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                WebApp webapp = DataManager.getInstance().getWebApp(webappID);
                if (webapp == null || webapp.isRequestDesktop())
                    return false;
                android.util.Log.d("LongPress", "touch ev=" + event.getAction() + " x=" + (int) event.getX() + " y=" + (int) event.getY());

                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_DOWN:
                        // 双击检测：250ms 内同点（±40px）再次按下 → 双击 → 弹小菜单。
                        // 250ms/40px 是快速双击窗口：慢速点击/滚动不会误判
                        // （300ms/50px 偏宽松，滚动+连点曾误触——实测收紧）
                        final long now = System.currentTimeMillis();
                        final float x = event.getX(0);
                        final float y = event.getY(0);
                        if (now - lastDownTime < 250
                                && Math.abs(x - lastDownX) < 40
                                && Math.abs(y - lastDownY) < 40) {
                            lastDownTime = 0; // 重置防三连击
                            // 双击：弹小菜单（输入框双击也走菜单——交互一致性）
                            checkBlankAndShowMenu(x, y);
                        } else {
                            lastDownTime = now;
                            lastDownX = x;
                            lastDownY = y;
                            // 单击：完全交还 WebView（键盘自然弹，无任何拦截——
                            // 拦截/恢复机制是实机「输入法反复弹收」的根因，已删除）
                        }
                        // 单指按下：记录起始坐标（供滑动阈值判断）
                        // stopX/stopY 同时初始化（防 POINTER_UP 用旧值/0 误判）
                        startX = x;
                        startY = y;
                        stopX = x;
                        stopY = y;
                        // 单指手势：仅单指（未进入多指）时启用
                        mode = SINGLE_FINGER;
                        return false;

                    case MotionEvent.ACTION_POINTER_DOWN:
                        // This happens when you touch the screen with two fingers
                        mode = SWIPE;
                        // 多指手势（捏合/双指滚动）：不是双击，清除双击检测状态
                        lastDownTime = 0;
                        // You can also use event.getY(1) or the average of the two
                        startX = event.getX(0);
                        startY = event.getY(0);
                        return true;

                    case MotionEvent.ACTION_POINTER_UP:
                        // This happens when you release the second finger
                        mode = NONE;
                        // release 前指针数（POINTER_UP 时 getPointerCount 已减 1）
                        int prevCount = event.getPointerCount() + 1;
                        if (Math.abs(startX - stopX) > TRESHOLD) {
                            if (startX > stopX) {
                                if (prevCount == 3 && DataManager.getInstance().getSettings().isThreeFingerMultitouch()) {
                                    WebViewLauncher.startWebView(DataManager.getInstance().getPredecessor(webappID), WebViewActivity.this);
                                    finish();
                                } else if (DataManager.getInstance().getSettings().isTwoFingerMultitouch()) {
                                    if (wv.canGoForward())
                                        wv.goForward();
                                }
                            } else {
                                if (prevCount == 3 && DataManager.getInstance().getSettings().isThreeFingerMultitouch()) {
                                    WebViewLauncher.startWebView(DataManager.getInstance().getSuccessor(webappID), WebViewActivity.this);
                                    finish();
                                } else if (DataManager.getInstance().getSettings().isTwoFingerMultitouch())
                                    onBackPressed();

                            }
                            return true;
                        }
                        if (DataManager.getInstance().getSettings().isMultitouchReload() && Math.abs(startY - stopY) > TRESHOLD) {
                            if (stopY > startY) {
                                currently_reloading = true;
                                wv.reload();
                            }
                            return true;
                        }
                    case MotionEvent.ACTION_UP:
                        // 抬起：重置滑动状态。
                        // 单指左右滑手势（竖屏单手控制前进/后退）：
                        // - 右滑（stopX > startX）= 后退（回上一个页面，与返回一致）
                        // - 左滑（stopX < startX）= 前进（有历史才执行）
                        // 阈值 100px 是竖屏单手安全距离（TRESHOLD），
                        // 要求水平位移 > 垂直位移（避免滚动页面误触）。
                        if (mode == SINGLE_FINGER && event.getPointerCount() == 1) {
                            float dx = stopX - startX;
                            float dy = Math.abs(stopY - startY);
                            if (Math.abs(dx) > TRESHOLD && Math.abs(dx) > dy * 1.2f) {
                                if (dx > 0) {
                                    // 右滑后退（与系统返回一致），先处理 WebView 历史再退出
                                    onBackPressed();
                                } else if (dx < 0) {
                                    if (wv.canGoForward())
                                        wv.goForward();
                                }
                                lastDownTime = 0; // 滑动后清除双击状态
                                return true;
                            }
                        }
                        mode = NONE;
                        return false;

                    case MotionEvent.ACTION_MOVE:
                        // 无论单指/多指都记录当前坐标（stopX/stopY 保持最新，POINTER_UP/UP 用最新值）
                        // 单指非滑动（mode==单指）也更新：防 POINTER_UP 用旧值误判
                        stopX = event.getX(0);
                        stopY = event.getY(0);
                        // 移动超阈值（滑动/拖动滚动）：不是双击，清除双击检测状态
                        if (Math.abs(stopX - startX) > TRESHOLD
                                || Math.abs(stopY - startY) > TRESHOLD) {
                            lastDownTime = 0;
                        }
                        return false;

                    case MotionEvent.ACTION_SCROLL:
                        // 滚轮滚动：不是双击，清除双击检测状态（防止误判弹菜单）
                        lastDownTime = 0;
                        return false;
                }
                return false;
            }
        });
    }

    @SuppressLint("RequiresFeature")
    private void setDarkModeIfNeeded() {
        if (webapp == null || wv == null) {
            return;
        }
        boolean needsForcedDarkMode = webapp.isUseTimespanDarkMode() &&
                DateUtils.isInInterval(DateUtils.convertStringToCalendar(webapp.getTimespanDarkModeBegin()), Calendar.getInstance(), DateUtils.convertStringToCalendar(webapp.getTimespanDarkModeEnd()))
                || (!webapp.isUseTimespanDarkMode() && webapp.isForceDarkMode());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            boolean isForceDarkSupported = WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK);
            boolean isForceDarkStrategySupported = WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY);
            boolean isAlgorithmicDarkeningSupported = WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING);

            if (needsForcedDarkMode) {
                wv.setBackgroundColor(Color.BLACK);
                wv.setForceDarkAllowed(true);
                getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                if (isForceDarkSupported) {
                    WebSettingsCompat.setForceDark(wv.getSettings(), WebSettingsCompat.FORCE_DARK_ON);
                }
                if (isForceDarkStrategySupported) {
                    WebSettingsCompat.setForceDarkStrategy(wv.getSettings(), WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING);
                }
                if (isAlgorithmicDarkeningSupported) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.getSettings(), true);
                }
            } else {
                getDelegate().setLocalNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                // 加载页背景跟随应用主题（不固定白底）：深色主题下避免加载白屏闪瞎
                // 读当前主题 colorBackground（浅色 #FBF8FF / 深色 #131318）
                int themeBg = Color.WHITE;
                try {
                    TypedValue tv = new TypedValue();
                    if (getTheme().resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                        themeBg = tv.data;
                    }
                } catch (Exception ignored) {
                }
                wv.setBackgroundColor(themeBg);

                if (isForceDarkSupported) {
                    WebSettingsCompat.setForceDark(wv.getSettings(), WebSettingsCompat.FORCE_DARK_OFF);
                }
                if (isForceDarkStrategySupported) {
                    WebSettingsCompat.setForceDarkStrategy(wv.getSettings(), WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY);
                }
                if (isAlgorithmicDarkeningSupported) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.getSettings(), false);
                }
            }
        }

    }

    /** 启动加载页动物走路动画（ImageView + AnimationDrawable） */
    private void startLoadingAnimal() {
        try {
            if (loadingAnimal == null) return;
            if (loadingAnimal.getVisibility() != View.VISIBLE) {
                loadingAnimal.setVisibility(View.VISIBLE);
            }
            android.graphics.drawable.AnimationDrawable anim =
                    (android.graphics.drawable.AnimationDrawable) loadingAnimal.getDrawable();
            if (anim != null && !anim.isRunning()) {
                anim.start();
            }
        } catch (Exception ignored) {
            // 动画启动失败不影响主功能
        }
    }

    /** 停止并隐藏加载页动物动画 */
    private void stopLoadingAnimal() {
        try {
            if (loadingAnimal == null) return;
            android.graphics.drawable.AnimationDrawable anim =
                    (android.graphics.drawable.AnimationDrawable) loadingAnimal.getDrawable();
            if (anim != null && anim.isRunning()) {
                anim.stop();
            }
            loadingAnimal.setVisibility(View.GONE);
        } catch (Exception ignored) {
        }
    }

    /** 显示 Compose 底部菜单（当前页叠加，WebView 保留在后面；滑杆实时预览，关闭即保存） */
    private void showWebViewMenuSheet() {
        String currentUrl = wv.getUrl() != null ? wv.getUrl() : "";
        // 初始化页面缩放待保存值（防只调字体时把已保存的 pageZoom 覆盖成 100）
        mMenuPageZoom = webapp.getPageZoom();
        WebViewMenuOverlayKt.showWebViewMenuOverlay(
                this,
                currentUrl,
                wv.canGoBack(),
                wv.canGoForward(),
                webapp.getTextZoom(),
                webapp.getPageZoom(),
                action -> { handleMenuAction(action); return kotlin.Unit.INSTANCE; },
                zoom -> {
                    // 实时预览字体缩放
                    if (wv != null) wv.getSettings().setTextZoom(zoom.intValue());
                    return kotlin.Unit.INSTANCE;
                },
                zoom -> {
                    // 实时预览页面缩放 + 记录待保存值（zoomBy 模拟捏合）
                    mMenuPageZoom = zoom.intValue();
                    if (wv != null) {
                        webapp.setPageZoom(zoom.intValue());
                        applyPageZoom();
                    }
                    return kotlin.Unit.INSTANCE;
                },
                () -> { saveZoomSettings(); return kotlin.Unit.INSTANCE; }
        );
    }

    /** 菜单中页面缩放预览值（保存时写回 webapp） */
    private int mMenuPageZoom = 100;

    /** 保存字体/缩放设置到 WebApp 原对象（菜单关闭时触发），不污染合并对象 */
    private void saveZoomSettings() {
        if (wv == null || webapp == null) return;
        WebApp original = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true);
        if (original == null) return;
        original.setTextZoom(wv.getSettings().getTextZoom());
        original.setPageZoom(mMenuPageZoom);
        DataManager.getInstance().replaceWebApp(original);
    }

    /**
     * 页面缩放：setInitialScale（内容缩放，不改 viewport 布局模式）。
     * 必须在页面加载完成后调用才稳定（加载前设置对移动自适应页面无效）。
     * 不用 zoomBy：模拟捏合会触发缩放状态机，破坏 viewport 导致页面空白/布局错乱。
     */
    private void applyPageZoom() {
        if (wv == null || webapp == null) return;
        int zoom = webapp.getPageZoom();
        wv.setInitialScale(zoom);
    }

    /** 菜单动作处理 */
    private void handleMenuAction(String action) {        switch (action) {
            case "back": onBackPressed(); break;
            case "forward": if (wv != null && wv.canGoForward()) wv.goForward(); break;
            case "reload": if (wv != null) wv.reload(); break;
            case "copy":
                if (wv != null && wv.getUrl() != null) {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    clipboard.setPrimaryClip(ClipData.newPlainText("URL", wv.getUrl()));
                }
                break;
            case "share":
                if (wv != null && wv.getUrl() != null) {
                    new ShareCompat.IntentBuilder(WebViewActivity.this)
                            .setType("text/plain")
                            .setChooserTitle("Share URL")
                            .setText(wv.getUrl())
                            .startChooser();
                }
                break;
            case "home":
                Intent intent = new Intent(this, MainActivity.class);
                startActivity(intent);
                break;
            case "close": finishAndRemoveTask(); break;
            case "new_tab":
                // 新增会话：sessionTabCount+1，跳到新标签（销毁当前，重建）
                addNewSessionTab();
                break;
            case "switch_tab":
                // 切换会话：弹标签选择（单实例，选后销毁重建）
                showSessionSwitchDialog();
                break;
            case "delete_tab":
                // 删除会话：会话数-1，重建回第一个标签
                deleteCurrentSessionTab();
                break;
            case "shortcuts":
                // 组合快捷键面板（录制/发送，页面独有快捷键）
                showShortcutMenuSheet();
                break;
            case "settings":
                // 跳转 WebApp 设置页（小菜单直达管理，与快捷键面板「管理」一致）
                Intent settingsIntent = new Intent(this, WebAppSettingsActivity.class);
                settingsIntent.putExtra(Const.INTENT_WEBAPPID, webappID);
                startActivity(settingsIntent);
                break;
        }
    }

    /** 新增会话：sessionTabCount+1（隔离模式下），保存当前快照后销毁重建到新标签 */
    private void addNewSessionTab() {
        if (webapp == null) return;
        WebApp original = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true);
        if (original == null) return;
        // 会话数+1（上限10，防内存）
        if (original.getSessionTabCount() < 10) {
            original.setSessionTabCount(original.getSessionTabCount() + 1);
            DataManager.getInstance().replaceWebApp(original);
        }
        int newTab = original.getSessionTabCount() - 1;
        // 保存当前快照（异步）→ CLEAR_TOP 复用实例重载到新标签（不销毁，单实例）
        com.cylonid.nativealpha.util.CookieSessionManager.INSTANCE.saveSnapshot(this, webappID, webappTabIndex);
        WebViewLauncher.startWebViewById(webappID, newTab, this);
    }

    /** 切换会话：弹对话框列出所有会话标签，选一个销毁重建 */
    private void showSessionSwitchDialog() {
        if (webapp == null) return;
        int count = Math.max(1, webapp.getSessionTabCount());
        // 二级会话菜单（简约）：列表切换 + "新增"；多会话才显示"删除"
        String[] items = new String[count];
        for (int i = 0; i < count; i++) {
            items[i] = "会话 " + (i + 1) + (i == webappTabIndex ? "（当前）" : "");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.menu_session))
                .setMessage(getString(R.string.session_isolated_hint))
                .setItems(items, (dialog, which) -> {
                    if (which != webappTabIndex) {
                        com.cylonid.nativealpha.util.CookieSessionManager.INSTANCE.saveSnapshot(this, webappID, webappTabIndex);
                        WebViewLauncher.startWebViewById(webappID, which, this);
                    }
                })
                .setPositiveButton(R.string.session_add, (d, w) -> addNewSessionTab());
        // 单会话不显示删除（至少保留一个）
        if (count > 1) {
            b.setNegativeButton(R.string.delete, (d, w) -> deleteCurrentSessionTab());
        }
        b.show();
    }

    /** 删除会话：会话数-1，销毁重建到第一个会话（保留目标快照） */
    private void deleteCurrentSessionTab() {
        if (webapp == null) return;
        WebApp original = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true);
        if (original == null) return;
        int count = Math.max(1, original.getSessionTabCount());
        if (count == 1) {
            // 单会话：不删除（至少保留一个），提示
            Toast.makeText(this, getString(R.string.session_at_least_one), Toast.LENGTH_SHORT).show();
            return;        }
        original.setSessionTabCount(count - 1);
        DataManager.getInstance().replaceWebApp(original);
        // 保存快照 → CLEAR_TOP 复用重载到第一个会话（不销毁）
        com.cylonid.nativealpha.util.CookieSessionManager.INSTANCE.saveSnapshot(this, webappID, webappTabIndex);
        WebViewLauncher.startWebViewById(webappID, 0, this);
    }

    /** 显示组合快捷键面板（ModalBottomSheet，纯发送；管理在设置页） */
    private void showShortcutMenuSheet() {        ShortcutMenuOverlayKt.showShortcutMenuOverlay(
                this,
                webappID,
                shortcut -> {
                    // 发送组合键到当前页面（JS 合成 KeyboardEvent）
                    sendShortcutToPage(shortcut);
                    return kotlin.Unit.INSTANCE;
                }
        );
    }

    /** 保存快捷键到 WebApp 原对象 */
    private void saveShortcutSettings() {
        WebApp original = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true);
        if (original == null) return;
        DataManager.getInstance().replaceWebApp(original);
    }

    /**
     * 统计缓存占用（异步，不阻塞主线程）：
     * - HTTP 缓存：cacheDir 递归求和（WebView 缓存目录，含 app_webview）
     * - 站点存储：WebStorage.getUsageForOrigin（localStorage/IndexedDB 等，回调异步补写）
     * 调用点：页面加载完成（onPageFinished）后，WebView 缓存已就绪。
     */
    private void recordCacheUsage() {
        if (wv == null) return;
        try {
            // HTTP 缓存：cacheDir 递归求和（IO 操作，放 StatsRecorder 线程避免主线程卡顿）
            // 不依赖 getOrigins 回调：HTTP 缓存立即统计，站点存储回调补写（两者独立）
            StatsRecorder.INSTANCE.record(() -> {
                try {
                    long httpBytes = dirSize(getCacheDir());
                    updateStatsCache(httpBytes, -1L); // -1 表示站点存储待补
                } catch (Exception e) {
                    // 缓存统计失败静默（不影响主功能）
                }
            });
            // 站点存储：异步查询（WebStorage 回调），回调后单独补写
            WebStorage.getInstance().getOrigins(originsMap -> {
                long storeBytes = 0L;
                if (originsMap != null) {
                    // getOrigins 回调为原始 Map：values 需强转 WebStorage.Origin
                    for (Object o : originsMap.values()) {
                        if (o instanceof WebStorage.Origin) {
                            WebStorage.Origin origin = (WebStorage.Origin) o;
                            storeBytes += origin.getQuota() > 0 ? origin.getUsage() : 0L;
                        }
                    }
                }
                final long finalStoreBytes = storeBytes;
                StatsRecorder.INSTANCE.record(() -> {
                    updateStatsCache(-1L, finalStoreBytes); // -1 表示 HTTP 缓存已统计
                });
            });
        } catch (Exception e) {
            // 缓存统计失败静默（不影响主功能）
        }
    }

    /** 递归计算目录大小（字节） */
    private long dirSize(java.io.File dir) {
        long size = 0L;
        try {
            java.io.File[] files = dir.listFiles();
            if (files != null) {
                for (java.io.File f : files) {
                    if (f.isDirectory()) {
                        size += dirSize(f);
                    } else {
                        size += f.length();
                    }
                }
            }
        } catch (Exception ignored) {
            // 目录不可读/损坏：跳过（统计尽力而为）
        }
        return size;
    }

    /** 更新 WebApp 缓存统计字段（原对象，防合并副本覆盖；-1 表示该值待补/已统计，跳过） */
    private void updateStatsCache(long httpBytes, long storeBytes) {
        WebApp original = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true);
        if (original == null) return;
        if (httpBytes >= 0) original.setStatCacheHttpBytes(httpBytes);
        if (storeBytes >= 0) original.setStatCacheStoreBytes(storeBytes);
        DataManager.getInstance().replaceWebApp(original);
    }

    /**
     * 发送组合键到当前页面：JS 合成 KeyboardEvent（keydown + keyup）。
     * 合成事件天然不触发浏览器默认行为，只被页面监听器收到（页面独有快捷键）。
     */
    /**
     * 发送组合键到当前页面。
     *
     * 优先：注入真实 KeyEvent（wv.dispatchKeyEvent）——WebView 将其转为
     * `isTrusted=true` 的 DOM 事件，严格校验可信度的页面（kimi code 等）也能收到。
     * 兜底：JS 合成 KeyboardEvent（isTrusted=false，部分页面忽略）。
     */
    private void sendShortcutToPage(String shortcut) {
        if (wv == null || shortcut == null || shortcut.isEmpty()) return;
        // 统计：记录发送次数（面板/统计页反馈）
        StatsRecorder.INSTANCE.recordShortcutSent(webappID, shortcut);
        // 解析组合键 → keyCode + metaState
        boolean ctrl = false, shift = false, alt = false;
        String key = "";
        for (String part : shortcut.split("\\+")) {
            String p = part.trim();
            switch (p) {
                case "Ctrl": ctrl = true; break;
                case "Shift": shift = true; break;
                case "Alt": alt = true; break;
                default: key = p; break;
            }
        }
        if (key.isEmpty()) return;
        int keyCode = keyCodeOf(key);
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) return;
        int metaState = (ctrl ? KeyEvent.META_CTRL_ON : 0)
                | (shift ? KeyEvent.META_SHIFT_ON : 0)
                | (alt ? KeyEvent.META_ALT_ON : 0);

        // 方案一：JS 合成 KeyboardEvent（主方案——kimi code 源码确认不校验 isTrusted）
        // 带 code 字段（CodeMirror 类编辑器按 e.code 匹配）+ 聚焦输入框（target 正确）
        injectJsWithFocus(ctrl, shift, alt, key);
        // 方案二：注入真实 KeyEvent（补充——对校验 isTrusted 的站点生效）
        // 保持当前页面焦点（不强行聚焦输入框——兼容多种网页）
        try {
            wv.requestFocus();
            long now = android.os.SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState);
            KeyEvent up = new KeyEvent(now, now + 50, KeyEvent.ACTION_UP, keyCode, 0, metaState);
            wv.dispatchKeyEvent(down);
            wv.dispatchKeyEvent(up);
        } catch (Exception ignored) {
            // KeyEvent 注入失败静默（JS 合成已发）
        }
    }

    /**
     * JS 合成 + 聚焦输入框：先聚焦页面输入框（kimi code 的 inject 需输入框有焦点），
     * 再向 activeElement 派发带 code 的 KeyboardEvent（CodeMirror 按 e.code 匹配）。
     */
    private void injectJsWithFocus(boolean ctrl, boolean shift, boolean alt, String key) {
        // 聚焦脚本：当前焦点是 body 时聚焦第一个输入框（textarea/contenteditable/input）
        String focusJs = "(function(){var t=document.activeElement;"
                + "if(!t||t===document.body){"
                + "var els=document.querySelectorAll('textarea,[contenteditable=true],input[type=text],input:not([type])');"
                + "if(els.length>0)els[0].focus();"
                + "}return true;})()";
        try {
            wv.evaluateJavascript(focusJs, value -> {
                injectJsFallback(ctrl, shift, alt, key);
            });
        } catch (Exception ignored) {
            injectJsFallback(ctrl, shift, alt, key);
        }
    }

    /** JS 合成 KeyboardEvent（kimi code 等不校验 isTrusted，合成事件可收到） */
    private void injectJsFallback(boolean ctrl, boolean shift, boolean alt, String key) {
        String jsKey = shift ? key.toUpperCase() : key.toLowerCase();
        // code 字段：CodeMirror 类编辑器按 e.code（KeyS）匹配，必须带上
        String jsCode = keyCodeToJsCode(key);
        String js = "var t=document.activeElement||document.body;"
                + "t.dispatchEvent(new KeyboardEvent('keydown',{key:'" + jsKey + "',code:'" + jsCode
                + "',ctrlKey:" + ctrl + ",shiftKey:" + shift + ",altKey:" + alt
                + ",bubbles:true,cancelable:true}));"
                + "t.dispatchEvent(new KeyboardEvent('keyup',{key:'" + jsKey + "',code:'" + jsCode
                + "',ctrlKey:" + ctrl + ",shiftKey:" + shift + ",altKey:" + alt
                + ",bubbles:true,cancelable:true}));";
        try {
            wv.evaluateJavascript(js, null);
        } catch (Exception ignored) {
            // JS 注入失败静默
        }
    }

    /** 主键 → JS KeyboardEvent.code（KeyA..KeyZ / Digit0..9 / F1..F12 / Enter / Space / Tab / Backspace） */
    private String keyCodeToJsCode(String key) {
        if (key.length() == 1) {
            char c = key.charAt(0);
            if (c >= 'A' && c <= 'Z') return "Key" + c;
            if (c >= 'a' && c <= 'z') return "Key" + Character.toUpperCase(c);
            if (c >= '0' && c <= '9') return "Digit" + c;
        }
        switch (key) {
            case "Enter": return "Enter";
            case "Space": return "Space";
            case "Tab": return "Tab";
            case "Backspace": return "Backspace";
            case "F1": case "F2": case "F3": case "F4": case "F5": case "F6":
            case "F7": case "F8": case "F9": case "F10": case "F11": case "F12":
                return key;
            default: return "";
        }
    }

    /** 组合键主键字符串 → Android KeyCode（A-Z / 0-9 / F1-F12 / Enter / Space / Tab / Backspace） */
    private int keyCodeOf(String key) {
        if (key.length() == 1) {
            char c = key.charAt(0);
            if (c >= 'A' && c <= 'Z') return KeyEvent.KEYCODE_A + (c - 'A');
            if (c >= 'a' && c <= 'z') return KeyEvent.KEYCODE_A + (c - 'a');
            if (c >= '0' && c <= '9') return KeyEvent.KEYCODE_0 + (c - '0');
        }
        switch (key) {
            case "Enter": return KeyEvent.KEYCODE_ENTER;
            case "Space": return KeyEvent.KEYCODE_SPACE;
            case "Tab": return KeyEvent.KEYCODE_TAB;
            case "Backspace": return KeyEvent.KEYCODE_DEL;
            case "F1": return KeyEvent.KEYCODE_F1;
            case "F2": return KeyEvent.KEYCODE_F2;
            case "F3": return KeyEvent.KEYCODE_F3;
            case "F4": return KeyEvent.KEYCODE_F4;
            case "F5": return KeyEvent.KEYCODE_F5;
            case "F6": return KeyEvent.KEYCODE_F6;
            case "F7": return KeyEvent.KEYCODE_F7;
            case "F8": return KeyEvent.KEYCODE_F8;
            case "F9": return KeyEvent.KEYCODE_F9;
            case "F10": return KeyEvent.KEYCODE_F10;
            case "F11": return KeyEvent.KEYCODE_F11;
            case "F12": return KeyEvent.KEYCODE_F12;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    @SuppressLint("NonConstantResourceId")
    private void showWebViewPopupMenu() {
        View center = findViewById(R.id.anchorCenterScreen);
        mPopupMenu = IconPopupMenuHelper.getMenu(center, R.menu.wv_context_menu, WebViewActivity.this);

        String currentUrl = wv.getUrl();
        String title = "";
        if (currentUrl != null) {
            title = currentUrl.length() < 32 ? currentUrl : currentUrl.substring(0, 32) + "…";
        }
        SpannableString spanStringWebAppTitle = new SpannableString(title);

        // The item is disabled because it has no click action, but we want to override the disabled style (text color)
        int colorOnSurface = MaterialColors.getColor(center, com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        ForegroundColorSpan foregroundColorSpan = new ForegroundColorSpan(colorOnSurface);
        spanStringWebAppTitle.setSpan(foregroundColorSpan, 0,     spanStringWebAppTitle.length(), 0);

        spanStringWebAppTitle.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), 0, spanStringWebAppTitle.length(), 0);
        mPopupMenu.getMenu().getItem(0).setTitle(spanStringWebAppTitle);

        for (int i = 0; i < mPopupMenu.getMenu().size(); i++) {
            MenuItem item = mPopupMenu.getMenu().getItem(i);
            SpannableString spanString = new SpannableString(item.getTitle());
            spanString.setSpan(foregroundColorSpan, 0, spanString.length(),0);
            item.setTitle(spanString);
        }
        if(wv.canGoForward()) {
            MenuItem forwardItem = mPopupMenu.getMenu().findItem(R.id.cmItemForward);
            if (forwardItem != null) forwardItem.setVisible(true);
        }
        if(BuildConfig.DEBUG) {
            MenuItem debugItem = mPopupMenu.getMenu().findItem(R.id.cmFallbackContextmenuTemp);
            if (debugItem != null) debugItem.setVisible(true);
        }
        mPopupMenu.setOnMenuItemClickListener(menuItem -> {
            int id = menuItem.getItemId();
            if (id == R.id.cmItemForward) {
                wv.goForward();
                return true;
            }
            if (id == R.id.cmItemBack) {
                onBackPressed();
                return true;
            }
            if (id == R.id.cmItemReload) {
                wv.reload();
                return true;
            }
            if (id == R.id.cmItemCopyUrl) {
                ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                ClipData clip = ClipData.newPlainText("URL", wv.getUrl());
                clipboard.setPrimaryClip(clip);
                return true;
            }
            if (id == R.id.cmItemShareUrl) {
                new ShareCompat.IntentBuilder(WebViewActivity.this)
                        .setType("text/plain")
                        .setChooserTitle("Share URL")
                        .setText(wv.getUrl())
                        .startChooser();
                return true;
            }
            if (id == R.id.cmItemCloseWebApp) {
                finishAndRemoveTask();
                return true;
            }
            if (id == R.id.cmFallbackContextmenuTemp) {
                fallbackToDefaultLongClickBehaviour = true;
                return true;
            }
            if (id == R.id.cmMainMenu) {
                Intent intent = new Intent(this, MainActivity.class);
                startActivity(intent);
                return true;
            }
            return false;
        });

        mPopupMenu.show();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        this.setDarkModeIfNeeded();
    }

    // 渲染核心的有意实现：WebView 后退优先 + 再按退出，不走 super（避免双重处理）
    // 注：Manifest enableOnBackInvokedCallback=true 下系统手势仍会回调本方法（legacy 兼容）
    @SuppressLint({"MissingSuperCall", "GestureBackNavigation"})
    @Override
    public void onBackPressed() {
        WebApp webapp = DataManager.getInstance().getWebApp(webappID);
        if (webapp == null) {
            finish();
            return;
        }

        if(wv.canGoBack()) {
            wv.goBack();
            return;
        }

        if(quitOnNextBackpress) {
            quitOnNextBackpress = false;
            moveTaskToBack(true);
            return;
        }

        loadURL(wv, webapp.getBaseUrl());
        quitOnNextBackpress = true;

    }

    @Override
    protected void onResume() {
        super.onResume();
        int new_id = getIntent().getIntExtra(Const.INTENT_WEBAPPID, -1);

        if (new_id != webappID) {
            WebApp new_webapp = DataManager.getInstance().getWebApp(new_id);
            if (new_webapp != null) {
                WebViewLauncher.startWebView(new_webapp, this);
            }
        }

        if (wv != null) {
            wv.onResume();
            wv.resumeTimers();
        }
        this.setDarkModeIfNeeded();

        if (webapp != null && webapp.isAutoreload()) {
            reload_handler = new Handler();
            reload();
        }

    }

    @Override
    protected void onPause() {
        super.onPause();

        if (wv != null) {
            wv.evaluateJavascript("document.querySelectorAll('audio').forEach(x => x.pause());document.querySelectorAll('video').forEach(x => x.pause());", null);
            wv.onPause();
            wv.pauseTimers();
        }
        // 统计埋点：落盘兜底（内存统计写入持久化）
        StatsRecorder.INSTANCE.flush();
        if(mPopupMenu != null) mPopupMenu.dismiss();

        if (webapp != null && (webapp.isClearCache() || DataManager.getInstance().getSettings().isClearCache()) && wv != null)
            wv.clearCache(true);

        if (reload_handler != null) {
            reload_handler.removeCallbacksAndMessages(null);
            Log.d("CLEANUP", "Stopped reload handler");
        }
    }

    /**
     * 内存压力回调：只做轻量操作。
     *
     * 注意：不可在此调用 WebView 重型方法（clearCache/freeMemory）——
     * 系统 dispatchTrimMemory 时 WebView 内部也在处理同一回调（WV.qi1.onTrimMemory），
     * 并发操作原生层会导致 SIGILL 崩溃（libwebviewchromium.so，模拟器实测复现）。
     * WebView 内存回收交给 onPause/onDestroy 的既有逻辑处理。
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (wv == null) return;

        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // 页面不可见：仅暂停计时器（轻量、线程安全）
            wv.pauseTimers();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 组合快捷键：已绑定组合键拦截发送（不触发浏览器默认），管理在设置页点选录入
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean ctrl = event.isCtrlPressed();
            boolean shift = event.isShiftPressed();
            boolean alt = event.isAltPressed();
            int keyCode = event.getKeyCode();
            // 仅捕获组合键（Ctrl/Shift/Alt 单独按下不处理）
            if (ctrl || shift || alt) {
                String key = keyCodeToChar(keyCode, shift);
                if (key != null) {
                    String shortcut = buildShortcutString(ctrl, shift, alt, key);
                    // 已绑定快捷键：拦截发送（不触发浏览器默认）
                    if (isBoundShortcut(shortcut)) {
                        sendShortcutToPage(shortcut);
                        return true;
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** 是否已绑定的组合键 */
    private boolean isBoundShortcut(String shortcut) {
        WebApp w = DataManager.getInstance().getWebAppIgnoringGlobalOverride(webappID, true);
        return w != null && w.getKeyShortcuts() != null && w.getKeyShortcuts().contains(shortcut);
    }

    /** 构建组合键字符串（Ctrl+S / Ctrl+Shift+S） */
    private String buildShortcutString(boolean ctrl, boolean shift, boolean alt, String key) {
        StringBuilder sb = new StringBuilder();
        if (ctrl) sb.append("Ctrl+");
        if (shift) sb.append("Shift+");
        if (alt) sb.append("Alt+");
        sb.append(key);
        return sb.toString();
    }

    /** keyCode → 字符（字母/数字/功能键） */
    private String keyCodeToChar(int keyCode, boolean shift) {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            char c = (char) ('a' + (keyCode - KeyEvent.KEYCODE_A));
            return shift ? String.valueOf(Character.toUpperCase(c)) : String.valueOf(c);
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return String.valueOf((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_F1: return "F1";
            case KeyEvent.KEYCODE_F2: return "F2";
            case KeyEvent.KEYCODE_F3: return "F3";
            case KeyEvent.KEYCODE_F4: return "F4";
            case KeyEvent.KEYCODE_F5: return "F5";
            case KeyEvent.KEYCODE_F6: return "F6";
            case KeyEvent.KEYCODE_F7: return "F7";
            case KeyEvent.KEYCODE_F8: return "F8";
            case KeyEvent.KEYCODE_F9: return "F9";
            case KeyEvent.KEYCODE_F10: return "F10";
            case KeyEvent.KEYCODE_F11: return "F11";
            case KeyEvent.KEYCODE_F12: return "F12";
            case KeyEvent.KEYCODE_ENTER: return "Enter";
            case KeyEvent.KEYCODE_SPACE: return " ";
            case KeyEvent.KEYCODE_TAB: return "Tab";
            case KeyEvent.KEYCODE_DEL: return "Backspace";
            default: return null;
        }
    }



    /**
     * 文本/链接选择 ActionMode 启动（系统长按选择时回调）：
     * 标记 actionModeActive，供长按延迟检测区分「有内容可操作」vs「空白处」。
     */
    @Override
    public void onActionModeStarted(android.view.ActionMode mode) {
        actionModeActive = true;
        currentActionMode = mode;
        super.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(android.view.ActionMode mode) {
        actionModeActive = false;
        if (currentActionMode == mode) currentActionMode = null;
        super.onActionModeFinished(mode);
    }

    @Override
    protected void onDestroy() {
        // 登录态隔离：开启隔离的 WebApp 保存 Cookie 快照（异步，多标签按 tabIndex）
        com.cylonid.nativealpha.util.CookieSessionManager.INSTANCE.saveSnapshot(this, webappID, webappTabIndex);
        // 显式销毁 WebView，释放渲染进程与内存（低损耗目标）
        cancelBlankScreenCheck();
        if (wv != null) {
            wv.removeAllViews();
            wv.destroy();
            wv = null;
        }
        if (reload_handler != null) {
            reload_handler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }

    private void reload() {
        reload_handler.postDelayed(() -> {
            currently_reloading = true;
            if (wv != null) wv.reload();
            reload();
        }, webapp.getTimeAutoreload() * 1000L);
    }

    public WebView getWebView() {
        return wv;
    }

    private Map<String, String> initCustomHeaders(boolean save_data) {
        Map<String, String> extraHeaders = new HashMap<>();
        extraHeaders.put("DNT", "1");
        extraHeaders.put("X-REQUESTED-WITH", "");
        extraHeaders.put("Accept-Language", LocaleUtils.getAcceptLanguage());
        if (save_data)
            extraHeaders.put("Save-Data", "on");
        return Collections.unmodifiableMap(extraHeaders);
    }

    private void loadURL(final WebView view, final String url) {
        final WebApp webApp = DataManager.getInstance().getWebApp(webappID);
        if (webApp == null) {
            finish();
            return;
        }
        if (url.contains("http://") && !webApp.isAllowHttp()) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(WebViewActivity.this);

            builder.setTitle(getString(R.string.no_https_dialog_title));
            builder.setMessage(getString(R.string.no_https_dialog_msg));
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.setPositiveButton(getString(R.string.no_https_dialog_accept), (dialog, id) -> {
                webApp.setAllowHttp(true);
                webApp.setOverrideGlobalSettings(true);
                DataManager.getInstance().saveWebAppData();
                view.loadUrl(url, CUSTOM_HEADERS);
            });
            builder.setNegativeButton(getString(android.R.string.cancel), (dialog, id) -> finish());
            final AlertDialog dialog = builder.create();
            dialog.show();
        } else
            view.loadUrl(url, CUSTOM_HEADERS);

    }
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if(controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());

                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private void showSystemBars() {

        if(webapp.isShowFullscreen()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

            getWindow().setDecorFitsSystemWindows(true);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        boolean allGranted = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            onPermissionsGranted(requestCode, Arrays.asList(permissions));
        } else {
            onPermissionsDenied(requestCode, Arrays.asList(permissions));
        }
    }
    @FunctionalInterface
    interface PermissionGrantedCallback {
        void execute();
    }

    private void enablePermissionBoolOnWebApp(PermissionGrantedCallback successCallback) {
        webapp.setOverrideGlobalSettings(true);
        successCallback.execute();
        DataManager.getInstance().replaceWebApp(webapp);
        wv.reload();
    }

    private void onPermissionsGranted(int requestCode, @NonNull List<String> list) {
        if (requestCode == Const.PERMISSION_RC_LOCATION) {
            enablePermissionBoolOnWebApp(() -> webapp.setAllowLocationAccess(true));
            this.handleGeoPermissionCallback(true);
        }
        if (requestCode == Const.PERMISSION_CAMERA) {
            enablePermissionBoolOnWebApp(() -> webapp.setCameraPermission(true));
        }
        if (requestCode == Const.PERMISSION_RC_STORAGE) {
            if (dl_request != null) {
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(dl_request);
                    NotificationUtils.showInfoSnackbar(this, getString(R.string.file_download), Snackbar.LENGTH_SHORT);
                }
                dl_request = null;

            }
        }
    }

    private void onPermissionsDenied(int requestCode, List<String> list) {
        if (requestCode == Const.PERMISSION_RC_LOCATION) {
            this.handleGeoPermissionCallback(false);
        }
    }

    private void handleGeoPermissionCallback(boolean allow) {
        if (mGeoPermissionRequestCallback != null) {
            mGeoPermissionRequestCallback.invoke(mGeoPermissionRequestOrigin, allow, false);
            mGeoPermissionRequestCallback = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent intent) {

        super.onActivityResult(requestCode, resultCode, intent);
        if (resultCode == RESULT_CANCELED && requestCode == CODE_OPEN_FILE) {
            this.filePathCallback.onReceiveValue(null);
        } else if (resultCode == RESULT_OK && requestCode == CODE_OPEN_FILE) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, intent));
            filePathCallback = null;
        }
    }


    private class CustomWebChromeClient extends android.webkit.WebChromeClient {
        private View mCustomView;
        private WebChromeClient.CustomViewCallback mCustomViewCallback;
        private int mOriginalOrientation;
        private int mOriginalSystemUiVisibility;

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage consoleMessage) {
            // 统计埋点：页面 JS 错误（未捕获异常/语法错误走 console.error 上报）
            if (consoleMessage != null
                    && consoleMessage.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                StatsRecorder.INSTANCE.recordPageError(
                    webappID, ErrorType.JS.name(),
                    ErrorType.JS.getCode(),
                    consoleMessage.message() != null ? consoleMessage.message() : "JS error"
                );
            }
            return false; // 不阻断页面（仅采集）
        }

        private void handlePermissionRequest(String resId,
                                             boolean currentState,
                                             String[] androidPermissions,
                                             int requestCode,
                                             List<String> permissionsToGrant,
                                             String[] webkitPermission,
                                             PermissionGrantedCallback successCallback) {
            boolean androidPermissionsMissing = areAndroidPermissionsMissing(androidPermissions);
            if (currentState && androidPermissionsMissing) {
                // 权限审计：区分「首次请求」vs「永久拒绝」（勾选"不再询问"）
                // 全部权限都已请求过 + shouldShowRequestPermissionRationale=false → 永久拒绝，
                // 不再重复弹系统框，引导用户去系统设置手动开启
                boolean allRequested = true;
                for (String perm : androidPermissions) {
                    if (!requestedPermissions.contains(perm)) {
                        allRequested = false;
                        break;
                    }
                }
                if (allRequested) {
                    boolean permanentlyDenied = false;
                    for (String perm : androidPermissions) {
                        if (ContextCompat.checkSelfPermission(WebViewActivity.this, perm) != PackageManager.PERMISSION_GRANTED
                                && !ActivityCompat.shouldShowRequestPermissionRationale(WebViewActivity.this, perm)) {
                            permanentlyDenied = true;
                            break;
                        }
                    }
                    if (permanentlyDenied) {
                        handleGeoPermissionCallback(false);
                        showPermissionPermanentlyDeniedDialog(resId);
                        return;
                    }
                }
                for (String perm : androidPermissions) {
                    requestedPermissions.add(perm);
                }
                ActivityCompat.requestPermissions(WebViewActivity.this, androidPermissions, requestCode);
                return;
            }
            if (currentState && !androidPermissionsMissing) {
                permissionsToGrant.addAll(Arrays.asList(webkitPermission));
                handleGeoPermissionCallback(true);
                return;
            }

            new AlertDialog.Builder(WebViewActivity.this).setTitle(getPermissionRequestStringResource("dialog_permission_", resId, "_title"))
                    .setMessage(getPermissionRequestStringResource("dialog_permission_", resId, "_txt"))
                    .setPositiveButton(android.R.string.yes, (dialog, id) -> {
                        enablePermissionBoolOnWebApp(successCallback);
                        handleGeoPermissionCallback(true);
                        permissionsToGrant.addAll(Arrays.asList(webkitPermission));
                        if (androidPermissionsMissing) {
                            ActivityCompat.requestPermissions(WebViewActivity.this, androidPermissions, requestCode);
                        }
                    }).setNegativeButton(android.R.string.no, (dialog, id) -> handleGeoPermissionCallback(false)).create().show();
        }

        private String getPermissionRequestStringResource(String prefix, String variable, String suffix) {
            return getString(WebViewActivity.this.getResources().getIdentifier(prefix + variable + suffix, "string", WebViewActivity.this.getPackageName()));
        }

        private boolean areAndroidPermissionsMissing(String[] androidPermissions) {
            for (String perm : androidPermissions) {
                if (ContextCompat.checkSelfPermission(WebViewActivity.this, perm) != PackageManager.PERMISSION_GRANTED) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 权限被永久拒绝：不再重复弹系统框，提示用户去系统设置手动开启。
         */
        private void showPermissionPermanentlyDeniedDialog(String resId) {
            String title = getPermissionRequestStringResource("dialog_permission_", resId, "_title");
            new AlertDialog.Builder(WebViewActivity.this)
                    .setTitle(title)
                    .setMessage(getString(R.string.permission_permanently_denied_msg, title))
                    .setPositiveButton(getString(R.string.permission_go_to_settings), (dialog, id) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch (Exception ignored) {
                            // 无设置页可跳时静默（不影响主功能）
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create().show();
        }

        @Override
        public boolean onShowFileChooser(
                WebView webView, ValueCallback<Uri[]> pFilePathCallback,
                WebChromeClient.FileChooserParams fileChooserParams) {
            filePathCallback = pFilePathCallback;
            try {
                Intent intent = fileChooserParams.createIntent();
                startActivityForResult(intent, CODE_OPEN_FILE);
            } catch (Exception e) {
                NotificationUtils.showInfoSnackbar(WebViewActivity.this, getString(R.string.no_filemanager), Snackbar.LENGTH_LONG);
                e.printStackTrace();
            }
            return true;
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            final Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawARGB(0, 0, 0, 0);
            return bitmap;
        }

        public void onHideCustomView() {
            ((FrameLayout) getWindow().getDecorView()).removeView(this.mCustomView);
            this.mCustomView = null;
            getWindow().getDecorView().setSystemUiVisibility(this.mOriginalSystemUiVisibility);
            setRequestedOrientation(this.mOriginalOrientation);
            this.mCustomViewCallback.onCustomViewHidden();
            this.mCustomViewCallback = null;
            showSystemBars();
        }

        public void onShowCustomView(View pView, WebChromeClient.CustomViewCallback pViewCallback) {
            if (this.mCustomView != null) {
                onHideCustomView();
                return;
            }
            this.mCustomView = pView;
            this.mOriginalSystemUiVisibility = getWindow().getDecorView().getSystemUiVisibility();
            this.mOriginalOrientation = getRequestedOrientation();
            this.mCustomViewCallback = pViewCallback;
            ((FrameLayout) getWindow().getDecorView()).addView(this.mCustomView, new FrameLayout.LayoutParams(-1, -1));
            hideSystemBars();
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            List<String> permissionsToGrant = new ArrayList<>();

            boolean containsDrmRequest = Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID);
            boolean containsCameraRequest = Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
            boolean containsMicrophoneRequest = Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE);

            if (containsDrmRequest) {
                this.handlePermissionRequest("drm", webapp.isDrmAllowed(), new String[]{}, -1, permissionsToGrant, new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID}, () -> webapp.setDrmAllowed(true));
            }
            if (containsCameraRequest) {
                this.handlePermissionRequest("camera", webapp.isCameraPermission(), new String[]{Manifest.permission.CAMERA}, Const.PERMISSION_CAMERA, permissionsToGrant, new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}, () -> webapp.setCameraPermission(true));
            }

            if (containsMicrophoneRequest) {
                this.handlePermissionRequest("microphone", webapp.isMicrophonePermission(), new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS}, Const.PERMISSION_AUDIO, permissionsToGrant, new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}, () -> webapp.setMicrophonePermission(true));
            }

            request.grant(permissionsToGrant.toArray(new String[0]));
        }


        public void onProgressChanged(WebView view, int progress) {

            // 白屏检测：记录进度推进（用于 20s 无推进超时判定）
            if (progress > lastProgress) {
                lastProgress = progress;
                lastProgressTime = System.currentTimeMillis();
                // 进度有推进 → 重新计时（每次推进重置 20s）
                scheduleBlankScreenCheck();
            }

            if (DataManager.getInstance().getSettings().isShowProgressbar() || currently_reloading) {
                if (progressBar.getVisibility() == ProgressBar.GONE && progress < 100) {
                    progressBar.setVisibility(ProgressBar.VISIBLE);
                }
                // 加载动画：进度条显示时同步显示动物走路动画（页面加载中可见）
                if (progress < 100 && progressBar.getVisibility() == ProgressBar.VISIBLE) {
                    startLoadingAnimal();
                } else {
                    stopLoadingAnimal();
                }

                // 平滑过渡（150ms），避免进度跳变
                progressBar.setProgress(progress, true);

                if (progress == 100) {
                    progressBar.setVisibility(ProgressBar.GONE);
                    currently_reloading = false;
                    stopLoadingAnimal();
                }
            }
        }


        @Override
        public void onGeolocationPermissionsShowPrompt(final String origin,
                                                       final GeolocationPermissions.Callback callback) {
            mGeoPermissionRequestCallback = callback;
            mGeoPermissionRequestOrigin = origin;
            this.handlePermissionRequest("location", webapp.isAllowLocationAccess(), new String[]{Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, Const.PERMISSION_RC_LOCATION, Arrays.asList(new String[]{}), new String[]{}, () -> webapp.setAllowLocationAccess(true));

        }
    }

    /**
     * 白屏检测：进度在 20s 内无推进 → 判定加载卡死，加载错误页并提示重试。
     * 只在新页面加载开始后计时，进度推进即重置；加载完成即取消。
     * AI 流式页进度持续推进（onProgressChanged 持续回调），不会误判。
     */
    private void scheduleBlankScreenCheck() {
        blankScreenHandler.removeCallbacks(blankScreenCheck);
        if (!pageLoadFinished) {
            blankScreenHandler.postDelayed(blankScreenCheck, Const.BLANK_SCREEN_TIMEOUT_MS);
        }
    }

    private void cancelBlankScreenCheck() {
        blankScreenHandler.removeCallbacks(blankScreenCheck);
    }

    private void handleBlankScreen() {
        if (pageLoadFinished || wv == null) return;
        long idle = System.currentTimeMillis() - lastProgressTime;
        if (idle >= Const.BLANK_SCREEN_TIMEOUT_MS && lastProgress < 100) {
            // 加载卡死：加载本地错误页（带重试），避免白屏挂起
            runOnUiThread(() -> {
                NotificationUtils.showInfoSnackbar(
                    WebViewActivity.this,
                    getString(R.string.blank_screen_detected),
                    Snackbar.LENGTH_LONG
                );
                // 加载中断：重置计时起点，避免错误页误计为页面加载耗时
                pageLoadStartTime = 0;
                wv.stopLoading();
                loadCustomErrorPage("timeout", getString(R.string.blank_screen_detected));
            });
        }
    }

    /**
     * 加载自定义错误页（M3 靛蓝统一风格，替代系统默认白屏）。
     * 带错误码/描述参数（query 传入，页面显示开发者向信息）。
     * 语言：跟随 LocaleUtils（zh/en/de）。
     * 注意：临时重置 textZoom=100——错误页不应继承用户对原页面的缩放（否则太小/太大）。
     */
    private void loadCustomErrorPage(String code, String desc) {
        if (wv == null) return;
        try {
            // 错误页用固定缩放（130：当前设备实测最舒适，不继承原页面缩放）
            wv.getSettings().setTextZoom(Const.ERROR_PAGE_TEXT_ZOOM);
            String lang = LocaleUtils.getFileEnding();
            String safeCode = code != null ? code : "";
            String safeDesc = desc != null ? desc : "";
            // URL 编码 desc（含空格/特殊字符安全）
            String encodedDesc = java.net.URLEncoder.encode(safeDesc, "UTF-8");
            wv.loadUrl("file:///android_asset/errorSite/error_" + lang
                    + ".html?code=" + safeCode + "&desc=" + encodedDesc);
        } catch (Exception ignored) {
            // 错误页加载失败静默（保持现状）
        }
    }

    private void showHttpAuthDialog(final HttpAuthHandler handler, String host, String realm) {
        DialogHttpAuthBinding localBinding = DialogHttpAuthBinding.inflate(LayoutInflater.from(this));
        new AlertDialog.Builder(this)
                .setView(localBinding.getRoot())
                .setTitle(getString(R.string.http_auth_title))
                .setMessage(getString(R.string.enter_http_auth_credentials, realm, host))
                .setPositiveButton(getString(R.string.ok), (dialog, whichButton) -> {
                    String username = localBinding.username.getText().toString();
                    String password = localBinding.password.getText().toString();

                    handler.proceed(username, password);

                })
                .setNegativeButton(getString(R.string.cancel), (dialog, whichButton) -> handler.cancel())
                .show();
    }

    private class CustomBrowser extends WebViewClient {

        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
            showHttpAuthDialog(handler, host, realm);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            // 加载完成：取消白屏检测（避免误判）
            pageLoadFinished = true;
            cancelBlankScreenCheck();
            // 页面加载完成：隐藏加载页动物动画
            stopLoadingAnimal();
            // 统计埋点：主体加载耗时（started 到 finished）
            if (pageLoadStartTime > 0) {
                StatsRecorder.INSTANCE.recordPageLoaded(webappID, System.currentTimeMillis() - pageLoadStartTime);
                pageLoadStartTime = 0;
            }
            // 统计埋点：缓存占用（cacheDir + WebStorage，异步不阻塞）
            recordCacheUsage();
            if(url.equals("about:blank")) {
                loadCustomErrorPage("blank", "");
            }
            wv.evaluateJavascript("document.addEventListener(\"visibilitychange\",function (event) {event.stopImmediatePropagation();},true);", null);
            // 移除图片 title/alt 属性（防止 WebView 查看图片时显示图片名浮层遮挡）。
            // MutationObserver 持续清除（SPA 动态图片）；busy 标志防递归
            // （clean 修改属性会再触发 observer）
            wv.evaluateJavascript(
                    "(function(){"
                    + "var busy=false;"
                    + "var clean=function(){"
                    + "if(busy)return;busy=true;"
                    + "document.querySelectorAll('img').forEach(function(i){i.removeAttribute('title');i.removeAttribute('alt');});"
                    + "busy=false;"
                    + "};"
                    + "clean();"
                    + "var mo=new MutationObserver(function(){clean();});"
                    + "mo.observe(document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['title','alt']});"
                    + "})()",
                    null);
            // 页面缩放：zoomBy 模拟捏合（对移动版自适应页面可靠）
            applyPageZoom();
            super.onPageFinished(view, url);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            // 新页面加载：重置白屏检测（进度从 0 重新计时）
            pageLoadFinished = false;
            lastProgress = 0;
            lastProgressTime = System.currentTimeMillis();
            scheduleBlankScreenCheck();
            // 统计埋点：记录加载开始时间
            pageLoadStartTime = System.currentTimeMillis();
            super.onPageStarted(view, url, favicon);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            // 仅主框架错误处理（子资源错误不统计防噪音）
            if (request != null && request.isForMainFrame()) {
                String code = error != null ? String.valueOf(error.getErrorCode()) : "unknown";
                String desc = error != null && error.getDescription() != null
                        ? error.getDescription().toString() : "";
                // 统计埋点：记录页面错误
                StatsRecorder.INSTANCE.recordPageError(
                    webappID,
                    ErrorType.NETWORK.name(),
                    code,
                    desc
                );
                // 记录重试目标 + 加载自定义错误页（不显示系统默认白屏）
                retryUrl = request.getUrl() != null ? request.getUrl().toString() : urlOnFirstPageload;
                loadCustomErrorPage(code, desc);
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            super.onReceivedHttpError(view, request, errorResponse);
            // 统计埋点：HTTP 状态码错误（主框架）
            if (request != null && request.isForMainFrame()) {
                StatsRecorder.INSTANCE.recordPageError(
                    webappID,
                    ErrorType.HTTP.name(),
                    errorResponse != null ? String.valueOf(errorResponse.getStatusCode()) : "unknown",
                    "HTTP error"
                );
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // 渲染进程崩溃/OOM：避免整个应用崩溃，提示用户并关闭页面
            StatsRecorder.INSTANCE.recordPageError(webappID, ErrorType.RENDER.name(), ErrorType.RENDER.getCode(), "Render process gone");
            runOnUiThread(() -> {
                NotificationUtils.showInfoSnackbar(
                    WebViewActivity.this,
                    getString(R.string.render_process_gone),
                    Snackbar.LENGTH_LONG
                );
                finish();
            });
            return true; // 已处理，阻止系统终止应用
        }

        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if(urlOnFirstPageload.equals("")) urlOnFirstPageload = request.getUrl().toString();

            if (webapp.isBlockThirdPartyRequests()) {
                Uri uri = request.getUrl();
                Uri webapp_uri = Uri.parse(webapp.getBaseUrl());

                if(uri.getHost() != null) {
                    if (!uri.getHost().endsWith(webapp_uri.getHost())) {
                        return new WebResourceResponse("text/plain", "utf-8", null);
                    }
                }
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {

            //This option is hidden in "expert settings"
            if (webapp.isIgnoreSslErrors()) {
                handler.proceed();
                return;
            }

            // 统计埋点：SSL 错误
            StatsRecorder.INSTANCE.recordPageError(webappID, ErrorType.SSL.name(), String.valueOf(error.getPrimaryError()), "SSL error");

            final AlertDialog.Builder builder = new AlertDialog.Builder(WebViewActivity.this);

            String message = getString(R.string.ssl_error_msg_line1) + " ";
            switch (error.getPrimaryError()) {
                case SslError.SSL_UNTRUSTED:
                    message += getString(R.string.ssl_error_unknown_authority) + "\n";
                    break;
                case SslError.SSL_EXPIRED:
                    message += getString(R.string.ssl_error_expired) + "\n";
                    break;
                case SslError.SSL_IDMISMATCH:
                    message += getString(R.string.ssl_error_id_mismatch) + "\n";
                    break;
                case SslError.SSL_NOTYETVALID:
                    message += getString(R.string.ssl_error_notyetvalid) + "\n";
                    break;
            }
            message += getString(R.string.ssl_error_msg_line2) + "\n";

            builder.setTitle(getString(R.string.ssl_error_title));
            builder.setMessage(message);
            builder.setIcon(android.R.drawable.ic_dialog_alert);
            builder.setPositiveButton(getString(android.R.string.cancel), (dialog, id) -> handler.cancel());
            builder.setNegativeButton(getString(R.string.load_anyway), (dialog, id) -> handler.proceed());
            final AlertDialog dialog = builder.create();
            dialog.show();
//            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setPadding(5, 5, 5, 5);
//            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setBackgroundColor(ContextCompat.getColor(WebViewActivity.this, android.R.color.holo_orange_light));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ContextCompat.getColor(WebViewActivity.this, android.R.color.holo_red_dark));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(WebViewActivity.this, android.R.color.holo_green_dark));
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            super.onLoadResource(view, url);

           WebApp webapp = DataManager.getInstance().getWebApp(webappID);
           if (webapp != null && webapp.isRequestDesktop())
               view.evaluateJavascript("""
                        var needsForcedWidth = document.documentElement.clientWidth < 1200;
                        if(needsForcedWidth) {
                          document.querySelector('meta[name=\"viewport\"]').setAttribute('content', 'width=1200px, initial-scale=' + (document.documentElement.clientWidth / 1200));
                        }
                       """, null);
            view.evaluateJavascript("document.addEventListener(    \"visibilitychange\"    , (event) => {         event.stopImmediatePropagation();    }  );", null);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            runOnUiThread(() -> setDarkModeIfNeeded());
            String url = request.getUrl().toString();
            WebApp webapp = DataManager.getInstance().getWebApp(webappID);

            if (url.startsWith("tel:")) {
                Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse(url));
                startActivity(intent);
                return true;
            }
            if (url.startsWith("mailto:")) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            }

            // 错误页重试：重新加载失败时的原地址（恢复用户 textZoom）
            if (url.startsWith("webnative://retry")) {
                if (!retryUrl.isEmpty() && wv != null) {
                    wv.getSettings().setTextZoom(webapp.getTextZoom());
                    wv.loadUrl(retryUrl);
                }
                return true;
            }

            // 非 http/https 协议（tbopen://、weixin:// 等 App 唤起协议）：
            // 交给系统处理（可唤起对应 App），避免 ERR_UNKNOWN_URL_SCHEME 错误页
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                } catch (Exception e) {
                    // 无对应 App：留在当前页不崩溃
                }
                return true;
            }

            if (webapp == null) {
                return false;
            }

            if (webapp.isOpenUrlExternal()) {
                String base_url = webapp.getBaseUrl();
                Uri uri = Uri.parse(base_url);
                String host = uri.getHost();
                if (!url.contains(host)) {
                    view.getContext().startActivity(
                            new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                }
            }
            loadURL(view, url);
            return true;
        }
    }
}


