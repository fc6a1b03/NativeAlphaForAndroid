# Kotlin 迁移第二刀执行计划（v2.1.31 →）

> 依据：`doc/KOTLIN_MIGRATION.md`（第二刀计划）+ 2026-08-24 实测核查。
> 目标不变：项目 100% Kotlin，`app/src/main/java` 目录整体退役。
> 第一刀已完成（v2.1.30）：`Const` / `App` / `Utility` / `InvalidChecksumException`
> 迁入 `src/main/kotlin`，删除死代码 `Utility.setViewAndChildrenEnabled`。

## 现状快照（2026-08-24 实测；第二刀完成后更新于同日）

> **第二刀已完成（2026-08-24）**：DataManager.java(459行) → DataManager.kt(502行)，
> 80 调用点零改动（唯一例外：SettingsActivity.kt 一处 getActiveWebsitesCount()
> 属性化）。新增 DataManagerGsonContractTest（4 测试：Gson 字段名锁 + 合并语义锁）。
> **剩余 Java：0——四刀全部完成（2026-08-24），项目 100% Kotlin，单 kotlin 源集**
> （java 目录已删除，43 个 kt 全部归位）。
> 第四刀：WebViewActivity 2365 行整体机械翻译（2543 行 Kotlin），Compose 弹窗
> /DataBinding 全退役，viewBinding buildFeature 关闭。第三刀完成（2026-08-24）：
> ShortcutDialogFragment 441 行 → Compose 弹窗 ShortcutRecreateDialog（复用
> WebAppDataFetcher/pin 逻辑），删 shortcut_dialog.xml + CircularProgressBar 依赖
> + markInactive 死代码；同刀修复 P1（删除不落库——真机验证 2 Apps 不复活）。
> 另有 5 个 .kt 文件滞留 java 目录（GlobalSettings/SettingsActivity/WebViewLauncher/
> WebAppSettingsActivity/WebAppStatsActivity）——第三/四刀迁移对应文件时顺手搬入
> kotlin 目录，目录退役时清零。下一刀：ShortcutDialogFragment。

| # | 文件 | 实测行数 | 文档行数 | 角色 | 风险 |
|---|------|-----:|-----:|------|------|
| 1 | `model/DataManager.java` | 459 | 457 | 数据中枢（SharedPreferences+Gson 单例，备份/恢复） | ⚠️ 中 |
| 2 | `ShortcutDialogFragment.java` | 441 | 440 | "重新创建快捷方式"弹窗（最后的 Java View UI） | 🔄 重写 |
| 3 | `WebViewActivity.java` | 2365 | 2350 | 渲染核心（WebView 设置/回调/手势/下载/权限/错误页） | 🔴 高 |

- 版本：v2.1.31（versionCode 2131）。三个文件均有小幅增量，说明 v2.1.31
  的改动（边缘手势边界提示、HTTP 允许写回、缩放 override、错误页缩放等）
  已落进这些文件——**迁移时 diff 基线以当前 HEAD 为准，不信任文档中的行数**。
- `DataManager` 调用面实测（2026-08-24）：16 文件、**80 处**，且全部为
  `DataManager.getInstance().xxx()` 链式实例调用形态——静态成员直访为 0
  （原文档记 109 处为写作时旧口径；test 目录仅 1 处注释引用，非调用）。
  Java 侧调用：`WebViewActivity` 31 处 + `ShortcutDialogFragment` 2 处。
  **结论：保住 `getInstance()` 单一入口，80 处调用点零改动。**

## 第二刀：DataManager.java → DataManager.kt（下一步，单独一轮）

**动作**：`app/src/main/java/com/cylonid/nativealpha/model/DataManager.java`
整体迁至 `app/src/main/kotlin/.../model/DataManager.kt`，同包同名，
本刀不动另外两个 Java 文件（它们继续以 Java 语法调用 Kotlin 版）。

### 步骤

1. **迁移前基线**
   - `./gradlew testDebugUnitTest lintDebug` 先跑绿，记录当前输出作回归基线。
   - 通读 `DataManager.java` 459 行，标记所有 public API（Kotlin 侧调用点
     用属性语法 `.activeWebsites` / `.settings` / `.incrementedID`，Java 侧
     `WebViewActivity` / `ShortcutDialogFragment` 用 getter/setter 语法——
     迁移后两类调用方都要编译通过）。
2. **机械迁移 + 签名对齐**
   - **单例形态（已定案，不留两可）**：`class DataManager` +
     `companion object { @JvmStatic fun getInstance(): DataManager }`，
     保持私有构造。理由：80 处调用全是 `getInstance()` 链式形态、
     零静态直访——**禁用纯 `object`**（会迫使 Java 侧 33 处改成
     `DataManager.INSTANCE.xxx`，改动面凭空扩大）。`App`/`Utility` 第一刀
     用 `object` 是因为它们当时就是静态工具类，`DataManager` 情形不同。
   - public 常量 `EULA_ACCEPTED` / `LAST_SHOWN_UPDATE` / `DATA_FORMAT`
     （外部引用 0、无 static import、内部自用 7 处）→ 迁 `private const val`
     放 companion；若未来需外部化再升级 `const val`，不预留投机接口。
   - Java 平台类型 → 显式可空性；Gson 字段名/结构零变更（`WebApp` /
     `GlobalSettings` JSON schema 是持久化契约，改名 = 用户数据丢失，
     v2.0 起无兼容层）。
   - 静态 `GSON` 单例保留（第一刀遗留账已还清的成果，别回退）。
   - Kotlin 调用方已有属性语法（`getInstance().settings = modified`），
     迁移后 `getSettings()` → `var settings` 需同时保住 getter（Java 侧
     `.getSettings()` 不改）与 setter（Kotlin 侧 `=` 赋值不改）——
     用 Kotlin property 天然同时暴露两者，无需手写。
   - checked exception：`InvalidChecksumException` 抛出方已迁 Kotlin，
     如 Java 侧（备份恢复路径）仍 catch，确认 `@Throws` 注解在位。
