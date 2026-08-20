# WebNative v2.1.0 更新计划

> 版本：v2.1.0（versionCode 2100）
> 日期：2026-08-20
> 状态：✅ 已实施（10 次提交，全量回归通过）

## 🎯 编码目标

**新版本 · 新特性 · 高性能 · 低损耗**

- **新版本**：全面对齐最新 Android 生态（SDK 37 / AGP 9.3 / Compose BOM 2026.06 / 新 API 优先，禁用旧 API）
- **新特性**：安全加固设置、性能优化、按 WebApp 独立统计页（图表+错误导出）、组合快捷键
- **高性能**：埋点全异步不阻塞主线程、内存/缓存优化、白屏检测不误判
- **低损耗**：轻量实现（不引入重依赖）、统计落盘延迟批量、守护线程队列零内存泄漏

> 每一项改动以「新特性 + 高性能 + 低损耗」三把尺子衡量，不符合不出手。

**⚠️ 新 API 纪律（禁止过时 API）**：
- 全部使用当前稳定 API（SDK 37 / Compose BOM 2026.06），**禁用 deprecated 方法/类**
- 关键对照（实施时逐条核对）：
  - `onReceivedError` 用新签名 `(WebView, WebResourceRequest, WebResourceError)`，**禁用**旧签名 `(WebView, int, String, String)`
  - 异步用协程/Flow（已有传递依赖），**禁用** `AsyncTask`（API 30 已删）
  - 权限用 `ActivityResultContracts`（`registerForActivityResult`），**禁用** `startActivityForResult`（deprecated）
  - 存储用 DataStore（本计划 4.0），**禁用**新增 SharedPreferences 使用点
  - 编译期 0 deprecated 警告（`--warning-mode all` 验证），lint 通过 `NewApi`/`Deprecated` 检查

**🔒 常量与枚举收紧规范（减少魔法值 · 逐一注释）**：
- **常量唯一入口**：所有常量收进 `Const.java`（已有）或按域拆分的独立常量文件（如 `ZoomConst`/`StatsConst`）——**禁止在业务代码中散落魔法值**（50/100/200/150/1000ms 等）
- **枚举代替魔法值**：状态/类型类用 `enum class`（如错误类型 `ErrorType { HTTP, NETWORK, SSL, RENDER }`、UI 模式 `UiMode`），**禁止 int 魔法值代替枚举**
- **逐一注释**：每个常量/枚举项必须带注释说明含义与单位（如 `ZOOM_MIN_PERCENT = 50  // 字体/页面缩放下限（%）`）
- **命名规范**：常量 `UPPER_SNAKE`，枚举项 `UPPER_SNAKE`，带类型前缀（`ZOOM_`/`PERMISSION_`/`STAT_`）
- **本计划新增常量/枚举（实施时全部入文件）**：
  ```
  // ZoomConst（字体/页面缩放）
  ZOOM_MIN_PERCENT = 50    // 缩放下限（%）
  ZOOM_MAX_PERCENT = 200   // 缩放上限（%）
  ZOOM_STEP = 10           // 缩放步进（%）
  ZOOM_DEFAULT = 100       // 默认缩放（%）
  // StatsConst（统计）
  STAT_ERROR_LIMIT = 200   // 页面错误历史上限（条，按站过滤展示）
  // AppErrorConst（应用错误日志，独立于页面错误）
  APP_ERROR_LIMIT = 200    // 应用错误日志上限（条，超出丢最旧）
  APP_ERROR_DAYS = 3       // 导出应用错误日志天数（仅近 3 天）
  // ErrorType（错误类型枚举，页面运行/网络错误）
  enum class ErrorType { HTTP, NETWORK, SSL, RENDER }  // 四类 WebView 页面错误
  ```

## 🎨 全应用 UI 规范（统一 · 不可割裂）

**所有新页面/组件必须遵循 `design-system/webshell/MASTER.md`**（现有权威设计系统），禁止各自为政：

| 维度 | 规范 | 来源 |
|------|------|------|
| 色板 | M3 靛蓝 seed `#4F46E5`（Light `#4A47D6` / Dark `#C1BFFF`） | MASTER.md §Color |
| 字体 | 系统默认 + 现有 Typography 层级 | MASTER.md §Typography |
| 间距 | 8dp 基准网格，卡片内 16dp，页面 20dp | MASTER.md §Spacing |
| 形状 | 卡片 20dp 圆角、按钮胶囊、输入框 12dp | MASTER.md §Shape |
| 组件 | TopAppBar/Cards/Buttons/Inputs/SettingsRow 按现有 Compose 实现 | MASTER.md §Components |
| 设置行 | 统一 SettingsSwitchRow（标题+1 行 description+警示条） | 现有实现 |
| 弹层 | 统一 ModalBottomSheet（圆角 24dp 顶 + 拖拽把手 + navigationBarsPadding） | 现有长按菜单 |
| 对话框 | 统一 AlertDialog（标题+说明+取消/确认） | 现有实现 |
| 深浅色 | 全部走 AppTheme 自动切换，图表/卡片配色随主题 | 现有机制 |

**统一性红线**：
- 统计页（KPI 卡/图表/列表）、快捷键面板、安全设置区——**全部用同一套组件体系**（Card + SettingsSwitchRow + ModalBottomSheet + AlertDialog）
- 图表配色用色板 Primary/Secondary/Tertiary + Error（错误数据），不引入新色
- 反模式见 MASTER.md §Anti-Patterns（禁 emoji 图标、禁混搭风格、禁割裂视觉）

**验收**：新增页面截图与既有页面并排对比，视觉体系一致（色/形/距/字统一）。

