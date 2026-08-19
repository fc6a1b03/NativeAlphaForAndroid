# NativeAlpha → Kimi Code Web Shell 改造计划（复盘定稿）

> 本文档是 NativeAlphaForAndroid 定制改造的完整复盘与执行计划。
> 状态： **全部完成**（阶段 0-8 已执行，待用户模拟器验收）
> 日期：2026-08-19
> 适用分支：dev

---

## 0. 执行准则（用户强调，优先级最高）

- **目标定位**：只为 PWA 而生，新特性、高性能、低损耗的应用。
- **当前用途**：主要用于 Kimi Code Web，**高频文本流渲染需要严苛优化**（流式输出、长文本滚动、代码块渲染）。
- **渲染优化核心**（阶段 5 落地）：
  - WebView `setRenderPriority(HIGH)`、硬件加速强制、禁软件层合成
  - `textZoom` 100 保真、标准字体族防降级
  - `onTrimMemory` 分级回收（已有）、流式输出时避免频繁 clearCache
  - `WebSettingsCompat.setOffscreenPreRaster` 预栅格化减少抖动
  - `onProgressChanged` 节流、`onPageFinished` 不重建 WebView
- **计划同步**：过程有补充/变更即时更新本文档，不遗漏不伪造。

---

## 1. 项目背景与目标

**现状**：Native Alpha 是一个通用 Web App 套壳应用（WebView 渲染任意网站 + 桌面快捷方式 + 多手势操作），单模块 Android
项目，Java/Kotlin 混合，View + XML 体系。

**目标**：定制为以 **首页自定义地址**为核心的通用 Web App 套壳（专为手机使用优化），面向 Kimi Code Web 等 AI
工具类页面提供最佳渲染与易用性体验。 **核心原则：不绑定、不预置任何固定地址**——地址完全由用户在首页自定义输入（沿用原功能）。

1. **大面积清理**：开屏画布/logo 去掉、冗余功能去掉、主题色采用当前最流行组合。
2. **全面版本升级**：不考虑旧 Android 兼容，以最新 Android API 37（SDK 37.1）为主轴，核心功能重构，目标"新特性 / 高性能 /
   低损耗"。
3. **基础功能不变**：主页面添加地址、桌面快捷方式直达全屏应用；但 UI/配置/设置做创新与改造。 **UI 按谷歌最新设计风格（Material
   3）完整重写**，包括交互细节——视觉、动效、组件全面对齐现代 Android 规范，核心功能逻辑不变，只优化易用性与展示性。
4. **列表显示名称**：新增地址后不再以 URL 作为列表显示，改为 **用户自定义描述字段**作为列表主显示名称（详见 §5A）。
5. **专业国际化**：所有用户可见文案提取为专业语言资源（ **中文 + 英文**，默认中文，设置内可切换），参考
   `json-helper/src/main/resources/messages` 的 bundle 规范（详见 §5A）。
6. **构建工具**：Gradle/AGP 可自由升级，无历史包袱约束。
7. **品牌策略**：应用自身 **不携带 kimi 标识**（应用名/图标/品牌色中性化）；"对 Kimi Code Web 绝对兼容与提升"是 **能力层**
   目标，与品牌无关。

---

## 2. 调研结论：版本盘面（全部经官方信源验证）

| 组件            | 当前    | 目标                                                                           | 信源                                                                                                           |
|-----------------|---------|--------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| Gradle          | 8.10.2  | **9.7.0**                                                                      | services.gradle.org 实测（2026-08-06 发布，当前稳定版）                                                        |
| AGP             | 8.8.2   | **9.3.1**                                                                      | Google Maven `maven-metadata.xml` 实测（9.4.0-rc01 为最新候选，未取）                                          |
| Kotlin          | 2.1.20  | **2.3.20**（AGP 9 内置）                                                       | kotlinlang.org（2.4.20 截至 2026-08-12 仍为 RC，不取）                                                         |
| compileSdk      | 35      | **37**                                                                         | 本地 SDK 已装 `android-37.1` platform，无需下载                                                                |
| targetSdk       | 35      | **37**                                                                         | 同上                                                                                                           |
| minSdk          | 28      | **31**（用户拍板：升，按最新版本来）                                           | 决策记录见 §3                                                                                                  |
| Build Tools     | 35.0.x  | **36.0.0**                                                                     | 本地已装                                                                                                       |
| JDK（构建运行） | 17.0.17 | **25**（本地 jdk-25.0.3）                                                      | Gradle 9.1.0+ 官方支持 Java 25；AGP 9.3 最低要求 17、运行于 25 兼容；**字节码目标保持 17**（Android 兼容基线） |
| NDK             | 未用    | 默认 **28.2.13676358**（AGP 9.3 默认）；本地已装 27.0.12077973 / 29.0.14033849 | 项目无 native 代码，仅登记备查；后续如需 native 能力可选用                                                     |

**关键事实**（AGP 9.3.0 官方 release notes 实测原文）：

> "The maximum API level that Android Gradle plugin 9.3 supports is API level 37."