3. **验证（红线一：证据闭环）**
   - `./gradlew testDebugUnitTest lintDebug assembleDebug` 全绿，贴输出。
   - 重点回归：备份/恢复单测（`InvalidChecksumException` 路径）、
     `WebApp` 增删改（`markInactive` 不物理删除、ID=数组索引不变式）、
     快捷方式创建读取 `incrementedID` 路径。
   - 真机：导出备份 → 清数据 → 恢复备份，列表与设置完整还原。
4. **收尾**
   - 删除 `DataManager.java`；`app/src/main/java` 只剩 2 个文件。
   - 在本文档勾掉本刀，更新实测行数表。
   - **冒烟验证**：`./gradlew assembleDebug` 装真机，冷启动 → 列表加载
     （走 `loadAppData`）→ 打开任一 WebApp → 返回列表 → 增删一个 WebApp
     → 杀进程重启 → 数据仍在。数据持久化路径无回归才叫闭环。

### 关键坑（第一刀互操作经验，直接沿用）

- Kotlin 可空性改变 Java 平台类型语义 → 用 `lateinit` / 非空类型对齐原签名。
- Java 静态工具（`String.valueOf` 等）Kotlin 不可达 → `Any?.toString()` 等价。
- 单例线程语义（SharedPreferences 读写均在调用方线程）不得悄悄改变。

## 第三刀：ShortcutDialogFragment → Compose 弹窗（重写，非机械迁移）

- 并入 `WebAppSettingsScreen` 作 Compose `AlertDialog`，复用
  `WebAppIconManager` 能力分层（UI 线程 `resolveIconCached` / IO 线程
  `resolveIcon`，禁止临时 WebApp 调 `loadFavicon` 落孤儿文件）。
- 图标/标题抓取统一走 `WebAppDataFetcher`，消灭
  `buildIconMap` / `fetchWebappData` 与其重复的能力（遗留账第 3 条）。
- 顺手删除：`shortcut_dialog.xml`、CircularProgressBar 依赖、ViewBinding 残留。
- 验收：设置页"重新创建快捷方式"实机可用，桌面图标正确生成。

## 第四刀：WebViewActivity（压轴，单独一轮或多轮）

- 分小步：无状态辅助方法 → 生命周期 → WebView 回调 → 手势 → Cookie 会话
  （`CookieSessionManager` 隔离）→ 下载/权限 → 错误页。
- 高危区：手势冲突、Cookie 随机数共享、多标签；每步真机回归。
- **粒度预案（执行期动态再分）**：2365 行拆七步只是框架——单步迁移中
  若单次 diff 超过约 400 行或一次编译报错面超过 30 处，说明该步仍过大，
  继续对半拆分后再迁，不硬扛大 diff。
- 迁移时合并处理遗留账第 6 条：表格横滑 vs 左右手势冲突——在 Kotlin 侧
  手势分发处加修复（历史 2.1.19/2.1.20 修的是"手势后上下滑失效"，
  表格横滑被抢占无专门修复），迁移 diff 不丢 v2.1.30/31 的四处新逻辑：
  边缘手势边界提示、HTTP 允许写回存储实例、`saveZoomSettings` override
  继承、`loadCustomErrorPage` 缩放跟随。

## 迁移全部完成后（统一收尾）

- [ ] `app/src/main/java` 无 `.java` 文件，sourceSet 配置移除该目录。
- [ ] `./gradlew testDebugUnitTest lintDebug assembleRelease` 全绿。
- [ ] 真机全功能回归（含下述遗留账实机项）。
- [ ] 归档删除 `doc/KOTLIN_MIGRATION.md` 与本文件。

## 遗留账（从 KOTLIN_MIGRATION.md 继承，未完成）

- [x] ~~`Utility.Assert` 大驼峰~~ → v2.1.30 已改 `assertTrue`
- [x] ~~`DataManager` 每次 `new Gson()`~~ → 已统一静态 `GSON` 单例
- [ ] `ShortcutDialogFragment` Jsoup 抓取与 `WebAppDataFetcher` 重复
      → 第三刀消灭
- [ ] 大文件逐行深审：`WebAppSettingsScreen.kt`（1018 行）、
      `WebAppStatsScreen.kt`（614 行）、`AddWebAppActivity.kt`（589 行）
      → 排在第四刀后、有空窗即做
- [ ] 真机三点验证：① IP/自托管站图标 HTML 实拉；② 老快捷方式手动重建；
      ③ OEM launcher 对 `updateShortcuts` 的支持度
- [ ] 表格横滑 vs 手势冲突实机复现 → 随第四刀修
- [x] ~~**删除 WebApp 不落库（P1 既有 bug）**~~ → 第三刀修复（deleteWebApp 按 ID
      取原对象置 inactive + saveWebAppData；真机验证删除+重启 2 Apps 不复活）。
      `MainActivity.deleteWebApp` → `markInactive` 只改内存对象，全链路无
      `saveWebAppData()`——杀进程即复活（v2.1.31 原版同样复现，非迁移回归）。
      且跟随全局的站点 `getWebAppIgnoringGlobalOverride(ID, true)` 返回深拷贝，
      `markInactive` 改的是拷贝，连内存原对象都未标记。修法：markInactive 后
      补 `saveWebAppData()`（需先确认对深拷贝场景改为直接按 ID 置原对象）。
      随第三刀 ShortcutDialogFragment 重写时一并修（同属列表数据链路）。