## 现状审计（有代码依据）

| 项 | 现状 | 依据 |
|----|------|------|
| WebView 安全 | `setAllowFileAccess(true)` 开着（风险）；`setMixedContentMode` 注释；Safe Browsing 显式关闭 | WebViewActivity.java:187-192、Manifest:17-18 |
| 错误捕获 | 仅 SSL/渲染崩溃/HTTP 认证；**缺 onReceivedError / onReceivedHttpError** | WebViewActivity.java:1037-1091 |
| 权限 | 6 个（INTERNET/LOCATION×2/CAMERA/MODIFY_AUDIO/RECORD_AUDIO），uses-feature required=false 正确 | Manifest:5-15 |
| 统计 | 无任何统计模块 | 全工程搜索 |
| 图表库 | 无 | build.gradle |
| 数据层 | SharedPreferences（WEBSITEDATA/GENERAL_INFO） | DataManager.java:35-36 |
| WebView 实例 | 多实例（三指切换依赖，standard launchMode） | Manifest:52、WebViewActivity:353-363 |

---

## Phase 1：安全加固（设置项 + 小标提示）

### 新增「安全」设置区（全局 + WebApp 两级，默认全开）

| 设置项 | 默认 | 实现 | 小标提示（1 行） |
|--------|------|------|------------------|
| 禁用文件访问 | ✅ 开 | `setAllowFileAccess(false)`（修复现状风险） | 防止恶意站点读取本地文件 |
| 禁用内容访问 | ✅ 开 | `setAllowContentAccess(false)`（新增） | 防止站点访问系统内容提供器 |
| 混合内容拦截 | ✅ 开 | `MIXED_CONTENT_NEVER_ALLOW`（当前注释掉） | 拦截 HTTP 内容混入 HTTPS 页面 |
| JS 弹窗限制 | ✅ 开 | `setJavaScriptCanOpenWindowsAutomatically(false)` | 防止站点自动弹出窗口 |
| 恶意网站防护 | ❌ 关（保持现状） | Safe Browsing 开关暴露 | 对非 HTTPS 站点可能显示警告，按需开启 |

- 说明：Safe Browsing 保持默认关（AGENTS.md 记录的既有设计：用户可添加非 HTTPS 站点）
- 实现：WebApp 模型加 `isSafeBrowsingEnabled` 等 5 个布尔字段（走 copySettings 合并，默认值 true/false 如上表）
- UI：全局设置页加「安全」分区，WebApp 设置页复用现有「安全与隐私」区，均带 1 行 description
- 依据：Zellic WebView 安全报告、OWASP MASTG-KNOW-0018

## Phase 2：性能与体验（不碰三指切换/缓存默认）

| 项 | 方案 | 说明 |
|----|------|------|
| 白屏检测 | 20s 超时 + 进度双条件 | onProgressChanged 长时间为 0 且超时才判定，AI 流式加载不误判 |
| 内存回收 | onTrimMemory 已有基础；页面切换按需 clearCache | 不启用 LOAD_CACHE_ELSE_NETWORK（AI 实时流会读缓存过期内容） |
| 键盘滚动 | imePadding 已有基础，优化输入框自动滚入可见 | 沿用现有 insets 机制 |

## Phase 3：权限审计

- 6 个权限全部有实际用途（站点请求走这些），uses-feature 正确——维持现状 + Manifest 加注释说明用途
- `MODIFY_AUDIO_SETTINGS` 为 normal 权限无需运行时申请——确认无多余声明
- 运行时权限补「永久拒绝」处理：拒绝后 shouldShowRequestPermissionRationale 判断，不再重复弹框

## Phase 3.5：导出应用错误日志（全局设置 · 备份区）

**需求**：全局设置 → 备份区（导出/导入下方）新增「导出应用错误日志」——导出**最近 3 天**的应用错误日志（供开发排查）。

**设计**：
- **入口**：备份区 SettingsActionRow（导出/导入之后，图标用错误/文档类）：
  ```
  导出设置与 Web App
  导入设置与 Web App
  ──────────────
  导出错误日志（近 3 天）   ← 新增
  ```
- **数据源**：DataStore `KEY_APP_ERRORS`（4.0 存储层）——**应用自身运行错误**（全局异常兜底 4.8 写入，与页面错误完全分离），按 `time >= now - 3天` 过滤
- **格式**：JSON 数组 `[{time, level, tag, message, stackTrace}]`（应用错误：级别/来源/堆栈；**与统计页页面错误文件不同构**——两类数据域独立，不互相导入）
- **文件名**：`WebNative_app_errors_YYYYMMDD.json`（导出当天日期；与统计页页面错误文件 `WebNative_errors_<站名>_*.json` 区分，一眼可辨类型）
- **实现**：SAF `ACTION_CREATE_DOCUMENT`（复用现有 export() 模式，已实测可用）→ 读 DataStore → 过滤 3 天 → 写 JSON → Snackbar 提示（成功/失败/无日志三态）
- **边界**：3 天内无错误 → 提示「近 3 天无错误日志」不创建空文件；导出不删除源数据（只读副本）

**依赖**：DataStore 落地（Phase 4.0）后实现——错误日志唯一存储源。

## Phase 4：统计页（按 WebApp 进入 · 开发者向）

### 4.0 全量 DataStore 替换（单一存储能力 · 不留技术债）

**决策**：按 Google 官方推荐（"consider migrating to SharedPreferences to DataStore"），**全量替换 SharedPreferences → DataStore**，同一种能力唯一化——一个项目只有一套存储方案。

**设计：单一 DataStore 存储层**（Repository 模式，官方架构指南）