- AGP 9.3 兼容表：Gradle 最低 9.5.0（默认 9.5.0）、Build Tools 36.0.0、JDK 17（最低要求）。
- Gradle 9.7.0 为当前最新稳定版；若与 AGP 9.3.1 出现兼容性报错，回退 Gradle 9.5.0。
- **JDK 25（LTS）**：Gradle 9.1.0 起官方支持 Java
  25（[Gradle 9.1 Release Notes](https://docs.gradle.org/9.1.0/release-notes.html)），Gradle 9.7 + JDK 25 可行。 **坑**：AGP
  9 内置 Kotlin 默认 KGP 2.2.10，对 JDK 25
  支持不完整（[Kotlin/Java 25 兼容讨论](https://discuss.kotlinlang.org/t/java-25-compatibility/30760)）——需在顶层 build
  显式声明 KGP **2.3.20**（Kotlin 2.3.0+ 支持 JDK 25）解决。字节码目标保持 17（Android 兼容基线）。
- **无固定入口地址**：Kimi Code Web 等 AI 工具页面的访问地址由用户自定义（如官方站点、或本地 `kimi web` 启动的 Web
  UI，地址因人而异）。 **本项目不预置/不硬编码任何站点地址**——首页自定义地址是核心功能（见 §3 D5）。

**NDK 说明**：本项目为纯 Java/Kotlin，无任何 native 代码，不编译 so 库。NDK 仅登记备查：AGP 9.3 默认 NDK 28.2.13676358；本地已装
27.0.12077973 / 29.0.14033849。 **如无 native 需求，不配置 `ndkVersion`，构建不受影响**。

---

## 3. 决策记录（含用户拍板内容）

| #   | 决策点       | 定案                                                                                                                                                                  | 依据/说明                                                                                                                                |
|-----|--------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| D1  | minSdk       | **31**（Android 12+）                                                                                                                                                 | 用户：升必须升，按最新版本来。动态色/SplashScreen/edge-to-edge 全部原生可用，无兼容分支                                                  |
| D2  | UI 架构      | **主界面/设置/对话框 Compose 化**（Material 3 + 动态色 + 自适应）；**WebViewActivity 核心渲染层保持 View 体系**，Compose 侧以 `AndroidView` 嵌入                      | 用户：官方建议就做。官方对 WebView 的标准路径就是 View 嵌入 Compose；WebViewActivity（1019 行）Compose 化风险大收益低                    |
| D3  | 包名         | `com.cylonid.nativealpha` **不变**                                                                                                                                    | 用户：不用动。包名仅为应用标识，数据兼容已放弃（D14），无迁移负担                                                                       |
| D4  | 品牌         | **不携带 kimi 标识**；应用名 **WebNative**（用户定名）；图标中性自适应（终端/对话气泡风格）；主题色 = 动态色优先 + Material 3 baseline 兜底 + 深色跟随系统             | 用户：应用名 WebNative；整个应用不需要 kimi 标识                                                                                        |
| D5  | 目标站点     | **不固定地址**：首页保留自定义地址能力（核心功能不变），首次启动引导用户自行添加；**不预置任何站点**                                                                  | 用户明确：不能固定地址，首页需可自定义                                                                                                   |
| D6  | 版本号       | 2.0.0（versionCode 2000）                                                                                                                                             | 大版本重构                                                                                                                               |
| D7  | 签名发版     | **GitHub CI 自动发版**：`key.properties` 读签名 + 无密钥回退 debug 签名 + GitHub Actions tag 触发自动签名发布（APK + AAB → Release）                                    | 用户：fork 到自己的仓库，CI 我来配，发布操作用户自己做（§8）                                                                             |
| D8  | 测试边界     | **以模拟器为主**（用户已装最新版 Android 虚拟机，走 IDEA 模拟器测试）+ Robolectric + 单测；装机目检兜底                                                               | 用户：先走 IDEA 虚拟机测试，已装可下载的最新版 Android 虚拟机                                                                           |
| D9  | 列表显示名称 | 新增 `displayName`（用户自定义描述）字段，作为列表主显示；URL 降级为次显示                                                                                            | 用户：新增地址后以地址作为列表不好，需加描述字段作列表主要显示名称；UI 由我设计（§5A）                                                   |
| D10 | 国际化       | **中英双语**（默认中文，设置内可切换）；所有用户可见文案外部化，禁止硬编码；键名规范参考 `json-helper` 的 `PrismBundle`（点分小写 + 双文件同步）                      | 用户：所有英文提取做专业语言管理，目前只需中英，默认中文可在设置切换；参考 `D:\project\fc6a1b03\json-helper\src\main\resources\messages` |
| D11 | 语言统一     | **统一 Kotlin**（Kotlin-first）：Java 存量 15 文件（2671 行）中保留的 8 个重写为 Kotlin，其余删除；删除不需要的语言文件；新增代码一律 Kotlin                          | 用户：java 和 tk 统一下不要太多语言，不需要的删除；保留部分必须重构遵守编码规范（§5C）                                                |
| D12 | gradle.properties | **按 AGP 9.3 / Gradle 9.7 官方最新配置优化**：JVM 参数调大、并行+缓存+配置缓存、清理旧 flags、关 jetifier | 用户：gradle.properties 按官方最新版本和特性配置优化（§6 阶段 1） |
| D13 | 依赖版本管理 | **Version Catalog 统一管理**：所有依赖版本收敛到根目录 `gradle/libs.versions.toml`，build 文件只引用不写死 | 用户：所有依赖版本号统一根目录文件管理，不散落 gradle/tk 文件；按官方最新要求（§5D） |
| D14 | 数据兼容     | **完全不考虑**：旧版 Native Alpha 直接卸载重装，不做任何数据迁移/兼容层；`DataVersionConverter`、`WebAppDeserializer`、`GlobalSettingsDeserializer`（v1.5.0 补丁）全部删除 | 用户：完全不考虑数据兼容，避免技术债；旧版直接卸载重装 |
| D15 | 备份/导出     | **功能保留，逻辑重写**：不做旧版数据导入兼容；全新 JSON 格式（版本化、可读、SAF 读写）导出/导入 Web App + 设置（§5E） | 用户：功能是需要的，但无需按之前的逻辑来做，不要兼容旧版本数据导入 |
| D16 | keystore     | **由我生成**：实施时用 keytool 生成 keystore 放项目根目录（git-ignored），产出 `KEYSTORE_BASE64` / 密码 / alias 等 Secrets 值交用户填仓库 | 用户：keystore 你直接弄就好了 |
| D17 | git 提交节奏 | **每阶段通过后提交一次**：每阶段 build + 测试门禁通过后提交一次，提交前向用户确认；历史清晰、可回滚 | 用户：每阶段通过后提交一次（推荐） |

---

## 4. 版本特性逐级对齐清单（API 31 → 37）

| API             | 平台特性                                       | 对本项目的强制影响                               | 落地动作                                         |
|-----------------|------------------------------------------------|--------------------------------------------------|--------------------------------------------------|
| 31 (Android 12) | 动态色 Material You、SplashScreen API          | 主题色动态化；开屏用官方 API                     | `dynamicColorThemeOverlay` / `core-splashscreen` |
| 33 (Android 13) | `POST_NOTIFICATIONS` 运行时权限                | 若保留通知则按需申请                             | 按需声明（当前无通知场景，观察）                 |
| 34 (Android 14) | edge-to-edge 过渡、预测性返回                  | `windowOptOutEdgeToEdgeEnforcement` 弃用         | `OnBackInvokedCallback` 支持                     |
| 35 (Android 15) | **edge-to-edge 强制**、16KB 页对齐             | 删 opt-out，真沉浸；无 native 代码，无 16KB 风险 | WindowInsets 全量接管                            |
| 36 (Android 16) | **大屏忽略方向锁 / resizeable 强制**           | `android:screenOrientation="portrait"` 失效      | **删除方向锁**（套壳场景无意义）                 |
| 37 (Android 17) | 大屏自适应强制不可退出、锁自由 MessageQueue 等 | 屏幕自适应保持（非重点，纯手机场景）             | 保留 `resizeableActivity` 默认；不强制做多列     |

**具体动作清单**：

- 删 `android:screenOrientation="portrait"`（manifest 与 build.gradle 中镜像声明同步）。
- 删 `android:windowOptOutEdgeToEdgeEnforcement`（themes.xml）。
- 全应用 edge-to-edge + WindowInsets 接管（状态栏/导航栏透明融合）。
- SplashScreen 官方 API 替换旧 `launch_screen.xml` 红底大 logo。
- 动态色主题 + Material 3 baseline 兜底 + 深色跟随系统（设置可手动切换）。

---

## 5. 渲染专项方案（"清晰直观一比一"五层）

目标：AI 工具类页面（流式输出、代码块、表格、暗色主题）是桌面级生产力界面，手机 WebView 必须清晰直观、一比一还原。
**所有站点相关策略均为"Web App 级可配置"**（每站独立开关，跟随用户自定义的地址生效）， **不硬编码任何域名**。

| 层      | 措施                                                                                                                             | 说明                                                             |
|---------|----------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------|
| 1. 引擎 | 硬件加速默认开；`setTextZoom(100)` 文字 1:1；DOM storage/database/标准字体族开启；`onTrimMemory` 分级回收缓存                    | 长会话不卡、文字不虚                                             |
| 2. 主题 | **每 Web App 独立配置 force-dark**（默认跟随系统；若站点自带暗色主题，用户可关掉 force-dark 防双重变暗失真）                     | 不做域名硬编码——站点千差万别，统一判断不可靠，交由用户按站点选择 |
| 3. 显示 | edge-to-edge 状态栏/导航栏透明融合；`displayCutoutMode=SHORT_EDGES` 刘海屏全屏                                                   | 页面底色顶到屏幕边缘，无黑边                                     |
| 4. 交互 | 剪贴板权限（AI 输出"复制代码"按钮依赖 `onPermissionRequest`）；双指缩放保留；文件上传；外链分流；SSL 安全处理                    | 保留现有 CustomBrowser/CustomWebChromeClient 手势与权限体系      |
| 5. 兼容 | **每 Web App 独立配置桌面 UA**（默认跟随系统 UA；站点需要桌面界面时用户按需开启）；cookie 持久化保登录态；PDF/附件下载后系统打开 | 实施时按用户添加的实际站点实测登录/渲染，必要时调 UA             |

**实施验收项**（装机实测，针对用户实际添加的站点）：

- 登录流程不被拦截；
- 流式输出不卡顿；
- 代码块复制功能正常；
- 手机渲染与桌面端视觉一致性截图对比。

---

## 5A. 新增功能设计（列表名称 + 国际化）

### 5A.1 列表显示名称（描述字段）

**现状问题**：新增地址后列表直接以 URL 显示，信息密度低、不友好。

**改造方案**：

- `WebApp` 模型新增 `displayName`（用户自定义描述）字段，作为列表 **主显示名称**；URL 降级为 **次显示**（辅助行，小号灰字）。
- 添加地址流程：输入 URL + 描述（描述可留空，留空时回退为原 URL 显示，兼容旧数据）。
- 旧数据兼容：已有 Web App 无 `displayName` → 回退 URL（Gson 缺失字段为 null，安全）。
- 列表 UI（Compose）：主行 `displayName`（若空则 URL）+ 次行 URL；图标仍为 favicon。
- **UI 设计（遵循 Material 3 + ui-ux-pro-max 设计资产，见 §5A.3）**：
    - 添加对话框：URL 输入框（主）+ "名称"输入框（辅助，可空）——名称字段带提示文案"给这个页面起个名字（可选）"；输入区聚焦边框高亮（M3
      OutlinedTextField）。
    - 列表项：两行式 `Card`（圆角 16、扁平低阴影、按压 scale 0.97）——主标题 = 名称/URL（`titleMedium`），副标题 = URL（
      `bodySmall` + `onSurfaceVariant`，仅当有名称时），favicon 圆形头像，尾部 overflow 菜单；入场 stagger 渐入（150-300ms，尊重
      reduced-motion）。
    - 编辑设置页：可修改名称（新增字段）。
    - 快捷方式标题：优先用名称，其次 URL。

### 5A.2 专业国际化（中英双语）

**目标**：所有用户可见文案外部化，禁止硬编码； **中文 + 英文**，默认中文，设置内可切换。

**方案**（参考 `json-helper/src/main/resources/messages` 的 `PrismBundle` 规范）：

- **资源组织**：
    - `res/values/strings.xml`（默认， **英文**）
    - `res/values-zh/strings.xml`（ **中文**）
    - 删掉现有 `values-de` / `values-es` / `values-it`（只保留中英）
- **键名规范**（对齐 PrismBundle）：点分小写语义化，如 `webapp.name` / `webapp.url` / `settings.language` /
  `dialog.add.title`；双文件同步添加，注释标明用途。
- **默认中文**：`defaultConfig` 设 `resConfigs "en", "zh"`（限制打包语言），应用默认语言跟随系统（中文系统 → 中文；非中文 →
  中文兜底，因默认中文）。设置内提供 **语言切换**：`AppCompatDelegate.setApplicationLocales`（androidx.appcompat 原生支持，API
  33+ 无重启切换；minSdk 31 兼容）。
- **语言切换 UI**：设置页「语言」分组，用 **M3 `SingleChoiceSegmentedButton`**（三选项：`跟随系统` / `中文` / `English`，默认
  `中文`——因应用默认中文）；切换即时生效（`setApplicationLocales` 无重启）。
- **存量清理**：全代码硬编码英文粗扫 101 处（`java/kt` 中 UI 文案字面量），全部提取到 strings.xml；`java` 中如
  `AboutActivity "Version "`、`WebViewActivity` 弹窗文案等逐个替换为 `getString()`。
- **数据层不翻译**：URL、Web App 名称/描述为用户数据，不参与翻译。
- **验证**：切换语言后全界面（主界面/设置/对话框/关于页/WebView 菜单）文案即时切换。

### 5A.3 UI 设计规范（ui-ux-pro-max 设计资产，新潮好看 + 易用方便）

> 设计系统已沉淀至 `design-system/webshell/MASTER.md`（ui-ux-pro-max 生成，后续实现以它为基准；页面级覆盖放
> `design-system/webshell/pages/`）。以下为 Android Compose 落地转译与定案。

**设计双目标**：新潮好看（现代配色、卡片、微动效、骨架屏、干净 Flat）+ 易用方便（48dp 触控、8dp 间距、清晰层级、即时反馈、简单流程、空状态引导）。

| 维度             | 定案                                                                                                                                                                                                        | 依据（ui-ux-pro-max）                                                                         |
|------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|
| 风格             | **Flat Design Mobile (Touch-First)**：扁平、2D、无重阴影、色块、几何、触控优先                                                                                                                              | 库推荐：工具类/系统 UI 最佳，性能 Excellent，WCAG AAA                                         |
| 配色             | **动态色优先**（Material You 壁纸取色）+ 静态兜底：`primary #2563EB` / `secondary #0891B2` / `accent #EA580C`（对比度已调至 WCAG 3:1+）/ 背景 `#F8FAFC` / 前景 `#0F172A`；深色模式全支持（跟随系统 + 手动） | MASTER.md 配色 + 动态色为 M3 主流                                                             |
| 字体             | **跟随系统字体**（Android 默认，中文一致性好）+ M3 type scale（title/body/label 层级）；不 bundle 自定义字体（低损耗）                                                                                      | 库推荐 Atkinson Hyperlegible（拉丁可访问字体）在中文环境无字体可映射，弃用；M3 系统字体已现代 |
| 间距             | **8dp 节奏**：4/8/16/24/32/48；卡片 padding 16、列表项间距 8、分组间距 24/32                                                                                                                                | MASTER.md spacing + pro-rules 8dp rhythm                                                      |
| 触控             | 触控目标 **≥48×48dp**（Android 标准），相邻目标间距 ≥8dp；图标小则用 hitSlop 扩热区                                                                                                                         | ux 域 Touch Target Size (High) + pro-rules                                                    |
| 动效             | 微交互 **150-300ms**、平台原生 easing（spring）；列表入场 stagger（0.4s，尊重 `reduced-motion` 关闭）；卡片按压 scale 0.97 即时反馈；页面加载用**骨架屏/进度条**（不留白屏）                                | MASTER key effects + ux Loading States (High) + pro-rules                                     |
| 图标             | **Material Symbols 矢量图标**（统一 filled/outline 层级、24dp、一致 stroke），**禁用 emoji 作图标**；语义色 token 控制                                                                                      | pro-rules icons 纪律                                                                          |
| 反馈             | 按压 ripple + 触觉反馈（轻震动，不过度）；破坏性操作确认；删除可撤销（Snackbar Undo）                                                                                                                       | ux Haptic + pro-rules                                                                         |
| 可访问性         | 正文对比 ≥4.5:1、次要文字 ≥3:1；焦点状态可见；TalkBack 描述齐全；表单有 label/hint/错误提示；颜色不单通道传达信息                                                                                           | pro-rules + MASTER checklist                                                                  |
| Compose 实现规范 | typed routes（sealed class）；事件导航（Channel + receiveAsFlow）；不可变集合（PersistentList）；`mutableStateOf` snapshot state；`animate*AsState`/`AnimatedVisibility` 动效                               | jetpack-compose stack 5 条                                                                    |

**主界面布局（定案）**：

- 顶部：大标题（应用名）+ 右侧 设置/关于 图标入口
- 主体： **卡片式列表**（两行：名称主 + URL 次 + favicon 头像 + overflow），`LazyColumn` 虚拟化；空状态友好引导（插画 +
  "添加第一个页面"按钮）
- 底部： **Extended FAB "添加"**（悬浮、主操作唯一）；删除/编辑走 `SwipeToDismissBox` 滑动操作（M3 组件）+ 删除可撤销 Snackbar
- 加载：列表/网页加载显示进度反馈（顶部线性进度 / 骨架屏），禁止白屏无反馈

**设置页布局（定案）**：

- 分组卡片（`通用` / `外观` / `隐私` / `关于`），组标题 + Card 列表；M3 `Switch` / `SegmentedButton` / `RadioButton` 组件
- 语言切换：`跟随系统 / 中文 / English` SegmentedButton（默认中文）
- 关于页：版本号 + 许可 + 开源库（简洁列表）

---

## 5B. 删除清单（最终复盘，供审阅）

> 以下为 **本次改造将删除的内容完整清单**，按类别列出，均经源码引用核对（删除后不产生编译残留）。 **保留项**（合规/核心必需）单独标注。

### 5B.1 功能删除（套壳场景无意义）

| 删除项                    | 涉及文件/依赖                                                                                                                                      | 引用方（需同步清理）                                        | 理由                                                    |
|---------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------|---------------------------------------------------------|
| **8 沙箱容器**            | `build.gradle` 的 `renameManifest` / `extendAndroidManifest` / `createWebViewclassN` 8 任务；`SandboxManager` / `Sandbox`                          | `WebViewActivity.java`（SandboxManager 引用）               | 多进程隔离对单站点套壳无意义；AGP 9 高危区，连根拔除    |
| **ProcessPhoenix**        | `process-phoenix` 依赖；`WebViewLauncher` 的 `triggerRebirth` 分支                                                                                 | `WebViewActivity.java:153,159,543`                          | 沙箱专用重启；`createWebViewIntent`/`startWebView` 保留 |
| **Adblock 全家桶**        | `ad-filter` 依赖；`AdblockListFragment`/`Adapter`/`AdblockLifecycleHelper`/`AdblockProviderApiHelper`/`AdblockConfigActivity`/`AdblockConfig` 模型 | `MainActivity.kt`、`WebViewActivity.java`、`WebApp.kt` 字段 | 套壳无广告场景；省崩溃面与体积                          |
| **生物识别锁**            | `BiometricPromptHelper`；`androidx.biometric` 依赖                                                                                                 | `WebViewActivity.java`、`WebAppSettingsActivity.kt`         | 单用户自用，锁无意义                                    |
| **强制深色时段**          | `timespan` 相关设置（`WebApp` 字段/UI）                                                                                                            | `WebApp.kt`、`WebAppSettingsActivity.kt`                    | 深色跟随系统即可，时段限制冗余                          |
| **NewsActivity 更新公告** | `NewsActivity`；`assets/news/`（latestUpdate_*）                                                                                                   | `EntryPointUtils`、`AndroidManifest`                        | 更新公告对定制壳无意义                                  |
| **EULA 弹窗**             | EULA 相关 strings/UI                                                                                                                               | `MainActivity` 首启流程                                     | 定制壳无授权门槛                                        |
| **PayPal/LiberaPay 捐赠** | About 页捐赠元素、`liberapay_logo`                                                                                                                 | `AboutActivity.kt` 的 `showPayPal`/`showLiberaPay`          | 去商业化；`showPayPal` 本就是死代码                     |

### 5B.2 资源/UI 删除

| 删除项                | 位置                                                  | 说明                                               |
|-----------------------|-------------------------------------------------------|----------------------------------------------------|
| 开屏红底大 logo       | `res/drawable/launch_screen.xml`、`AppTheme.Launcher` | 换官方 SplashScreen API                            |
| 语言资源 de/es/it     | `res/values-de` / `values-es` / `values-it`           | 只留中英                                           |
| Native Alpha 品牌图标 | `res/mipmap-*` 的 `native_alpha*`                     | 换中性自适应图标                                   |
| 关于页旧依赖          | `io.github.medyo:android-about-page`                  | Compose 重写后弃用（保留 aboutlibraries 开源声明） |
| 旧 UI 测试            | `androidTest/UITests.java` + `TestUtils.java`         | Espresso 断言旧 View UI，Compose 后失效            |

### 5B.3 构建/配置删除

| 删除项                               | 位置                                         | 说明                                                          |
|--------------------------------------|----------------------------------------------|---------------------------------------------------------------|
| `kapt databinding compiler 3.1.4`    | `app/build.gradle`                           | 2018 遗留，AGP 9 必炸                                         |
| `kotlin-kapt` 插件                   | `app/build.gradle`                           | 随 kapt 移除                                                  |
| flavor `extended` / `extendedGithub` | `app/build.gradle`                           | 三合一，留单一 flavor；删 `.pro` 后缀与 `FLAVOR` 门控（6 处） |
| ABI splits                           | `app/build.gradle`                           | 无 native 库，4 份相同 APK                                    |
| `WRITE_EXTERNAL_STORAGE` 权限        | `AndroidManifest.xml`                        | API 29 起已废                                                 |
| `INSTALL_SHORTCUT` 权限              | `AndroidManifest.xml`                        | API 26 起系统自动                                             |
| `screenOrientation="portrait"`       | `AndroidManifest.xml`（+ build.gradle 镜像） | 大屏强制自适应                                                |
| `windowOptOutEdgeToEdgeEnforcement`  | `res/values/themes.xml`                      | 弃用                                                          |
| 旧 gradle flags                      | `gradle.properties`                          | `android.newDsl` 等新默认接管                                 |

### 5B.4 依赖删除（build.gradle）

| 依赖                           | 理由                                                                   |
|--------------------------------|------------------------------------------------------------------------|
| `ad-filter`（AdblockAndroid）  | Adblock 删除                                                           |
| `process-phoenix`              | 沙箱删除                                                               |
| `androidx.biometric`           | 生物锁删除                                                             |
| `androidx.work:work-runtime`   | 仅 `App.java:20-21` 空初始化，无任务                                   |
| `drag-drop-swipe-recyclerview` | 列表 Compose 化（`WebAppListAdapter/Fragment` 引用，随重写移除）       |
| `circularprogressbar`          | `ShortcutDialogFragment` 用（随 UI 重写移除，favicon 加载改骨架/占位） |
| `easypermissions`              | 权限改系统 API 处理                                                    |
| `android-about-page`           | 关于页重写                                                             |

### 5B.5 保留项（勿删）

| 保留项                                                                      | 理由                   |
|-----------------------------------------------------------------------------|------------------------|
| **首页自定义地址 + 添加入口**                                               | 核心功能（用户强调）   |
| **桌面快捷方式**（ShortcutDialogFragment）                                  | 核心功能（用户强调）   |
| **WebView 核心渲染**（WebViewActivity/CustomBrowser/CustomWebChromeClient） | 渲染核心，仅适配不重写 |
| **手势系统**（双指/三指）                                                   | 基础功能               |
| **aboutlibraries**                                                          | GPL-3.0 开源声明合规   |
| **aboutlibraries About 页**（许可/开源库）                                  | 合规（保留改版）       |
| `privacy_policy.md` / `LICENSE`                                             | 合规                   |
| 定位/相机/麦克风权限                                                        | Web 页面权限按需       |

---

## 5C. 语言统一（Kotlin-first，最终定案）

**原则**：全项目**统一 Kotlin**——双语言混用 = 两套规范/工具链/心智负担；Compose 是 Kotlin-first、AGP 9 内置 Kotlin，统一 Kotlin 是官方路径。**Java 存量 15 文件（2671 行）**：保留 8 个重写为 Kotlin，其余删除；新增代码一律 Kotlin。

### 5C.1 Java → Kotlin 迁移清单（保留重写）

| 文件 | 行数 | 迁移要点（遵守编码规范） |
|---|---|---|
| `WebViewActivity.java` | 1019 | 核心渲染，最大迁移项。转 Kotlin：`CustomBrowser`/`CustomWebChromeClient` 内部类改 Kotlin 内部类/顶层、`onActivityResult`/`onRequestPermissionsResult` 等回调现代化、硬编码文案抽 strings、switch-case 改 `when` 表达式、移除沙箱/flavor 门控 |
| `ShortcutDialogFragment.java` | 419 | 转 Kotlin + Fragment KTX；favicon 拉取逻辑保留；`WebApp` 数据类互操作对齐 |
| `model/DataManager.java` | 395 | 转 Kotlin singleton；Gson 序列化保持（数据兼容）；`Hasher`（SHA-256 校验和，`DataManager.java:297,318`）改标准库 `MessageDigest`，删 Hasher 依赖 |
| `lib/MovableFloatingActionButton.java` | 101 | 转 Kotlin（或随 UI 重写弃用——评估后定，倾向保留转 Kotlin） |
| `model/DataVersionConverter.java` | 65 | 转 Kotlin；映射逻辑保持（数据迁移兼容红线） |
| `util/Utility.java` | 53 | 转 Kotlin；`Assert` 保留（前置守卫） |
| `util/Const.java` | 34 | 转 Kotlin object；常量收敛 + 注释 |
| `util/App.java` | 29 | 转 Kotlin；删 WorkManager 空初始化 |
| `util/InvalidChecksumException.java` | 15 | 转 Kotlin 异常类 |

### 5C.2 删除的 Java/Kotlin 文件（不迁移）

| 文件 | 理由 |
|---|---|
| `model/Sandbox.java` / `SandboxManager.java` | 沙箱删除（§5B） |
| `androidTest/TestUtils.java` / `UITests.java` | 旧 View UI 测试，Compose 后失效（§5B） |
| `test/RoboTests.java` | 空壳 Robolectric 桩 |
| `test/UtilUnitTests.java` | 依赖真实网络抓站（xda/orf 等），不稳定且非核心 |
| `model/DataVersionConverter.java` | **数据兼容层删除（D14）**：旧版直接卸载重装，无迁移需求 |
| `model/deserializer/WebAppDeserializer.kt` / `GlobalSettingsDeserializer.kt` | **数据兼容补丁删除（D14）**：v1.5.0 adBlockSettings 补丁不再需要（Adblock 也已删）；`GlobalSettings` 直接 Gson 反序列化。**注意同步清理注册引用**：`DataManager.java:164,184,185` 的 `registerTypeAdapter` 删除 |

### 5C.3 Kotlin 编码规范对齐

- 沿用项目 Kotlin 既有风格：`isXxx` 布尔命名（Gson 序列化兼容红线，**不可改名**）、data class + copy、`object` 单例。
- 遵守规范：无魔法值（常量/枚举）、空值防护（`?.`/`?:`/`requireNotNull`）、新时间 API（`java.time`）、StringBuilder/join、集合不可变优先、`when` 表达式、注释中文、import 排序、无用 import/变量零容忍。
- **迁移不改变行为**：纯机械转换 + 规范修正；Gson 字段名/JSON 结构不变（数据兼容红线）。

---

## 5D. 依赖版本统一管理（Version Catalog，官方机制）

**原则**：所有依赖版本号**统一收敛到根目录 `gradle/libs.versions.toml`**（Gradle Version Catalog 官方推荐机制），禁止散落在 `build.gradle` / 各 gradle 文件中。集中管理 = 一处改版本全项目生效、避免版本漂移、IDE 自动补全。

### 5D.1 落地结构

```toml
# gradle/libs.versions.toml
[versions]
agp = "9.3.1"
kotlin = "2.3.20"
gradle = "9.7.0"
compileSdk = "37"
minSdk = "31"
targetSdk = "37"
# ... 全部依赖版本

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
# ... 全部依赖

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
# ...
```

### 5D.2 接入点

- 根 `build.gradle`：`plugins { alias(libs.plugins.xxx) }`（AGP 9 用新 DSL + 版本目录）
- `app/build.gradle`：`implementation(libs.androidx.core.ktx)` 等，**删除所有硬编码版本字符串**
- `gradle/libs.versions.toml` 为版本唯一来源

### 5D.3 对齐官方最新

- 依赖版本以最新稳定为准（实施时逐一核 Maven/Google Maven，见 §2 盘面）
- 保留项（约 10 个）：core-ktx / appcompat / material / activity-compose / compose-bom / lifecycle / navigation-compose / webkit / gson / aboutlibraries / junit / robolectric 等
- 删除项：§5B.4 已列（ad-filter / phoenix / biometric / work / drag-drop / circularprogressbar / easypermissions / about-page / Hasher）
- 版本号不写死在 build 文件，全部走 `libs.versions.toml`

---

## 5E. 备份/导出功能重写（D15）

**决策**：功能**保留**（用户需要），逻辑**重写**——不做旧版数据导入兼容（D14 放弃数据兼容），全新实现。

### 现状（旧逻辑，删除）
- `DataManager` export/import：SharedPreferences 全量 map → 文件（Base64 + Java 序列化）+ SHA-256 校验和（`Hasher`）；import 校验 `InvalidChecksumException`，然后清 WebStorage/cookies 重载。
- 旧逻辑问题：Java 序列化格式不可读、版本耦合、跨版本不兼容、`Hasher` 依赖（`DataManager.java:297,318`）。

### 新方案
| 维度 | 定案 |
|---|---|
| 格式 | **版本化 JSON**：`{ "version": 2, "exportedAt": "...", "webApps": [...], "settings": {...} }`，Gson 序列化（模型即 schema） |
| 读写 | **SAF**（Storage Access Framework）：导出 `ACTION_CREATE_DOCUMENT`（建议文件名 `webnative-backup-YYYYMMDD.json`）、导入 `ACTION_GET_CONTENT` |
| 校验 | 导出时算 SHA-256 摘要存于文件内；导入时重算比对（`InvalidChecksumException` 保留）——完整性校验，非兼容校验 |
| 导入行为 | 导入后清 WebStorage/cookies 并重载（沿用原行为）；**不做**旧版数据格式转换 |
| UI | 设置页「数据」分组：导出 / 导入入口（Material 3 按钮/列表项），成功/失败 Snackbar 反馈 |
| 依赖 | 删 `Hasher` 依赖（SHA-256 用标准库 `MessageDigest`） |

### 实现要点
- 新格式 `version` 字段预留（后续格式演进可版本化处理）。
- `DataManager` Kotlin 化时一并重写 export/import（§5C.1）。
- 不兼容旧版备份文件（旧版卸载重装，导入旧文件直接报格式不支持提示）。

---

## 6. 分阶段改造计划（每阶段有验证门）

### 阶段 0：基线验证（30 分钟）

- 跑 `./gradlew assembleStandardDebug` 确认当前代码可构建。
- **目的**：建立基线，后续升级锅与新锅可区分。
- **门**：build 通过。

### 阶段 1：构建系统升级

- Gradle wrapper → 9.7.0；AGP → 9.3.1。
- 迁 built-in Kotlin：删 `kotlin-android` / `kotlin-kapt` 插件声明；删 `kapt "com.android.databinding:compiler:3.1.4"`
  （2018 年遗留，AGP 9 必炸）。
- Kotlin → 2.3.20（或跟随 AGP 9 内置默认 2.2.10+，选稳定高版本）。
- `compileSdk/targetSdk` → 37；`minSdk` → 31。
- **Version Catalog 落地**：新建 `gradle/libs.versions.toml`，所有依赖/插件版本收敛其中；`build.gradle` / `app/build.gradle` 改 `alias(libs...)` / `libs.xxx` 引用，**删除全部硬编码版本字符串**（§5D）。
- **gradle.properties 按官方最新优化**（Gradle 9.7 / AGP 9.3）：
  - JVM 参数：`-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8`（原 1536m 偏小）
  - 构建性能：`org.gradle.parallel=true`、`org.gradle.caching=true`、`org.gradle.configuration-cache=true`（Gradle 9 官方推荐，配置缓存默认不启用需显式开）、`org.gradle.configuration-cache.parallel=true`
  - 清理旧 flags：删 `android.enableJetifier=true`（**项目全 androidx、无旧 support 库，jetifier 可关**）、`android.defaults.buildfeatures.buildconfig` / `android.nonTransitiveRClass` / `android.nonFinalResIds`（AGP 9 新默认接管）
  - `org.gradle.jvmargs` 保持 UTF-8 显式声明（Windows 中文路径/输出防乱码）
- **AGP 9 新 DSL 迁移**（代码级重点）：
    - `applicationVariants.all { ... }`（APK 改名、build 后清理 manifest）→ `androidComponents.onVariants`。
    - 若决定删除沙箱功能（见阶段 2），`renameManifest` / `extendAndroidManifest` / `createWebViewclassN` 整套构建 hack
      连根拔除—— **这是 AGP 9 兼容高危区，删除是最优解**。
- **R 类非 final 编译问题**（AGP 9 默认 `android.enableAppCompileTimeRClass=true`）：`WebViewActivity.java:463-494` 存在
  `switch` + `case R.id.xxx`（10 个 case），R 类非 final 后 switch-case 需编译期常量 → **必编译失败**。处理：改用 if-else
  链（AGP 9 官方建议），顺带清理 `gradle.properties` 的 `android.nonFinalResIds=false`。
- **门**：`assembleStandardDebug` + `testStandardDebugUnitTest` 通过；`git status` 无构建残留。

### 阶段 2：大面积清理（去芜存菁）

- **开屏**：删 `launch_screen.xml` 红底大 logo → `androidx.core:core-splashscreen` 官方 SplashScreen（纯色 + 图标，随主题）。
- **功能删除**（套壳场景无意义）：
    - NewsActivity（更新公告）；
    - EULA 弹窗；
    - **8 沙箱容器整套**（`__WebViewActivity_N` 生成任务、ProcessPhoenix、ProcessUtils、SandboxManager 连根拔除）。 **注意
      WebViewLauncher 不能整体删**：`util/WebViewLauncher.kt:32` 的 `ProcessPhoenix.triggerRebirth` 分支删除，但
      `createWebViewIntent` 仍被 `ShortcutDialogFragment.java:336` 使用、`startWebView` 仍被 `WebAppListAdapter.kt:19` /
      `WebViewActivity.java:350,358`（三指切换）使用——保留普通启动路径。同步清理 `WebViewActivity.java:153,159,543`
      的沙箱进程启动调用点与 `:387` 的 flavor 门控。
    - 生物识别锁（BiometricPromptHelper + androidx.biometric 依赖）；
    - 强制深色时段（timespan 相关设置）；
    - **Adblock 全家桶**（AdFilter / ad-filter 依赖 / AdblockListFragment / AdblockListAdapter / AdblockLifecycleHelper /
      AdblockProviderApiHelper / AdblockConfigActivity）；
    - PayPal 捐赠 UI。
    - **About 页：保留改版（合规必需）**——`AboutActivity.kt` 承载 GPL-3.0 许可声明（`showLicense`）+ 开源库列表（
      `showOpenSourcelibs`，aboutlibraries `LibsBuilder`）， **必须保留**。只删：PayPal/LiberaPay 捐赠元素、cylonid 品牌文案、
      `io.github.medyo:android-about-page:2.0.0` 依赖（Compose 重写后不再用 AboutPage 库）；改为版本号 + 许可 + 开源库三项简洁页。
- **flavor 三合一**：`standard` / `extended` / `extendedGithub` → 单一 flavor；删 `BuildConfig.FLAVOR` 门控（精确位置 6 处：
  `MainActivity.kt:79`、`AboutActivity.kt:58`、`SandboxManager.java:24`、`WebAppSettingsActivity.kt:145`、
  `WebViewActivity.java:387`、`NewsActivity.kt:59`）与 `.pro` 后缀。
- **权限裁剪**：删 `WRITE_EXTERNAL_STORAGE`（API 29 起已废）、`INSTALL_SHORTCUT`（API 26 起系统自动）；保留 定位/相机/麦克风（Web
  页面权限按需，`PermissionRequest` 运行时处理）。
- **删 ABI splits**：项目无任何 native 库，4 份相同 APK 纯浪费。
- **语言资源清理**：删 `values-de` / `values-es` / `values-it`；`resConfigs "en","zh"`（见 §5A.2）。
- **语言统一（Kotlin-first）**：Java 存量按 §5C 清单迁移——保留 8 文件重写为 Kotlin，删除 7 文件；`app/src/main/java` 与 `app/src/main/kotlin` 双源根合并为单一 Kotlin 源根；删除 `lib/MovableFloatingActionButton` 视评估定（倾向保留转 Kotlin）；`Hasher` 改 `MessageDigest`。
- **数据兼容层删除（D14）**：`DataVersionConverter` + 两个 deserializer 整文件删除；`GlobalSettings` / `WebApp` 直接用 Gson 默认反序列化（模型字段即 schema，无历史包袱）。
- **门**：build + 单测通过。

### 阶段 3：首页自定义地址功能（核心，保留原能力）

- **首页添加地址功能完整保留**：用户自定义 URL 添加 Web App（原 `buildAddWebsiteDialog` 流程）， **不预置任何站点地址**。
- 首次启动：若列表为空，引导用户添加第一个地址（沿用欢迎引导，去掉原版"预置站点"语义）。
- 应用名：中性名（默认 **WebShell**，实施前可改）；图标：中性自适应图标（终端/对话气泡风格，不出现任何品牌字样/logo）。
- 桌面快捷方式沿用现有 ShortcutDialogFragment 能力。
- **新增 `displayName` 字段**（模型 + 添加对话框 + 列表 UI + 编辑页），列表主显示名称、URL 次显示（§5A.1）。
- **门**：build + 装机目检——首页可添加任意自定义地址（含名称）并打开，列表显示名称。

### 阶段 4：UI Compose 化（对齐 API 31-37 版本特性 + 谷歌最新设计风格）

- 主界面 + 设置界面 + 添加对话框 → **Compose + Material 3 完整重写**（Material 3 是当前谷歌官方设计语言）：组件、动效、间距、图标全面对齐现代规范；
  **UI 按 `design-system/webshell/MASTER.md` 设计资产实现**（§5A.3：Flat 风格、动态色+蓝青橙兜底、8dp 节奏、150-300ms 动效、48dp
  触控、矢量图标、可访问性）；Navigation Compose 稳定版（2.9+；Navigation 3 作为备选不上主菜）。
- 主题： **动态色优先**（壁纸取色）+ Material 3 baseline 兜底 + 深色跟随系统（设置可手动切换）。
- 易用性优化：主界面卡片式列表、添加/编辑流程简化（含名称字段）、常用设置前置；展示性优化：页面加载进度、WebView 沉浸式展示。
- **国际化落地**：`values/strings.xml`（en）+ `values-zh/strings.xml`（zh）双语言资源，全硬编码文案提取；默认中文 + 设置内语言切换（
  `AppCompatDelegate.setApplicationLocales`）（§5A.2）。
- 自适应：纯手机场景，以手机竖屏为基准优化；沿用窗口 insets 适配；不做平板多列/折叠屏专项（非目标形态）。
- 版本特性逐级对齐：见 §4 清单。
- **旧 UI 测试处置**：`androidTest/UITests.java` + `TestUtils.java` 全部针对旧 View UI（Espresso 断言），Compose 重写后
  **必然失效**——删除或整体 `@Ignore`，配套 `espresso-web` / `espresso-contrib` / orchestrator 依赖一并清理；UI 验证改为
  Robolectric + 装机目检。
- WebViewActivity：保持 View 体系，Compose 侧 `AndroidView` 嵌入；仅做 edge-to-edge 与状态栏融合适配。
- **备份/导出 UI（§5E）**：设置页「数据」分组 + 导出/导入入口（SAF 读写、版本化 JSON、SHA-256 校验）。
- **门**：build + 单测 + 装机目检。

### 阶段 5：渲染专项（§5 五层落地）

- **门**：装机实测 + 手机 vs 桌面渲染一致性截图对比。

### 阶段 6：性能与低损耗

- 开启 R8：`minifyEnabled true` + 资源收缩（套壳无反射负担，包体预期 -30%）。
- 依赖瘦身：删 circularprogressbar / drag-drop-swipe-recyclerview / Adblock / process-phoenix / easypermissions 等。
  **注意**：
    - **aboutlibraries 保留**：AboutActivity 依赖它展示开源许可（`AboutActivity.kt` 使用），GPL-3.0
      合规需要开源声明——保留或换轻量实现，不可直接删。
    - **work-runtime 可删**：全项目仅 `App.java:20-21` 空初始化、无实际任务（grep 验证）——删 adblock 后无使用方，连同 App
      初始化代码一并删除；实施时确认 ad-filter 无传递依赖。
- 其余核心依赖升到最新稳定（webkit / material / fragment / lifecycle / Compose 全家桶——具体版本实施时以最新稳定版为准，不预写死）。
- **门**：APK 体积对比（改造前 vs 后）+ 启动耗时实测。

### 阶段 7：签名与 CI 自动发版（参考 Selene 方案）

- `signingConfigs.release` 读根目录 `key.properties`（git-ignored）； **无 key.properties → 回退 debug 签名**（本地开发可安装）。
- 新建 `.github/workflows/ci.yml`：
    - push/PR：单测 + debug 构建（快速反馈）；
    - **打 tag `v*`**：release 构建 → 从 GitHub Secrets 注入 keystore（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` /
      `KEY_ALIAS` / `KEY_PASSWORD`）自动签名 → 生成 APK（universal + arm64）+ AAB → 自动创建 GitHub Release 附带产物。
- 首次配置流程：**我生成 keystore**（keytool，放项目根目录 git-ignored）→ 产出 4 个 Secrets 值（`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`）→ 用户填仓库 Settings → 打 tag 即出正式包。
- **门**：本地 debug 构建可装；CI 语法验证（push 触发 lint 任务通过）。

### 阶段 8：回归验证

- 全量单测 + Robolectric 通过；`git status` 干净（无构建残留）；`changelog.md` 更新 2.0.0 发布说明。
- **门**：全部绿灯 + 装机完整走一遍（添加 → 打开 → 全屏 → 快捷方式 → 重启保登录态）。

---

## 7. AGP 9 迁移三大雷区（代码摸底确认）

1. **`applicationVariants` 全部废弃**：`app/build.gradle` 中 APK 改名、build 后清理逻辑必须迁到
   `androidComponents.onVariants`（新 DSL）。
2. **8 沙箱文本替换 hack**：`renameManifest` / `extendAndroidManifest` / `createWebViewclassN`（复制 manifest + 字符串替换类名）是
   AGP 9 兼容高危区—— **若裁剪沙箱功能，整套 hack 连根拔除（推荐）**。
3. **`kapt "com.android.databinding:compiler:3.1.4"`**：2018 年遗留依赖，AGP 9 下必炸，直接删除（现代 AGP 内置 DataBinding
   编译器）。

---

## 8. 签名发版方案（Selene-Source 参考）

参考仓库：`https://github.com/fc6a1b03/Selene-Source/tree/dev/android`（fork 自 MoonTechLab/Selene-Source）

**核心模式**（已读源码确认）：

- `app/build.gradle.kts` 中 `signingConfigs.release` 从根目录 `key.properties` 读取 `storeFile` / `storePassword` /
  `keyAlias` / `keyPassword`；文件不存在则 release 回退 `debug` 签名。
- CI（`.github/workflows/ci-cd.yml`）：push/PR 跑 analyze + test + debug 构建； **tag `v*` 触发 release job**，构建全部平台产物，
  `softprops/action-gh-release` 自动创建 GitHub Release 附带 APK/AAB 等产物。
- keystore 不落仓库：CI 通过 GitHub Secrets 注入（`KEYSTORE_BASE64` 等），或本地 `key.properties`（git-ignored）。

**本项目落地**：见阶段 7。差异点：本项目为纯 Android 单模块，无 Flutter 多平台需求，CI 只构建 Android APK/AAB。

---

## 9. 风险登记与缓解

| 风险                                                                         | 等级   | 缓解                                                                             |
|------------------------------------------------------------------------------|--------|----------------------------------------------------------------------------------|
| **R 类非 final 导致 switch-case 编译失败**（`WebViewActivity.java:463-494`） | **高** | 阶段 1 内改 if-else 链（AGP 9 官方建议）                                         |
| AGP 9 新 DSL 下 `applicationVariants` 迁移失败                               | 高     | 阶段 1 单独门禁；沙箱删除后逻辑大幅简化                                          |
| Gradle 9.7 与 AGP 9.3.1 兼容性报错                                           | 中     | 立即回退 Gradle 9.5.0（AGP 9.3 官方默认）                                        |
| Compose 化导致旧单测/UI 测试失效                                             | 中     | 单测以纯逻辑为主（DateUtils/UtilUnitTests），UI 走装机目检；测试失败可定位到阶段 |
| kimi.com 桌面 UA 导致登录被拦                                                | 中     | 实施时实测，必要时回退系统 UA 或按域名分流                                       |
| Kotlin 2.3.20 与 AGP 9.3.1 内置 KGP 2.2.10 版本差异                          | 低     | built-in Kotlin 机制自动处理；JDK 25 场景需显式声明 KGP 2.3.20（§2 已记录）      |
| 第三方库（drag-drop 列表、aboutlibraries）对 AGP 9 不兼容                    | 低     | 均在删除清单内，无兼容负担                                                       |

---

## 10. 验收标准汇总（最终交付定义）

- [ ] `assembleStandardDebug` / `assembleStandardRelease` 通过，`git status` 无构建残留
- [ ] 全量单测 + Robolectric 通过
- [ ] 首页可自定义地址添加 Web App（含名称字段），打开、全屏、快捷方式全流程可用
- [ ] 中英双语切换生效：主界面/设置/对话框/关于页全文案随设置即时切换（默认中文）
- [ ] UI 质量（ui-ux-pro-max pro-rules 清单）：触控 ≥48dp、对比 ≥4.5:1、无 emoji 图标、动效
  150-300ms、深色模式独立验证、安全区无遮挡、reduced-motion 支持
- [ ] 手机渲染与桌面端视觉一致性（截图对比）—— **纯手机场景，不做平板/桌面适配**
- [ ] 桌面快捷方式可全屏直达
- [ ] APK 体积较改造前显著下降（R8 + 依赖瘦身，预期 -30%）
- [ ] `changelog.md` 更新 2.0.0
- [ ] tag 触发 CI 自动签名发布链路验证通过

---

## 11. 参考链接

- AGP 9.3.0 release notes（最大 API 37）：https://developer.android.com/build/releases/agp-9-3-0-release-notes
- AGP 9.0.0 release notes（built-in Kotlin / 新 DSL）：https://developer.android.com/build/releases/agp-9-0-0-release-notes
- Gradle 版本源：https://services.gradle.org/versions/all
- Android SDK / NDK 下载：https://developer.android.google.cn/ndk/downloads?hl=zh_cn
- AGP Gradle API 参考：https://developer.android.google.cn/reference/tools/gradle-api
- Kimi Code CLI 文档：https://moonshotai.github.io/kimi-code/zh/guides/getting-started.html
- Kimi Code Web：https://www.kimi.com/code/
- Selene-Source（CI/CD + 签名参考）：https://github.com/fc6a1b03/Selene-Source/tree/dev/android
