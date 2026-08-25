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

## 1. 十二条红线（违反即打回）

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

## 5. 单测规范

- Robolectric：`@Config(sdk = [34])`（targetSdk 37 > Robolectric 支持 ≤36）
- 测试方法名行为描述（`saveIcon_storesFileAndUpdatesPath`）；必须可跑（`testDebugUnitTest`）

---

## 6. 已验证的坑（项目实践沉淀）

- Kotlin 位或用 `or` 关键字（`|` 有解析歧义——实测）
- `OutlinedTextFieldDefaults.colors` 参数名随 Material3 版本变化（避免新旧歧义参数）
- CRLF 文件用 Python `newline=''` 处理（Edit 工具的 LF 匹配可能失败）
- Gson 旧数据字段缺失 → getter 归一化（如 `sessionTabCount`/`textZoom`）
- `copyStatsAndShortcuts` 保留统计/快捷键字段（防保存清空）

---

执行：改动前对照本规范（尤其十二条红线）；提交前全绿 + 证据交付。