```kotlin
// 单一入口：所有持久化走 AppStorage（顶层单例，DataStore 官方单例约束）
object AppStorage {
    val Context.dataStore by preferencesDataStore(name = "webnative_store")
    // 统一 key 定义（类型安全）
    val KEY_WEBSITES = stringPreferencesKey("websites")       // WebApp 列表 JSON
    val KEY_SETTINGS = stringPreferencesKey("settings")       // 全局设置 JSON
    val KEY_META = stringPreferencesKey("meta")               // 元信息（EULA/版本等）
    val KEY_PAGE_ERRORS = stringPreferencesKey("page_errors")  // 页面运行/网络错误历史 JSON（按站，统计页用）
    val KEY_APP_ERRORS = stringPreferencesKey("app_errors")    // 应用自身运行错误日志 JSON（全局，兜底写入）
    val KEY_STATS = stringPreferencesKey("stats")             // 统计明细 JSON
    val KEY_MAX_ID = intPreferencesKey("max_id")
    // 读写：Flow 异步 + updateData 事务（官方 API）
}
```

**设计模式抽象定义（独有 · 共性 · 安全 · 高效）**：

**① 单一职责（独有性）**：全项目**唯一**持久化入口 `AppStorage`——任何模块要读写数据只经它，禁止直接 new DataStore/SharedPreferences/文件存储。编译期通过 lint 规则（`NoDirectStorageAccess`）强制：仅 AppStorage 包内允许触碰 DataStore。

**② 存储门面（共性）**：`AppStorage` 之上提供**通用 Repository 接口**，所有业务仓库（WebAppRepo/SettingsRepo/StatsRepo）实现同一契约，调用方只依赖接口不依赖实现：

```kotlin
// 通用存储契约（所有数据仓库共用，杜绝重复实现）
interface StorageRepository<T> {
    val data: Flow<T?>                    // 读：Flow 异步
    suspend fun update(transform: (T?) -> T)  // 写：事务性更新
    suspend fun clear()                   // 清空
}
// 具体仓库只需声明 T 类型 + 序列化方式，CRUD 由基类提供（模板方法模式）
class WebAppRepository : StorageRepository<String> { ... }
class StatsRepository : StorageRepository<StatsData> { ... }
```

**③ 事务与并发（安全性）**：所有写操作走 `updateData`（原子读改写），**禁止**先读后写（read-then-write 竞态）；单例约束防多实例冲突；协程作用域限定（`SupervisorJob + Dispatchers.IO`）防取消泄漏。

**④ 批量与缓存（高效性）**：高频写（埋点/统计）走**内存缓冲 + 批量 flush**（模板方法内实现，仓库复用）；读走 `distinctUntilChanged` 防多余重组；序列化统一 Gson（现有依赖，不引入新序列化）。

**⑤ 扩展点（防未来重复）**：新数据需求（如未来加收藏/历史）→ 只新增 key + 一个 Repository 实现，**复用 StorageRepository 契约**——能力沉淀在抽象层，不重复造。

**替换范围（已摸清全部调用点）**：

| 现有 SP | 用途 | DataStore 迁移 |
|---------|------|---------------|
| `WEBSITEDATA` | WebApp 列表 + 全局设置 JSON | → `KEY_WEBSITES` + `KEY_SETTINGS` |
| `GENERAL_INFO` | 元信息（EULA/更新提示） | → `KEY_META` |
| `MAX_ID` | ID 分配 | → `KEY_MAX_ID` |
| `GLOBALSETTINGS`（legacy） | 旧版迁移 | 读兼容保留，写不再用（一次性迁移到新 key） |
| 备份导出/导入 | 文件读写 JSON | 改从 DataStore 读/写（同一数据源） |

**迁移策略（无感，不丢用户数据）**：
1. AppStorage 首次启动：读 DataStore → 空则从 SharedPreferences 一次性迁移（旧数据搬入新 key）→ 标记完成
2. DataManager 改为 AppStorage 门面（内部 DataStore 读写，对外接口不变）——调用方零改动
3. 备份导出/导入：数据源切到 AppStorage（格式不变，兼容旧备份）
4. 迁移完成后旧 SP 不再写入（避免双写），后续版本可清理

**性能与安全（官方特性）**：
- 异步 Flow：读写不阻塞主线程（高性能）
- `updateData` 事务：原子读改写，无并发竞争（安全）
- 类型安全 key + 协程取消支持（低损耗/新特性）
- 单例约束：顶层 `dataStore` 属性创建一次（官方强制）

**风险**：全量替换影响 DataManager 核心（13 处 SP 调用点）——通过「对外接口不变 + AppStorage 门面」隔离，回归测试覆盖备份/设置/统计全链路。

**采集点**：

| 指标 | 采集点 | 说明 |
|------|--------|------|
| 打开次数 | WebViewActivity.onCreate | ++ |
| 加载耗时 | onPageStarted→onPageFinished | 主体加载（不含 AI 流式生成） |
| 错误 | onReceivedError / onReceivedHttpError（补实现）/ SSL / RenderGone | 4 类，写入 DataStore 错误存储 |
| HTTP 缓存 | getCacheDir() 递归统计 | 全部站共享，显示总量 |
| 站点存储 | WebStorage.getUsageForOrigin | 每站 localStorage 等 |

**落盘策略**（统一 DataStore 通道，异步不阻塞主线程）：
- 所有持久化走 AppStorage（DataStore）——WebApp 统计字段/错误历史/明细全部 `updateData` 事务写入（Flow 异步，事务性原子）
- 内存缓冲（UI 即时一致）+ DataStore 异步落盘：StatsRecorder 队列触发写入，onPause/onDestroy 兜底 flush
- 不再有 SharedPreferences 写盘路径（全量替换后唯一存储）


