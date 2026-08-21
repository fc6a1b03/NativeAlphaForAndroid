# Google 官方编码规范（项目级）

> 来源：Google Android Kotlin Style Guide + Kotlin Coding Conventions（官方稳定规范，无版本漂移）。
> 适用于本项目（WebNative/Android App，Kotlin 为主 + 保留 WebViewActivity.java 的 Java 渲染层）。

---

## 1. 语言与构建（项目指引）

- 新代码一律 **Kotlin**；唯一保留 Java：`WebViewActivity.java`（渲染核心，禁止擅自迁移）
- Java 17 字节码，Kotlin 2.3.20（AGP 9 内置）
- 依赖版本 **Version Catalog**（`gradle/libs.versions.toml`）唯一来源，禁止硬编码版本
- Compose BOM 固定 `2026.06.01`（禁止升级到不兼容 beta）

---

## 2. Kotlin 代码风格（Google Kotlin Style Guide）

### 命名
- 类 / 对象：大驼峰 `PascalCase`（`WebAppIconManager`）
- 函数 / 方法：小驼峰 `camelCase`（`saveIcon`）
- 常量（顶层/伴生对象）：`UPPER_SNAKE_CASE`（`MAX_ENTRIES`）
- **禁止匈牙利命名**（`mContext`、`strTitle` 之类）；private 字段不加前缀
- 属性：小驼峰，与 getter/setter 无前缀（`isIsolatedSession`）

### 导入（重点）
- **禁止通配符 `.*`**——必须全量显式导入（Google 强规）
- 排序：Android 平台 → androidx → 第三方 → 本项目；组间空行
- 自动修复：Ctrl+Alt+O（Android Studio）/ ktlint `import-ordering`

### 注释
- 方法 KDoc：`/** 功能描述。*/`；类/公共 API 必须有
- 注释描述"为什么"不描述"做了什么"（好代码自解释）
- 行尾 `//` 与代码间隔空格；**禁止** `TODO/FIXME` 挂技术债（必须修复或删除）

### 布局/格式化
- 4 空格缩进（Kotlin 官方，禁 Tab）
- 大括号 K&R 风格（`{` 同行尾）
- 行宽 ~100 字符（超长换行）
- 尾随逗号：多行参数列表/collection 加尾随逗号（Kotlin 现代风格）

---

## 3. Android 特定规范（AOSP/Android 官方）

- **资源命名**：`snake_case`，前缀语义化（`btn_`/`icon_`/`strings` 用 `app_icon_hint`）
- 字符串：`R.string` 全部外部化（禁止硬编码中文/英文文案）——en + zh 双语同步
- 颜色/尺寸：`res/values` 定义，禁硬编码
- AndroidManifest：权限最小化（INTERNET/location/camera/audio），activity 逐项声明
- Activity：一个 Activity 一个职责；`onCreate` 必须 `setTheme` + `ThemeUtils.applyUiMode` + `applySystemBarColors(this)`
- Compose：`@Composable` 函数名大驼峰；state 提升；**stringResource() 在 Composable 作用域预取**（禁止回调内 `context.getString`——Lint `LocalContextGetResourceValueCall`）

### WebView 专属
- `WebViewActivity` 保持 Java；渲染优化（RenderPriority.HIGH + LAYER_TYPE_NONE + OffscreenPreRaster）不得回退
- Cookie 隔离走 `CookieSessionManager`（唯一入口）；头像走 `WebAppIconManager`（统一源）

---

## 4. 单测规范

- Robolectric：`@RunWith(RobolectricTestRunner::class)` + `@Config(sdk = [34])`（项目 targetSdk 37 > Robolectric 支持 ≤36）
- 测试方法名：行为描述（`saveIcon_storesFileAndUpdatesPath`）
- 单测必须能跑（`./gradlew testDebugUnitTest`），新增功能配单测

---

## 5. 已验证的坑（项目实践沉淀）

- Kotlin 位或用 `or` 关键字（`|` 有解析歧义——实测）
- `OutlinedTextFieldDefaults.colors` 参数名随 Material3 版本变化（避免用新旧歧义参数）
- CRLF 文件用 Python `newline=''` 处理（Edit 工具的 LF 匹配可能失败）
- Gson 旧数据字段缺失 → getter 归一化（如 `sessionTabCount`/`textZoom`）
- `copyStatsAndShortcuts` 保留统计/快捷键字段（防保存清空）

---

执行：改动前对照本规范；提交前 `./gradlew testDebugUnitTest lintDebug` 必须全绿。
