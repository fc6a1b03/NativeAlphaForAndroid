# Google 官方编码规范（项目级·含红线）

> 来源：Google Android Kotlin Style Guide + Kotlin Coding Conventions。
> 适用于本项目（WebNative/Android App， **100% Kotlin**，无 Java 源文件）。

---

## 0. 官方文档索引（编码目标：新版本 · 新特性 · 高性能 · 低损耗）

| 主题               | 地址                                                     |
|--------------------|----------------------------------------------------------|
| Google Kotlin 风格 | https://developer.android.com/kotlin/style-guide         |
| Kotlin 官方约定    | https://kotlinlang.org/docs/coding-conventions.html      |
| Android 开发者总览 | https://developer.android.com/docs                       |
| Android 架构指南   | https://developer.android.com/topic/architecture         |
| Compose            | https://developer.android.com/develop/ui/compose         |
| 性能优化总览       | https://developer.android.com/topic/performance          |
| Gradle 用户手册    | https://docs.gradle.org/current/userguide/userguide.html |
| AGP 构建文档       | https://developer.android.com/build                      |

取向：优先采用新版本稳定特性（Kotlin 2.3 / AGP 9 / Compose BOM 2026.06），同等实现选低损耗方案（懒初始化、集合容量预估、避免无效拷贝）。

---

## 1. 十三条红线（违反即打回）

### R1. 禁止硬编码与魔法值

- 任何字面量（数字/字符串/路径/URL）进入逻辑即魔法值；必须常量定义后引用
- 用户可见文案一律 `R.string`（en + zh 双语）；颜色/尺寸走 `res/values`
- 版本号只允许出现在 Version Catalog 与 `app/build.gradle` 的 `defaultConfig`

### R2. 常量/枚举必须归位

- 禁止在业务逻辑 kt 文件里堆常量/枚举定义
- 按功能归属放入独立 kt 文件（如 `Const.kt` 管全局键值、`ErrorType.kt` 管错误枚举）
- 纯公共跨模块内容：全局唯一常量文件/枚举文件，禁止多处重复定义同名常量

### R3. 禁止重复逻辑与超长实现

- 同一逻辑两处实现即重复——抽出独立 kt（工具对象/设计模式类），统一对外开放、统一维护
- 单方法建议 ≤80 行、单文件建议 ≤600 行；超限必须拆分（按职责分文件）
- 「图标能力只在 WebAppIconManager」是范式：能力唯一实现处，调用方只编排

### R4. 禁止嵌套循环/深度递归/嵌套 if

- 嵌套 ≤2 层：第三层必须用集合操作（`map/filter/flatMap/associate`）、提前 return、或设计模式（访问者/策略/责任链）消解
- 深递归改迭代或用 `Sequence` 惰性处理；无法消解的极端场景须注释说明依据

### R5. 禁止 Java 文件

- 实现以 Kotlin 为主；仓库不新增任何 `.java` 文件（现有 0 个，保持为 0）

### R6. 注释强制 + 禁止空行分段

- 任何实现必须有有效易懂注释：公共 API 用 KDoc，逻辑块用行注释说明「为什么」
- 代码之间 **禁止空行分段**——分段只能用注释（空行仅允许出现在 import 组间与顶层声明间）

### R7. 禁止全限定类名内联

- 禁止在逻辑体内写 `com.xxx.SomeClass.method()`；必须顶部 `import` 后直接使用
- 唯一豁免：`import` 会产生命名冲突时，顶部声明别名 `import a.B as Alias`

### R8. 附属逻辑不得阻塞主线程

- 埋点、日志、统计、IO、网络等非主流程逻辑：一律独立线程/协程（`Dispatchers.IO`）
- 协程作用域必须挂接生命周期（`lifecycleScope`/`rememberCoroutineScope`），禁止裸 `GlobalScope`
- 共享可变状态必须线程安全（`@Volatile`/`AtomicBoolean`/`Mutex`/不可变快照），主线程只读快照

### R9. 交付即绿

- 提交前 `./gradlew testDebugUnitTest lintDebug` 必须全绿；新增功能配单测
- 声称完成必须有运行证据（构建/测试/实测输出）

### R10. 禁止无节制反复赋值——不可变优先