### 4.1 数据采集

**字段归属说明（重要区分）**：
- **安全字段**（Phase 1）：走 copySettings 合并——全局模板控制默认，WebApp 可覆盖（沿用现有 isOverrideGlobalSettings 机制）
- **统计字段**（本 Phase）：**不参与 copySettings**——DataManager 合并逻辑在 `copySettings` 后单独保留 WebApp 自身统计值（与 textZoom/pageZoom 同款处理，代码模式已验证）

**WebApp 新增统计字段**：

```kotlin
var statLaunches: Int = 0        // 打开次数
var statLoadTimeSum: Long = 0    // 主体加载耗时累计 ms
var statLoadTimeCount: Int = 0   // 加载次数（均值）
var statMaxLoadTime: Long = 0    // 最慢加载 ms
var statCacheHttpBytes: Long = 0 // HTTP 缓存占用（cacheDir）
var statCacheStoreBytes: Long = 0// 站点存储（WebStorage）
var statErrors: Int = 0          // 错误计数
var statLastError: String? = null// 最近错误描述
var statFirstLoadedAt: Long = 0  // 首次使用（0 = 未使用，展示时处理）
var statLastUsedAt: Long = 0     // 最近使用
```

**页面错误历史存储**（多条记录，不只最近一条；**数据域：页面运行/网络错误**）：
- **并入统一 DataStore 存储层**（见下方「全量 DataStore 替换」——单一存储能力，不引入第二套存储）
- 结构：`{time, site, type, code, description}`，写入 `KEY_PAGE_ERRORS`，按站点过滤展示，上限 `STAT_ERROR_LIMIT`（200 条）
- 导出/导入 = 该站过滤后的完整列表（4.4）

**⚠️ 与应用错误分离（不混存）**：
- 应用自身运行错误（未捕获异常/崩溃）→ **独立 `KEY_APP_ERRORS`**（4.8 全局兜底写入，Phase 3.5 导出近 3 天）
- 两个存储域互不读写：页面错误不写 `KEY_APP_ERRORS`，应用错误不写 `KEY_PAGE_ERRORS`（防统计口径污染）

### 4.2 入口

主界面卡片 ⋮ 菜单：编辑 / **统计**（新增）/ 删除 → `WebAppStatsActivity(webappID)`（每站独立）

### 4.3 页面设计（Bento Grid · 移动端优先）

```
← 统计 · fanyi.baidu.com
├ KPI 卡 2×2：打开次数 / 平均主体加载 / 缓存 / 错误数
├ 加载耗时分布柱状图（Vico ColumnChart，按次，标注口径）
│   副信息：平均 · 最快 · 最慢
├ 缓存详情：HTTP 缓存总量 + 站点存储 + 清缓存按钮
├ 错误日志（该站全部页面运行/网络错误，KEY_PAGE_ERRORS 过滤）[导入] [导出]
│   条目：类型图标（HTTP 橙/网络红/SSL 黄/崩溃紫）+ 描述 + 时间
│   空状态：✅ 此站点无错误记录
└ 元信息：首次/最近使用 + 清空统计按钮
```

**交互确认设计**：
- 清缓存 / 清空统计按钮 → 点击弹确认对话框（AlertDialog：标题+说明+取消/确认），防误触丢数据
- `statFirstLoadedAt = 0`（旧数据未使用）→ 元信息显示「—」而非 1970
- 加载耗时分布：数据 < 2 次时显示「使用 2 次后展示分布图」（空态引导）

**设计要点**：
- KPI 卡大数字（28sp）+ 小标签（12sp），一屏看懂
- 图表全宽卡片、圆角 20dp、沿用 M3 靛蓝体系、深色自动适配
- 导入/导出按钮对称融入错误日志标题行（并排同组，带图标，不割裂）
- 加载耗时明确标注「主体加载耗时（不含流式内容生成）」
- 缓存双口径标注清楚（HTTP 缓存 vs 站点存储）

### 4.4 页面错误 导入 / 导出（统计页 · 对称闭环）

**数据域**：**页面运行/网络错误**（KEY_PAGE_ERRORS，按站）——与全局备份区的应用错误日志（KEY_APP_ERRORS，Phase 3.5）**完全独立，格式不同构，不互相导入**。

**导出**：
- 格式：JSON（该站错误记录 + 性能摘要）
- 文件名：`WebNative_errors_<站名>_YYYYMMDD.json`
- 实现：SAF `ACTION_CREATE_DOCUMENT`（复用备份导出模式，已实测可用）
- 成功 Snackbar 提示（NotificationUtils）

**导入**（读取此前导出的**页面错误**文件，查看/排查用）：
- 入口：统计页错误日志标题行，导入按钮与导出并排（对称设计，不割裂）
- 交互：SAF `ACTION_OPEN_DOCUMENT`（`application/json` 与 `*/*` 双过滤）→ 选择 `WebNative_errors_*.json`
- 解析校验：JSON 数组结构校验（`[{time, site, type, code, description}]`）；字段缺失/类型错误 → 整体提示「文件格式不正确」，不崩溃
- 展示：导入记录合并到错误日志列表（按 time 降序），条目标记「导入」来源 badge
- **只读不落盘**：导入数据仅展示层合并，不写 DataStore、不参与统计计数/图表，退出页面即失效（提供「清除导入」操作）
- 边界：损坏文件 → Snackbar「文件格式不正确」；空数组 → 「文件中无错误记录」；重复导入 → 按 `time + type + code` 去重合并
- 提示：导入文件必须为**页面错误**格式（WebNative_errors_*.json）；全局备份区导出的**应用错误**文件（WebNative_app_errors_*.json）结构不同，若误选 → 明确提示「这是应用错误文件，请在全局设置查看」

