# AGENTS.md

面向 AI 编码助手的 **WebNative** 项目指引。WebNative
是 [NativeAlphaForAndroid](https://github.com/cylonid/NativeAlphaForAndroid) 的一个深度修改分支，目标是把任意网站包装成沉浸式、可独立配置的
PWA 风格 Android 应用，并为高频文本流场景（AI 对话、代码生成、长文档）做渲染优化。

> 阅读本文件前，请先查阅 `.kimi/GoogleCodingStandards.md`——所有代码改动必须遵守其中的「十三条红线」与 Kotlin/Google Android
> 编码规范。

---

## 项目概览

- **应用名**：WebNative
- **包名 / namespace / applicationId**：`com.cylonid.nativealpha`
- **当前版本**：`2.2.18`（`versionCode 2218`）
- **最低 SDK**：31（Android 12）
- **目标 / 编译 SDK**：37
- **开源协议**：GPL-3.0
- **主要语言**：100% Kotlin，仓库内已无 `.java` 源文件（`WebViewActivity` 等最后一批 Java 文件已在近期迁移完成）
- **主要场景**：把 Kimi Code Web 等网站作为独立应用运行，提供全屏沉浸、手势导航、按站配置、Cookie 隔离、统计图表、多窗矩阵（同屏 2-6 站点，档位按设备内存动态分档）、网页事件提醒（网页事件 → 系统通知/Toast）等能力。

---

## 技术栈

- **语言**：Kotlin 2.3.20（AGP 9 内置 built-in Kotlin），Java 17 字节码
- **构建工具**：Gradle 9.7.0 + Android Gradle Plugin 9.3.1
- **UI 框架**：Jetpack Compose + Material 3（Compose BOM `2026.06.01`）
    - 所有配置/列表/向导/统计/关于页面均为 Compose
    - `WebViewActivity` 虽为 Kotlin，但仍使用传统 View/XML 布局（`res/layout/full_webview.xml`）承载 `WebView` 渲染核心
- **WebView**：系统 Android WebView + `androidx.webkit:webkit`（force-dark、OffscreenPreRaster 等）
- **数据持久化**：
    - `SharedPreferences` + Gson：WebApp 列表、全局设置、元信息
    - `DataStore`（Preferences）：错误日志、统计明细、Cookie 隔离快照
    - 功能包自持 DataStore（不参与宿主备份）：`matrix_session`（矩阵会话）、`webevent_rules`（事件规则）
- **主要依赖库**：
    - Gson（JSON 序列化）
    - JSoup（favicon / 标题自动识别）
    - Vico Compose M3（统计页图表）
    - Markwon（Markdown 渲染，用于更新日志等）
    - AndroidX ProfileInstaller（Baseline Profile，启动提速）
    - ZXing core（站点分享二维码生成；纯 Java，无 GMS/Android 传递依赖）
    - CameraX 1.5.x（应用内扫码相机栈：camera-camera2/lifecycle/view；权限走 ActivityResultContracts）
- **版本管理**：所有依赖版本集中在 `gradle/libs.versions.toml`（Version Catalog），build 文件禁止硬编码版本号

---

## 构建与测试命令

项目使用 Gradle Wrapper，本地需先配置 JDK 17+。当前仓库 `local.properties` 已指向 `D:\software\ambient\android` 的 Android
SDK。

```bash
# 1) 本地开发：编译 debug APK
./gradlew assembleDebug

# 2) 提交前必跑：单元测试 + lint（CI 同样执行）
./gradlew testDebugUnitTest lintDebug

# 3) 完整 CI 等价命令（push/PR 到 main/dev 分支）
./gradlew testDebugUnitTest lintDebug assembleDebug

# 4) Release 构建（需要根目录 key.properties，无则回退到 debug 签名）
./gradlew assembleRelease bundleRelease
```

> **交付即绿**：任何改动提交前必须 `testDebugUnitTest lintDebug` 全绿；新增功能需补充对应单测。

### 签名配置

Release 签名读取根目录 `key.properties`（已加入 `.gitignore`）：

```properties
storeFile=webnative-release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

CI 在 tag 构建时通过 GitHub Secrets（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`）动态注入该文件与
keystore。

---

## 代码规范

详细规范见 `.kimi/GoogleCodingStandards.md`。核心要求：

- **Kotlin Google Style Guide**：命名、导入、注释、格式化全部遵循官方指南
- **禁止通配符导入**（`import xxx.*`）：全量显式导入
- **禁止硬编码**：用户可见文案走 `R.string`（`values/` 英文 + `values-zh/` 中文双语），颜色/尺寸走 `res/values`，常量集中放入
  `Const.kt` 或对应枚举文件
- **资源命名**：`snake_case`；字符串全部外部化
- **Compose**：
    - `stringResource()` 在组合期预取，禁止在回调里使用 `context.getString`
    - 状态提升、生命周期感知的协程作用域（`lifecycleScope` / `rememberCoroutineScope`）
- **Activity 规范**：`onCreate` 内先 `ThemeUtils.applyUiMode()` + `setTheme(...)` +
  `ThemeUtils.applySystemBarColors(this)`
- **统一数据源**：
    - Cookie 隔离唯一入口：`CookieSessionManager`
    - WebApp 图标唯一入口：`WebAppIconManager`（调用方只编排，不重复实现存储/拉取逻辑）
- **线程与内存**：非主流程（IO、网络、统计、日志）必须走 `Dispatchers.IO` 或独立协程；禁止裸 `GlobalScope`
  ；重资源（Bitmap/流）随用随关；禁止静态长生命周期持有 Activity/Context
- **单文件 / 单方法规模**：单文件建议 ≤600 行，单方法建议 ≤80 行；超过必须按职责拆分
- **注释**：描述「为什么」而非「做了什么」；禁止用空行分段（分段用注释）；公共 API 写 KDoc

---

## 代码组织与模块划分

项目是单模块 Android 应用（`app` module），源码位于 `app/src/main/kotlin/com/cylonid/nativealpha/`。

```
app/src/main/kotlin/com/cylonid/nativealpha/
├── MainActivity.kt              # 主界面入口（Compose）
├── SettingsActivity.kt          # 全局设置页（Compose）
├── WebAppSettingsActivity.kt    # 单个 WebApp 设置页（Compose）
├── WebAppStatsActivity.kt       # 单个 WebApp 统计页（Compose）
├── WebViewActivity.kt           # 渲染核心（Kotlin + XML 布局 + WebView）
├── ScanCaptureActivity.kt       # 扫码取景页（CameraX + 自绘遮罩 ScanOverlayView）
├── WebViewSiteContext.kt        # 站点行为解耦接口（宿主/矩阵共用，防 Activity 强引用）
├── WebViewBrowserClient.kt      # WebViewClient 模板方法基类（SiteWebViewClient）+ 宿主子类
├── model/                       # 数据模型与数据中枢
│   ├── DataManager.kt           # 单例：WebApp 列表/全局设置的加载、持久化、备份导入导出
│   ├── WebApp.kt                # WebApp 数据类（含设置字段、统计字段、快捷键）
│   ├── GlobalSettings.kt        # 全局设置数据类
│   ├── AppErrorLog.kt           # 应用错误日志模型
│   ├── PageErrorLog.kt          # 页面错误日志模型
│   └── ErrorType.kt             # 错误类型枚举
├── ui/                          # Compose UI 屏幕与弹窗
│   ├── MainScreen.kt            # 主界面卡片列表 + 搜索 + FAB
│   ├── AddWebAppActivity.kt     # 两步添加向导
│   ├── SettingsScreen.kt        # 全局设置 Compose UI
│   ├── WebAppSettingsScreen.kt  # WebApp 设置 Compose UI
│   ├── WebAppStatsScreen.kt     # 统计图表页
│   ├── ShortcutRecreateDialog.kt# 重新创建快捷方式弹窗
│   ├── ShortcutMenuOverlay.kt   # 快捷方式菜单浮层
│   └── WebViewMenuOverlay.kt    # WebView 页面菜单浮层
├── matrix/                      # 多窗矩阵（独立包，MainActivity 直达 MatrixActivity，实现内聚）
│   ├── MatrixActivity.kt        # UI 宿主：生命周期接线（onDestroy 先 resumeTimers 再 release）
│   ├── MatrixScreen.kt          # 主入口 + 网格编排（338 行，渲染已拆至 MatrixCell/MatrixCellPicker）
│   ├── MatrixCell.kt            # 格子渲染层：五态窗格 + ActiveContent + FitFrameLayout（视口适配）
│   ├── MatrixCellPicker.kt      # 选站面板（底部 sheet）
│   ├── MatrixChrome.kt          # 可移动窗数胶囊：自由拖动 + 三向吸附 + 2s 自动吸顶 + 扩开快捷菜单
│   ├── MatrixEngine.kt          # 核心：五态机/交换/缩放注入/崩溃批量恢复/会话持久化
│   ├── MatrixSessionState.kt    # @Keep 会话模型（webappId/zoomPercent/textZoomPercent）
│   ├── MatrixSessionStore.kt    # 自持 DataStore matrix_session + 单写者 conflate 队列
│   ├── MatrixCapacityGate.kt    # 容量闸门（逐窗拦截 fail-open）+ 设备档位 decideMaxWindows（3/4/6 动态上限）
│   ├── MatrixCrashBackoff.kt    # 崩溃退避状态机（手动重试重置）
│   ├── MatrixDegrade.kt         # 强制降级纯函数（保留槽位内活跃格）
│   └── ...                      # CellAdjustSheet/CellClient/ChromeClient/MemorySampler/Codec
├── webevent/                    # 网页事件插件（独立包，入口 WebeventRuntime，实现 internal）
│   ├── EventRule.kt             # 规则模型（@Keep + proguard 显式 keep）
│   ├── EventRuleEngine.kt       # 三触发器引擎（跳变沿/冷却/500ms 合并）
│   ├── EventRuleStore.kt        # 自持 DataStore webevent_rules（rules + muted_sites 双 key）
│   ├── JsHookScript.kt          # 注入脚本（T1 Notification 拦截 + T3 标题劫持双路径 + T2 observer）
│   ├── WebEventBridge.kt        # @JavascriptInterface 桥（L4 纯抽取 → 主线程 post）
│   ├── WebeventRuntime.kt       # 初始化/注入判定/级联删除接线
│   └── ...                      # RuleEditorActivity/RuleEditorScreen/RuleWizardSheet/Notifier/EventsEntrySection
├── util/                        # 工具类与统一能力层
│   ├── App.kt                   # Application：崩溃兜底（OOM 标注）、主题初始化、内存压力记录
│   ├── AppStorage.kt            # DataStore 统一封装
│   ├── SystemBars.kt            # 系统控件全局能力：inset 兜底防遮+全屏沉浸（SelfManagedInsets 自管契约）
│   ├── AppTheme.kt / ThemeUtils.kt / ColorUtils.kt  # 主题/颜色/状态栏
│   ├── Const.kt                 # 全局常量
│   ├── CookieSessionManager.kt  # Cookie 隔离
│   ├── WebAppIconManager.kt     # 图标加载/保存/拉取
│   ├── IconGenerator.kt         # 渐变首字母图标生成
│   ├── WebAppDataFetcher.kt     # 添加向导：标题/favicon/start_url 识别
│   ├── WebViewLauncher.kt       # 启动 WebViewActivity 的封装
│   ├── WebViewSetup.kt          # WebView 创建配置纯函数（宿主/矩阵同源，禁双路径漂移）
│   ├── StatsRecorder.kt         # 加载耗时/缓存等统计记录
│   ├── FeatureMetrics.kt        # 功能观测门面：计数聚合 + 阈值批量落盘
│   ├── NotificationUtils.kt     # 通知/Toast 等
│   ├── UrlUtils.kt              # URL 规范化、校验、host 提取
│   ├── DateUtils.kt             # 日期格式化
│   ├── Utility.kt               # 通用辅助函数
│   └── ...
└── helper/
    ├── WebViewGestureHelper.kt       # 双击手势判定契约（JS 构建+语义解析，可单测）
    ├── WebViewTouchHandler.kt        # 触摸手势（双击菜单/长按下载/多指切换/边缘滑）
    ├── WebViewShortcutInjectHelper.kt  # 组合键注入（JS 合成 + KeyEvent 双路）
    ├── WebViewMenuHelper.kt          # 菜单浮层（缩放/会话标签/快捷键面板/缓存统计）
    └── WebViewPermissionHelper.kt    # 运行时权限分流
```

资源目录 `app/src/main/res/` 仅保留必要的 XML 资源：启动主题、WebView 布局、图标、字符串双语、错误页 HTML/CSS、Baseline
Profile 等。

---

## 数据与持久化

- **`DataManager` 单例**持有全部 WebApp 列表与全局设置：
    - WebApp 列表以 JSON 数组形式存于 `SharedPreferences`
    - **WebApp ID 同时是数组下标**——删除不真正移除条目，而是将 `isActiveEntry` 置为 `false`（`markInactive`）
    - `GlobalSettings` 同样以 JSON 存于 `SharedPreferences`
- **`AppStorage` DataStore** 用于错误日志、页面错误、统计快照、Cookie 隔离快照
- **功能包自持 DataStore**：`matrix_session`（矩阵会话）/ `webevent_rules`（事件规则）独立文件，
  **不参与宿主备份**（换机/清数据会丢，属已知取舍）
- **备份格式**：版本化 JSON，结构为 `{ checksum, data: { version, websites, settings } }`，SHA-256 校验
    - 当前 `BACKUP_FORMAT_VERSION = 3`（v3 起携带 webevent 规则分区，仍可导入 v2 备份）
    - 导入导出通过 SAF（Storage Access Framework）
- **无旧版数据兼容**：v2.0 起不再保留 legacy converter/deserializer；模型字段即 schema，重命名会改变持久化 JSON

---

## 测试策略

- **单元测试**：`app/src/test/kotlin/com/cylonid/nativealpha/`（按被测业务包镜像：util/matrix/webevent/helper/model/ui，公共设施 testing/）
    - 使用 JUnit 4 + Robolectric（`@Config(sdk = [34])`，Robolectric 当前最高支持到 API 34）
    - 覆盖：URL 工具、WebApp 设置拷贝、DataManager Gson 契约、图标管理、日期工具、应用错误日志等
- **UI / Compose 测试**：`app/src/androidTest/kotlin/com/cylonid/nativealpha/UITests.kt`
    - 使用 Compose UI Test + Espresso；CI 目前以单元测试为主
- **运行命令**：`./gradlew testDebugUnitTest`
- **新增功能必须补充单测**，并确保 `testDebugUnitTest lintDebug` 全绿

---

## 安全与隐私

- **权限最小化**：仅声明 `INTERNET`、`ACCESS_NETWORK_STATE`（矩阵/宿主断线自动恢复的网络探测）、`POST_NOTIFICATIONS`（网页事件通知，保存首条 notify 规则时场景化申请）、位置、相机、录音、修改音频设置；均为按需向用户申请
- **WebView 默认加固**（全局 + 每站两级，默认开启）：
    - 禁用文件访问（`setAllowFileAccess(false)`）
    - 禁用内容提供器访问（`setAllowContentAccess(false)`）
    - 拦截混合内容（`MIXED_CONTENT_NEVER_ALLOW`）
    - 限制 JS 自动弹窗（`setJavaScriptCanOpenWindowsAutomatically(false)`）
    - Safe Browsing 关闭（应用自身提供 HTTP 警告）
- **Cookie 隔离**：`WebApp.isIsolatedSession` 默认开启，通过 `CookieSessionManager` 在 WebView 切换时保存/恢复快照，避免多站登录态串扰
- **明文流量**：`android:usesCleartextTraffic="true"` 仅用于用户主动添加的非 HTTPS 站点
- **崩溃兜底**：`App` 中安装全局未捕获异常处理器，尽力写入 `AppErrorLogRepository` 后交还系统，避免数据丢失

---

## CI/CD 与发版

- **CI 文件**：`.github/workflows/ci.yml`
- **Push / PR 到 `main` / `dev`**：运行 `./gradlew testDebugUnitTest lintDebug assembleDebug`
- **Tag `v*`**：自动构建签名 APK + AAB，并创建 GitHub Release
    - Release body 根据两次 tag 之间的 commit 自动分类（亮点 / 修复 / 性能优化）
- **分发渠道**：仅 GitHub Releases，不上架 Play / F-Droid
- **版本升级**：修改 `app/build.gradle` 中 `defaultConfig.versionCode` / `versionName`，同步更新 `README.md`；
  `doc/KOTLIN_MIGRATION.md` 为历史迁移文档，迁移完成后已归档，后续版本变更无需再改

---

## 常见坑与注意事项

1. **WebViewActivity 现为 Kotlin，但仍是 View 渲染核心**：不要把它改成纯 Compose；其 XML 布局 `full_webview.xml` 承载
   WebView 与加载动画。
2. **Compose Compiler 插件不能删**：`build.gradle` 应用了 `alias(libs.plugins.kotlin.compose)`，否则 `remember` 等会出现
   inline 错误。
3. **Compose BOM 固定 `2026.06.01`**：不要随意升级，新 BOM 可能引入与 Kotlin 2.3.20 不兼容的 beta Compose 版本。
4. **Kotlin 源码目录**：`src/main/kotlin` 与 `src/main/java` 都会被编译；新增 Kotlin 文件优先放入 `src/main/kotlin`。
5. **R8 已启用**：`app/proguard-rules.pro` 必须保留 `com.cylonid.nativealpha.model.**`（Gson 反射依赖字段名）。
6. **DataManager 的 WebApp ID 即数组下标**：删除站点调用 `markInactive` / `isActiveEntry = false`，切勿从列表 `remove`。
7. **图标能力集中**：所有图标读取/保存/favicon 拉取走 `WebAppIconManager`，禁止在业务层重复写文件。
8. **Cookie 隔离入口唯一**：`CookieSessionManager`；不要直接在 `WebViewActivity` 里操作 `CookieManager` 做隔离逻辑。
9. **本地构建需 JDK 17+**：Gradle 9.7 不再支持 JDK 8。
10. **Release 签名回退**：没有 `key.properties` 时 release build 会使用 debug 签名，产物可安装但无法覆盖正式签名包。
11. **`@Keep` 不保 Gson 字段名**（R8 实锤）：反射序列化的模型类必须在 `proguard-rules.pro` 显式 keep
    （matrix/webevent 模型已有先例），仅加 `@Keep` 注解在 release 会丢字段。
12. **`pauseTimers()` 是进程全局开关**：多 WebView 场景（矩阵）必须严格配对恢复——`MatrixActivity.onDestroy`
    先 `resumeTimers` 再 release，否则残留会杀死之后所有页面加载（v2.2.1 P0 修复）。
13. **`WebView.zoomBy`/CSS zoom 均已废弃**：zoomBy 对带 viewport meta 的页面无效；
    CSS zoom（`documentElement.style.zoom`）只缩放渲染**不改变布局视口**（CDP 实测
    innerWidth 恒为格子宽，fixed/100vh 布局错乱）——现行方案是 View 级视口适配
    （`FitFrameLayout` 按 1/ratio 测量 WebView + View scale 缩回，innerWidth 真实变宽）。
    注意 Compose AndroidView 每布局 pass 会用容器约束强制 re-measure 子 View，
    自定义测量必须写在 onMeasure/onLayout 覆写里（update 里手动 measure 会被覆盖）。
14. **runTest 禁止对「恒失败重试」协程用 advanceUntilIdle**：无限退避=虚拟时间无限
    推进=测试挂死（UncompletedCoroutinesError）——用 advanceTimeBy(N) 只推进首轮。
15. **单测调 android.util.Log 会抛 not mocked**：协程内的 Log 调用会静默杀死协程
    （表现为断言 count=0 而非异常），插桩诊断用后必须删除。

---

## 本地开发速查

```bash
# 环境要求
JAVA_HOME=/path/to/jdk-17
Android SDK 路径已配置在 local.properties

# 常用命令
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleRelease bundleRelease
```

---

*最后更新：2026-09-01（基于仓库当前实际内容整理，版本 2.2.18）。*