- 变量默认 `val`；确需变更语义才用 `var`，且必须注释为什么不可变不适用
- 禁止「先声明空再逐步填充」的临时可变累积——用构造完备表达式/`buildList`/`apply` 块一次成型
- 同一变量在方法内被赋值 ≥2 次即坏味道：拆分为多个具名 `val`，让每次赋值承载独立语义
- 集合参数只读传递（`List` 而非 `MutableList`）；可变集合禁止跨方法/跨线程外泄

### R11. 内存最小颗粒控制——防泄漏、防非规占用

- 最小颗粒控制：位图/流/游标等重资源随用随关（`use{}`）；大对象作用域最小化（方法内局部，禁止提升为字段除非缓存必需）
- 生命周期绑定：Activity/Context 引用禁止进入静态/单例/伴生对象长生命周期（static leak）；回调/协程持外部引用必须在
  `onDestroy` 断链
- 集合容量预估：已知大小的集合构造时给初始容量（`ArrayList(n)`），避免扩容拷贝
- 泄漏自查项：`Handler`/`Runnable`、注册未反注册的监听、`GlobalScope` 裸协程、Compose 中强持有 Activity 的 state——出现即违规
- 禁止以「以后可能用到」为由缓存大对象；缓存必须有失效与上限策略（如 LruCache），否则不缓存

### R12. 分支流纪律——dev 开发，main 只进发布

- 开发提交一律进 `dev`；`main` 只接收 dev 验证后的合并（或发版 squash），**禁止直接向 main 提交功能代码**
- 发版路径固定：dev 全绿 → 合并 main → bump 版本 → tag `v*` → push 触发 CI Release
- 合并方向单一（dev → main）；发现 main 领先时先对齐 dev 再继续开发，不允许两侧并行漂移
- push 前确认分支：`git branch --show-current`——在错误分支上发现的提交必须撤回重做，不带病合入

### R13. 严禁残留技术债——复盘即清零

- 任何 `TODO` / `FIXME` / 已知 lint 警告 / 临时兼容代码 / 待清理的废弃 API 调用，都必须在当轮迭代内计划消除或给出明确排期；禁止以「下一版再处理」为由合入 `dev`/`main`
- 每轮功能交付或 bug 修复结束时，强制反问：是否引入了新的技术债？同类代码是否还有相同问题？是否已同步更新规范/文档防止复发
- 违反本红线的代码不得进入远程仓库；已发现的技术债优先于新需求，先清零再开新工

---

## 2. 语言与构建（项目指引）

- Java 17 字节码，Kotlin 2.3.20（AGP 9 内置）；依赖版本唯一来源 `gradle/libs.versions.toml`
- Compose BOM 固定 `2026.06.01`（禁止升不兼容 beta）

---

## 3. Kotlin 代码风格（Google Kotlin Style Guide）

### 命名

- 类/对象大驼峰（`WebAppIconManager`）；函数/属性小驼峰（`saveIcon`）
- 常量 `UPPER_SNAKE_CASE`（`MAX_ENTRIES`）； **禁止匈牙利命名**（`mContext`/`strTitle`）

### 导入

- **禁止通配符 `.*`**——全量显式导入；排序：Android 平台 → androidx → 第三方 → 本项目
- 自动修复：Ctrl+Alt+O（Android Studio）/ ktlint `import-ordering`

### 注释

- 注释描述「为什么」不描述「做了什么」； **禁止 `TODO/FIXME` 挂技术债**
- 行尾 `//` 与代码间隔空格

### 布局/格式化

- 4 空格缩进（禁 Tab）；大括号 K&R；行宽 ~100；多行参数加尾随逗号

---

## 4. Android 特定规范（AOSP/Android 官方）

- 资源命名 `snake_case` 语义前缀；AndroidManifest 权限最小化、activity 逐项声明
- Activity 一个一职责；`onCreate` 必须 `setTheme` + `ThemeUtils.applyUiMode` + `applySystemBarColors(this)`
- Compose：`@Composable` 大驼峰、state 提升、`stringResource()` 组合期预取（禁回调内 `context.getString`）
- WebView 渲染优化（RenderPriority.HIGH + OffscreenPreRaster）不得回退
- Cookie 隔离走 `CookieSessionManager`（唯一入口）；头像走 `WebAppIconManager`（统一源）

---

## 5. Android XML 体系规范（XR1-XR8）

> 适用 `app/src/main/` 下全部 XML：AndroidManifest、layout、menu、values*、xml/、drawable；
> 与 proguard-rules.pro 的关联约定见 XR8。命名一律 `snake_case` + 语义前缀（XR2 表）。