### 4.5 图表库选型

**Vico**（github.com/patrykandpatrick/vico）：Compose 原生 KMP 图表库，活跃维护。
依赖：`com.patrykandpatrick.vico:compose-m3`（版本以 libs.versions.toml 登记）

### 4.6 埋点异步抽象（不影响主功能 · 防泄漏）

**设计原则**：
- 埋点/统计**绝不阻塞主线程**——全部走独立单线程队列
- 与主功能解耦：埋点失败/异常不影响页面加载、导航、交互
- 生命周期安全：不持有 Activity/View 强引用（防内存泄漏）

**StatsRecorder 单例（Java，贴合现有 Thread 模式，不引入新依赖）**：

```java
public class StatsRecorder {
    private static final ExecutorService executor =
        Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stats-recorder");
            t.setDaemon(true);            // 守护线程：进程结束不阻塞
            return t;
        });

    // 弱引用：不持有 Activity 强引用，页面销毁不影响统计写入
    private static final WeakReference<Context> appContext =
        new WeakReference<>(App.getAppContext());

    /** 所有埋点统一走此方法：异步、安全、不抛异常 */
    public static void record(Runnable task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Exception ignored) {
                    // 埋点失败静默：不影响主功能
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 队列关闭/异常：丢弃本次埋点
        }
    }

    /** 页面加载完成埋点（示例调用方式） */
    public static void onPageLoaded(int webappId, long loadMs, String url) {
        record(() -> { /* 更新内存统计 + 标记待落盘 */ });
    }
}
```

**防泄漏要点**：
- `WeakReference<Context>`：不持有 Activity/Context 强引用（应用上下文生命周期最长，弱引用更保险）
- 守护线程 + 单线程队列：进程退出自动结束，无并发竞争
- 埋点内不 touch View/Activity：只操作 DataManager 内存对象 + SharedPreferences
- 落盘时机：onPause/onDestroy 由主线程触发 `record()`，队列串行执行写盘

**调用点（全部异步，不阻塞）**：
| 事件 | 埋点 | 是否阻塞 |
|------|------|---------|
| 打开 | `StatsRecorder.record(() -> statLaunches++)` | ❌ 异步 |
| 加载完成 | `onPageLoaded(id, ms, url)` | ❌ 异步 |
| 错误 | `recordError(id, type, code, desc)` | ❌ 异步 |
| 退出落盘 | onPause/onDestroy → `record(saveTask)` | ❌ 异步 |
| 清缓存 | 统计页按钮 → `record(clearCacheTask)` | ❌ 异步 |

**验证**：埋点线程池在 Profiler 中无主线程阻塞；连续操作 100 次埋点无 ANR/无泄漏（LeakCanary 或 dumpsys meminfo 对比）。

### 4.7 异步异常防护与数据一致性（防数据遗漏）

**核心原则：数据分层——关键数据可靠，非关键数据尽力而为**
| 数据类别 | 示例 | 保存策略 | 失败处理 |
|---------|------|---------|---------|
| **用户关键数据** | 设置/WebApp 配置/快捷方式 | **主线程同步**（现有 `apply()`：内存立即一致 + 磁盘异步，Google 推荐） | apply 不抛异常；配置变更后 onResume 重读保证 UI 一致 |
| **统计非关键数据** | 埋点/错误日志 | **异步队列尽力而为** | 见下方三重兜底 |
| **用户主动操作** | 清缓存/清空统计 | 异步执行 + **主线程回调确认** | 失败 → Snackbar 明确提示，不静默 |

**异步失败三重兜底（防统计遗漏）**：

1. **内存缓冲（UI 永远一致）**：统计更新先写内存（WebApp 字段/内存 Map），统计页读取走主线程 DataManager——**UI 显示内存最新值，不依赖磁盘**；即使落盘失败，本次会话内数据完整
2. **失败重试**：落盘任务失败 → 数据保留在内存缓冲 → 下一个落盘时机（onPause/onDestroy/下一条埋点触发）自动重试，不丢弃
3. **崩溃恢复**：onDestroy 时若缓冲非空 → 尝试**同步 flush**（短超时，如 100ms）；进程被杀无法 flush → 缓冲数据本次丢失（**可接受：统计非关键**，已在风险披露）

**UI 一致性机制**：
- 统计页进入时 `onResume` → `loadAppData()` + 刷新（复用现有 refreshTrigger 模式）——展示磁盘+内存合并后的最新数据
- 异步落盘不影响读取：读取永远走主线程内存，无并发读写竞争（写是单线程队列串行）

**关键操作反馈（不静默）**：
- 清缓存/清空统计：异步执行 → 完成回调主线程 → Snackbar「已清空」/「清理失败，请重试」
- 导出错误日志：SAF 保存结果同步返回（现有备份导出模式），失败已有提示

**验证**：T8 强停后统计不丢（onPause 落盘）+ 新增 T14「落盘失败模拟」：注入写盘异常 → 统计页数据仍在（内存缓冲）、下次重试成功。

### 4.8 全局异常兜底规范（任何逻辑块异常不崩溃）

**现状缺口（已扫描）**：Compose 层 3 文件（WebAppSettingsScreen/MenuOverlay/SettingsScreen）**0 异常覆盖**、**无全局 UncaughtException 兜底**——崩溃风险点。

**分层异常兜底（每层各司其职）**：