### XR1. AndroidManifest

- **manifest 禁止再写 `package=` 属性**（AGP 7.3 起废弃）：包名唯一来源是 `app/build.gradle` 的 `namespace`（R 类生成）与 `applicationId`（安装标识）；现存 `package="com.cylonid.nativealpha"` 属存量债，下次触碰 manifest 时顺手删除；debug 构建走 `applicationIdSuffix ".debug"`，两套环境可并存
- `uses-permission` 逐条声明并按「网络 → 位置 → 相机 → 音频」分组；**权限最小化**——新增权限必须在 AGENTS.md「安全与隐私」节同步登记依据
- `uses-feature` 凡是站点授权的可选硬件（相机/麦克风/位置）一律 `android:required="false"`，避免无摄像头设备被商店过滤
- 每个 `<activity>` 必须显式声明 `android:exported`（intent-filter 的写 true，纯内部跳转写 false）；`theme`/`parentActivityName`/`launchMode` 逐项给出，不依赖默认值
- FileProvider 的 `authorities` 必须用 `${applicationId}.fileprovider` 占位符（debug/正式包名自动区分），禁止硬编码全限定包名
- meta-data（SafeBrowsing 开关、Samsung DeX keepalive）集中放 application 尾部并注释用途与出处链接

### XR2. 资源目录与命名前缀

| 目录 | 前缀 | 示例（项目既有） |
|------|------|------------------|
| `layout/` | `activity_` / `dialog_` / `item_` | `full_webview.xml`（历史名，新布局按前缀）、`dialog_http_auth.xml` |
| `menu/` | `wv_` / `menu_` | `wv_context_menu.xml` |
| `drawable/` | `ic_`（矢量图标）/ `animal_`（动画）/ `launch_`（启动）/ `webnative_`（品牌） | `ic_baseline_refresh_24.xml` |
| `values/colors.xml` | `md_theme_`（M3 语义色） | `md_theme_primary` |
| `values/themes.xml` | `AppTheme`（项目主题） | `AppTheme.Launcher` |
| `xml/` | 功能语义名 | `file_paths.xml` |

- values 变体目录规则：`values-night/`（深色）仅放**覆盖差异项**，不复制全量；`values-v31`/`values-night-v31` 仅放 API 31+ 的主题项（如 splashscreen 属性）；`values-zh/` 与 `values/` 的 string key 必须一一对应（缺 key 落英文默认）
- 一图多分辨率走 `mipmap-anydpi-v26`（自适应图标 XML）+ `mipmap-*dpi`（位图回退），不新建 `drawable-*dpi`

### XR3. 布局 XML（View 体系）

- 本项目 View 体系布局仅两个（`full_webview.xml` 承载 WebView 渲染核心、`dialog_http_auth.xml`）；**新页面一律 Compose**，禁止新增传统布局——WebViewActivity 不 Compose 化是架构决策（AGENTS.md 坑 1）
- 根布局声明全部命名空间（`xmlns:android` 必需；`xmlns:app`/`xmlns:tools` 仅用到时声明）；`tools:` 只用于设计期属性（`tools:ignore`/`tools:text`）
- 控件顺序：`android:id` → 尺寸（`layout_width/height`） → 行为属性 → 外观属性 → `tools:` 属性
- 禁止 `android:text` 硬编码字面量（lint `HardcodedText` 直接打回）——一律 `@string/`；装饰性 `View` 的 `android:text=""` 例外（如 `anchorCenterScreen` 锚点）
- `android:contentDescription` 必填（纯装饰填 `@null` 并加 `importantForAccessibility="no"`，参照 `loadingAnimal`）
- `full_webview.xml` 的层级结构（WebView ×2 + ProgressBar + loadingBg + 动画 IV + 锚点 TV）是启动链路一部分——**改动必须评估对首帧的影响**（loadingBg 防深色白屏、动画 visibility=gone 按需显示），禁止「顺手优化」
- `tools:ignore` 必须附注释说明为什么豁免（如 `MissingConstraints`：FrameLayout 布局无需约束）

### XR4. menu / xml 资源

- menu item 的 `title` 一律 `@string/`；分组用 `<group android:id>` 划分语义区（URL 区/操作区/更多区，参照 `wv_context_menu.xml`）
- 动态控制的 item 声明默认状态（`android:visible="false"`）由代码按需开启
- `xml/file_paths.xml` 这类配置型 XML：顶部注释说明用途与调用方（FileProvider 的 external-path→Download/）