| 层 | 策略 | 实现 |
|----|------|------|
| **全局兜底** | 未捕获异常不崩溃，记录后重启 | `Thread.setDefaultUncaughtExceptionHandler`：写**应用错误日志**（含堆栈，`KEY_APP_ERRORS`，上限 `APP_ERROR_LIMIT`）到 DataStore + **不写统计页页面错误**（两类数据域分离）+ 优雅 finish 当前 Activity（不弹系统"已停止"） |
| **UI 回调层** | Compose 事件回调全部 try-catch | 点击/滑动/滑杆回调包 `runCatching`（Kotlin）或 try-catch（Java），异常 Toast/日志不崩溃 |
| **数据层** | DataManager/AppStorage 读写异常 | try-catch + 返回安全默认值（空列表/默认配置），不抛给 UI |
| **异步层** | StatsRecorder 队列任务 | 已设计（4.6）：任务内 try-catch + RejectedExecutionException 兜底 |
| **WebView 层** | JS 注入/加载回调 | `evaluateJavascript` 回调 try-catch + onRenderProcessGone 已有（优雅关闭） |

**关键规则（所有新代码强制）**：
1. **禁止 `!!` 非空断言**（Kotlin）——用 `?.` + `?:` 安全默认值（现有代码已无 `!!`，保持）
2. **禁止 `orElseThrow`/`get()` 直接抛**——返回默认值或空
3. 所有 IO/网络/JSON 解析包 try-catch（Gson 解析失败返回默认对象）
4. 回调（Compose onClick/WebView 回调）内异常**必须捕获**——UI 线程异常 = 崩溃
5. 全局兜底是最后防线，正常路径靠 1-4 拦截

**验证**：T23 注入各层异常（UI 点击抛错/数据解析坏 JSON/异步任务异常）→ 应用不崩溃、错误被记录、UI 正常。

## Phase 5：组合快捷键（页面独有快捷键发送）

### 5.1 需求与原理

**需求**：WebView 小菜单支持「组合快捷键」——用户自定义组合键（Ctrl+S、Shift+S、Ctrl+Shift+S 等），发送到当前页面，**触发页面的独有快捷键逻辑**（如 kimi code 的 Ctrl+S 插入内容），**不触发浏览器/系统默认行为**（如 Ctrl+S 保存页面）。

**技术原理（已确认可行）**：
- 页面快捷键本质是 `keydown` 事件监听（[JS 指南](https://javascript.plainenglish.io/simple-way-to-add-keyboard-shortcuts-in-your-web-app-no-hassle-87ec43d12716)：`preventDefault()` 拦截默认）
- **JS 合成 KeyboardEvent**（`new KeyboardEvent('keydown', {key, ctrlKey, shiftKey, bubbles:true})` 派发到 activeElement/document）——**合成事件天然不触发浏览器默认行为**，只会被页面监听器收到 → 完美契合"只激活页面快捷键"
- 外接键盘真实按键：WebView `dispatchKeyEvent` 拦截 → 转 JS 合成派发（避免浏览器默认）

### 5.2 交互设计（参考 ShortcutRecorder / KeyboardShortcuts 模式，操作易用）

**入口**：长按小菜单 → 新增「快捷键」项 → 打开快捷键面板（ModalBottomSheet，与菜单同风格）

**面板结构**：
```
┌─────────────────────────────────────┐
│  ⌨️ 快捷键                    [完成] │
│  发送到当前页面的组合键              │
│  ┌───────────────────────────────┐  │
│  │ [发送] Ctrl+S     (点按即发送) │  │
│  │ [发送] Ctrl+Shift+S           │  │
│  │ [发送] Shift+S                │  │
│  │ [＋ 添加组合键]                │  │
│  └───────────────────────────────┘  │
│  ⓘ 组合键只发送给页面，不触发      │
│     浏览器默认功能                  │
└─────────────────────────────────────┘
```

- **已绑定列表**：每条左侧「发送」按钮（点击即向页面派发该组合键）、右侧删除
- **添加（录制模式）**：点「＋ 添加」→ 按钮变「请按下组合键...」→ 用户按组合键（外接键盘）→ 捕获显示（如 `Ctrl+Shift+S`）→ 确认绑定
- **操作反馈**：发送成功 Toast「已发送 Ctrl+S」；录制支持 Ctrl/Shift/Alt + 字母/数字/功能键
- **限制**：每条 WebApp 最多 5 个组合键（防冗余）；重复绑定提示

### 5.3 存储与实现

- 存储：WebApp 新增字段 `keyShortcuts: List<String>`（格式 `"Ctrl+S"`，每站独立，**不参与 copySettings 合并**——同统计字段处理）
- 发送实现：`evaluateJavascript` 合成 KeyboardEvent 派发：
  ```js
  var t = document.activeElement || document.body;
  t.dispatchEvent(new KeyboardEvent('keydown', {key:'s', ctrlKey:true, shiftKey:false, bubbles:true}));
  t.dispatchEvent(new KeyboardEvent('keyup', {key:'s', ctrlKey:true, shiftKey:false, bubbles:true}));
  ```
- 外接键盘拦截：WebViewActivity `dispatchKeyEvent` → 匹配已绑定组合 → 转 JS 派发 + `return true`（吞掉，不触发浏览器默认）
- 解析：`Ctrl+S` → `{key:'s', ctrlKey:true}`；`Shift+S` → `{key:'S', shiftKey:true}`（大写自动）

### 5.4 验证

- 模拟器 + 外接键盘（`adb shell input keyevent` 模拟 Ctrl 组合）：配置 Ctrl+S → 按真实键 → 页面收到 keydown（kimi code 场景触发插入）
- 小菜单「发送」按钮 → 页面收到合成事件
- 浏览器默认不触发（Ctrl+S 不弹保存页）
- 每站独立、持久化、深浅色正常

## 测试矩阵（模拟器全量 · android-emulator-adb skill）

### 功能测试

| # | 场景 | 操作 | 预期 | 日志检查 |
|---|------|------|------|---------|
| T1 | 安全设置默认值 | 新装后查设置 | 文件/内容/混合/JS弹窗=开，SafeBrowsing=关 | 无异常 |
| T2 | 安全开关生效 | 关文件访问→开含本地资源的站 | 本地资源加载失败（符合预期） | ERR_ACCESS_DENIED |
| T3 | 白屏检测 | 断网打开站点 | 20s 后自定义错误页+重试 | 无 FATAL |
| T4 | 白屏不误判 | 打开 AI 流式页面 | 20s 内进度在动→不判定 | 无误报 |
| T5 | 内存回收 | 连续开关 10 次站点 | 内存无累积（首尾对比） | dumpsys meminfo |
| T6 | 权限永久拒绝 | 拒绝相机→再请求 | 不再弹系统框，提示手动开启 | 无重复请求 |
| T7 | 统计采集 | 访问 5 次（含 1 慢加载 1 错误） | 打开/加载/错误计数正确 | 埋点日志 |
| T8 | 统计落盘 | 强停后重启 | 统计不丢（onPause 落盘） | WEBSITEDATA |
| T9 | 页面错误导出 | 触发 3 类页面错误→统计页导出 | JSON 完整含该站全部页面错误 | 导出成功 Snackbar |
| T10 | 清缓存/清统计 | 点按钮→确认 | 数据归零，确认框防误触 | 无异常 |
| T11 | 空态展示 | 新站无数据 | KPI 显示「—」、图表空态引导 | 无崩溃 |
| T12 | 深浅色 | 切换主题→统计页 | 图表/KPI 配色适配 | 无异常 |
| T13 | 埋点性能 | 连续操作 100 次 | 无 ANR、主线程无阻塞 | Profiler |
| T14 | 落盘失败兜底 | 注入写盘异常 | 统计页数据仍在（内存缓冲）、下次重试成功 | 无崩溃 |
| T15 | 快捷键绑定 | 录制 Ctrl+S/Ctrl+Shift+S | 列表显示、持久化、每站独立 | 无异常 |
| T16 | 快捷键发送（按钮） | 小菜单点「发送」 | 页面收到 keydown（合成事件） | 无浏览器默认 |
| T17 | 快捷键发送（真实按键） | adb 模拟 Ctrl+S | 页面收到、浏览器默认不触发 | dispatchKeyEvent 拦截 |
| T18 | 存储迁移（旧→新） | 装旧版→装新版 | 旧数据无感迁入 DataStore（WebApp/设置/统计） | 无丢失 |
| T19 | 存储唯一性 | 全量操作后检查 | 无 SharedPreferences 写入残留（仅 DataStore） | grep 验证 |
| T20 | 备份兼容 | 旧备份导入 | 格式不变，数据正常还原 | 无异常 |
| T21 | 存储抽象契约 | 单测注入 StorageRepository | 各仓库 CRUD/事务/clear 行为一致 | 无竞态 |
| T22 | 存储访问管控 | lint 扫描 | 无 DataStore 直连（仅 AppStorage 包） | lint 规则 |
| T23 | 异常兜底 | 注入各层异常（UI/数据/异步） | 应用不崩溃、错误被记录、UI 正常 | 全局兜底日志 |
| T24 | 导出应用错误日志（3天） | 触发应用异常（崩溃兜底）→备份区导出 | JSON 含近 3 天应用错误、无空文件、三态提示正确 | 无异常 |
| T25 | 魔法值检查 | lint/代码扫描 | 无散落魔法值（全部入常量文件+注释）、枚举替代 int 状态 | lint 规则 |
| T26 | 错误日志导入 | 统计页导入合法文件→合并展示；损坏文件→友好提示 | 合法文件按 time 合并+「导入」badge、损坏文件不崩溃、导入不污染统计计数 | 无异常 |

### 回归测试（确认不破坏既有功能）

| # | 场景 | 预期 |
|---|------|------|
| R1 | 三指切换站点 | 正常（未动多实例） |
| R2 | 长按菜单滑杆+保存 | 正常（异步埋点不干扰） |
| R3 | 设置页全部开关 | 正常 |
| R4 | 备份导出/导入 | 正常 |
| R5 | 深色/浅色/跟随系统 | 正常 |
| R6 | 快捷方式创建/删除 | 正常 |

### 埋点持续完善机制

- 测试过程中（T7/T8/T9）**发现统计缺失或口径不准 → 立即补埋点/修口径**，同步更新本文档
- 每次测试跑完检查：①埋点数据与 UI 一致 ②埋点未引入 ANR/泄漏 ③统计页展示完整
- 埋点位置以"对开发者有意义"为准绳，测试中验证每个埋点确实产生有用数据，无用的移除

## 实施顺序与验证（目标版本 v2.1.0 / versionCode 2100）

| Phase | 内容 | 验证 |
|-------|------|------|
| 1 | 安全加固 | 设置项开关生效（模拟器 dumpsys WebSettings 验证）、默认值正确 |
| 2 | 性能体验 | 断网触发白屏检测、连续开关 10 次站点内存无累积 |
| 3 | 权限审计 | 拒绝相机后不再重复弹框 |
| 3.5 | 导出错误日志 | 制造错误→导出近 3 天 JSON 正确、三态提示 |
| 4 | 统计页 | 访问 5 次（含 1 慢加载 1 错误）→ 数据正确；导出 JSON 完整；深浅色正常 |
| 5 | 组合快捷键 | 绑定+发送（按钮/真实键）→ 页面收到、浏览器默认不触发 |

每 Phase 完成即提交（分段）+ 模拟器实测 + 日志监控（android-emulator-adb skill）。
全部 Phase 完成后：版本号升 2.1.0（versionCode 2100）→ dev→main→tag 发版（沿用现有流程）。

## 关键参考文档（开发时反复查阅）

### 官方文档（Google）
| 主题 | 链接 | 用于 |
|------|------|------|
| DataStore 存储 | https://developer.android.google.cn/topic/libraries/architecture/datastore | 4.0 存储层（异步/事务/单例/corruption 处理） |
| R8 优化 | https://android-developers.googleblog.com/2025/11/use-r8-to-shrink-optimize-and-fast.html | 体积优化 |
| 优化资源压缩 | https://android-developers.googleblog.com/2025/09/improve-app-performance-with-optimized-resource-shrinking.html | AGP9 资源压缩 |
| Android 16 迁移 | https://android-developers.googleblog.com/2025/01/orientation-and-resizability-changes-in-android-16.html | API 36 兼容 |
| 协程最佳实践 | https://developer.android.com/kotlin/coroutines | 埋点异步 |
| 后台任务选型 | https://developer.android.com/develop/background-work | 异步/WorkManager 边界 |

### 安全参考（WebView 加固）
| 主题 | 链接 | 用于 |
|------|------|------|
| OWASP WebViews | https://mas.owasp.org/MASTG-KNOW-0018/ | Phase 1 安全基线 |
| Zellic WebView 安全 | https://www.zellic.io/blog/webview-security | 注入防护 |
| WebView 漏洞加固 | https://blog.securelayer7.net/android-webview-vulnerabilities/ | 安全设置项依据 |

### WebView 优化
| 主题 | 链接 | 用于 |
|------|------|------|
| WebView 优化系列（缓存/复用/秒开/白屏） | https://juejin.cn/post/7143025767268810759 | Phase 2 性能 |

### 图表库
| 主题 | 链接 | 用于 |
|------|------|------|
| Vico（Compose 图表） | https://github.com/patrykandpatrick/vico | 4.5 图表选型 |

### 快捷键设计参考
| 主题 | 链接 | 用于 |
|------|------|------|
| 页面快捷键 JS 实现 | https://javascript.plainenglish.io/simple-way-to-add-keyboard-shortcuts-in-your-web-app-no-hassle-87ec43d12716 | Phase 5 原理 |
| ShortcutRecorder（录制 UI） | https://github.com/Kentzo/ShortcutRecorder | 5.2 交互设计 |
| KeyboardShortcuts（绑定列表） | https://github.com/sindresorhus/KeyboardShortcuts | 5.2 交互设计 |

### CI 优化参考
| 主题 | 链接 | 用于 |
|------|------|------|
| GitHub Actions 提速实战 | https://github.com/orgs/community/discussions/204825 | CI 并行/缓存 |
| Gradle 性能 | https://docs.gradle.org/current/userguide/performance.html | daemon/缓存 |

## 风险与披露

- 统计字段不参与全局合并（否则被覆盖）——DataManager 合并逻辑需单独保留（与 textZoom/pageZoom 同款）
- 缓存口径非精确（HTTP 缓存为目录估算）——标注清楚
- Safe Browsing 保持默认关（既有设计，不强制）
- 不启用 LOAD_CACHE_ELSE_NETWORK（AI 实时流一致性优先）
- 页面错误历史独立存储（DataStore `KEY_PAGE_ERRORS`）上限 200 条——防无限增长，超出丢最旧
- 应用错误日志独立存储（DataStore `KEY_APP_ERRORS`）上限 200 条——与页面错误分离，防统计口径污染
- 旧用户 WebApp 无统计字段（Gson 默认 0）——展示层处理 0 值（显示「—」/空态），无需数据迁移
- 埋点异步队列：守护线程 + WeakReference——若应用被杀，队列中未落盘统计可能丢失（可接受：统计非关键数据）
- 埋点上限：页面错误 200 条 + 应用错误 200 条 + 单线程队列——防内存/存储膨胀
- 快捷键依赖页面自身实现 keydown 监听——页面无此逻辑时发送无效果（预期行为，非 bug）
- JS 合成 KeyboardEvent 的 `isTrusted=false`——部分严格校验 `isTrusted` 的站点（银行/验证码类）会忽略合成事件，属已知限制（与 WebBridge 同类限制）
- DataStore 单例约束：同一文件同进程仅一个实例（官方强制）——顶层属性创建一次，跨文件共享
- 新增依赖：`datastore-preferences:1.2.1`（官方稳定版）+ kotlinx-coroutines（已由 Compose 传递引入）——符合"新特性"编码目标，不引入重依赖
- 导入页面错误文件为只读展示层合并（不落盘）——退出页面即失效，属预期设计（防污染统计口径），文件本身保留可反复导入
- 两类错误文件格式不同构（页面错误 `{time,site,type,code,description}` vs 应用错误 `{time,level,tag,message,stackTrace}`）——误选另一类文件会明确提示，不崩溃
- **全量替换风险**：DataManager 13 处 SP 调用点改造——通过 AppStorage 门面（对外接口不变）隔离，回归覆盖备份/设置/统计全链路；旧 SP 一次性迁移（无感不丢数据），迁移完成不再双写