### XR5. values 资源（strings/colors/themes）

- **strings**：key 用 `snake_case` 语义名（`shortcut_max_reached`）；带参格式用位置参数 `%1$s`/`1$d`（lint 会校验翻译完整性）；**禁止** `translatable="false"` 逃逸双语（应用名 `app_name` 等品牌词除外）
- **colors**：只放 M3 语义色板（`md_theme_` 前缀，成对 primary/onPrimary…）；页面级临时色写 `Color(0xFF...)` Compose 常量或补充语义色，禁止无前缀散色值；深浅两套在 `colors.xml` + `values-night/colors.xml` 成对维护
- **themes**：`AppTheme` 继承 `Theme.Material3.*.NoActionBar`；新属性项先查 M3 是否有语义 slot（colorSurfaceVariant 等）再决定加自定义 attr；Launch 主题（`AppTheme.Launcher`/`AppTheme.WebView`）的 windowBackground 是启动白屏/闪屏的关键，改动需实测冷启动
- 所有 values XML 修改后跑 `lintDebug`（MissingTranslation 等资源类 lint 会拦截缺翻译）

### XR6. drawable（矢量/动画）

- 图标一律 Material Symbols 24dp 矢量导出（`ic_baseline_*_24` 命名对齐），禁止位图图标（体积+密度适配）
- 动画：帧动画拆 `animal_walk1-4.xml`（单帧）+ `animal_walk_anim.xml`（animation-list 装配），**单帧与装配分离**是范式
- 自适应图标三件套：`webnative.xml`（anydpi-v26 前景/背景装配）+ `webnative_icon_bg/fg.xml`（矢量图层）

### XR7. tools 命名空间与 lint 抑制

- `tools:ignore` 仅允许出现在确有豁免理由的节点，且**同文件同理由集中注释一次**（本项目惯例：布局根节点后紧跟注释块）
- 代码内 `@SuppressLint` 同理——禁止文件级 `@SuppressLint("All")` 兜底
- 新增 lint 抑制必须在 PR/commit 说明里给依据（lint id + 为什么不修而豁免）

### XR8. R8/ProGuard 关联约定

- 规则文件唯一：`app/proguard-rules.pro`；每条 `-keep`/`-dontwarn` 必须带「为什么」注释（现有范式：Gson 反射/jsoup 缺失类/Compose 运行时/profileinstaller）
- **红线联动**：`com.cylonid.nativealpha.model.**` 全保留（Gson 反射依赖字段名——模型字段重命名=持久化破坏，AGENTS.md 坑 5）；新增 `@JavascriptInterface` 类必须手工加 keep（WebView JS 按名反射）
- 调整 keep 规则后必须 `assembleRelease` 实测（debug 不跑 R8，问题只在 release 暴露）

---


- Robolectric：`@Config(sdk = [34])`（targetSdk 37 > Robolectric 支持 ≤36）
- 测试方法名行为描述（`saveIcon_storesFileAndUpdatesPath`）；必须可跑（`testDebugUnitTest`）

---

## 6. 单测规范

- Robolectric：`@Config(sdk = [34])`（targetSdk 37 > Robolectric 支持 ≤36）
- 测试方法名行为描述（`saveIcon_storesFileAndUpdatesPath`）；必须可跑（`testDebugUnitTest`）

---

## 7. 已验证的坑（项目实践沉淀）

- coroutines-test 1.10.x 的 `Dispatchers.setMain/resetMain` 是 **test 模块的扩展函数**（kotlinx-coroutines-core 已无此 API），必须显式 `import kotlinx.coroutines.test.setMain/resetMain`，否则 Unresolved reference 且报错点会误导排查方向（实测 jar 反编译确认）
- Kotlin 位或用 `or` 关键字（`|` 有解析歧义——实测）
- `OutlinedTextFieldDefaults.colors` 参数名随 Material3 版本变化（避免新旧歧义参数）
- CRLF 文件用 Python `newline=''` 处理（Edit 工具的 LF 匹配可能失败）
- Gson 旧数据字段缺失 → getter 归一化（如 `sessionTabCount`/`textZoom`）
- `copyStatsAndShortcuts` 保留统计/快捷键字段（防保存清空）

---

执行：改动前对照本规范（尤其十三条红线）；提交前全绿 + 证据交付。
