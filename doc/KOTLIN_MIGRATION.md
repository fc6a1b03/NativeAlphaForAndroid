# Kotlin 迁移待办（第二刀计划）

> 目标：项目 100% Kotlin，`app/src/main/java` 目录整体退役。
> 第一刀已完成（v2.1.30）：`Const` / `App` / `Utility` / `InvalidChecksumException`
> 迁入 `src/main/kotlin`，并删除死代码 `Utility.setViewAndChildrenEnabled`。

## 剩余 Java 文件（3 个，共 3235 行）

| # | 文件 | 行数 | 角色 | 风险 |
|---|------|-----:|------|------|
| 1 | `model/DataManager.java` | 457 | 数据中枢（SharedPreferences+Gson 单例，备份/恢复），109 个调用点 | ⚠️ 中 |
| 2 | `ShortcutDialogFragment.java` | 440 | "重新创建快捷方式"弹窗（最后的 Java View UI） | 🔄 重写 |
| 3 | `WebViewActivity.java` | 2350 | 渲染核心（WebView 设置/回调/手势/下载/权限/错误页） | 🔴 高 |

## 执行顺序

1. **DataManager（先迁）**
   - 中枢先迁，`WebViewActivity` 后续互操作面更小
   - 关键坑：Gson 持久化 JSON 兼容（字段名/结构不能变）、单例线程语义、
     Kotlin 侧属性访问语法（`.settings`/`.activeWebsites`/`.incrementedID`）需保持
   - 兜底：配备份/恢复单测（`InvalidChecksumException` 路径）

2. **ShortcutDialogFragment（Compose 重写，非机械迁移）**
   - 并入设置页 Compose 弹窗，复用 `WebAppIconManager` 能力分层
   - 顺手删除：`shortcut_dialog.xml`、CircularProgressBar 依赖、ViewBinding 残留

3. **WebViewActivity（压轴，单独一轮）**
   - 分小步：先无状态辅助方法 → 生命周期 → WebView 回调/手势/Cookie 会话
   - 手势冲突、Cookie 隔离、多标签均为实机踩坑区，每步真机回归
   - 注意：v2.1.30/31 在此文件新增——边缘手势边界提示、HTTP 允许写回存储实例、
     菜单缩放保存时 override 继承逻辑（saveZoomSettings）、错误页缩放跟随生效配置
     （loadCustomErrorPage）——迁移 diff 时别丢

## 第一刀互操作经验（第二刀预案）

- Kotlin 可空性会改变 Java 平台类型语义 → 用 `lateinit`/非空类型对齐原 Java 签名
- Java 静态工具（如 `String.valueOf`）Kotlin 不可达 → 用 Kotlin 等价写法（`Any?.toString()`）
- Java 调用静态成员：`const val`（字段）/ `@JvmStatic`（方法）保持 `Const.XXX`、`X.yyy()` 不变
- Java catch checked exception：抛出方在 Java 侧时迁移异常类安全；抛出方迁 Kotlin 需 `@Throws`

## 遗留账（规范复盘记账）

- [x] ~~`Utility.Assert` 大驼峰命名~~ → 已改 `assertTrue`（定义 + 5 处调用）
- [x] ~~`DataManager` 每次读写 `new Gson()`（热路径内存浪费）~~ → 已统一静态 `GSON` 单例（6 处）
- [ ] `ShortcutDialogFragment` 内 Jsoup 图标抓取（`buildIconMap`/`fetchWebappData`）
      与 `WebAppDataFetcher` 能力重复——随第 2 步 Compose 重写一并消灭（统一走 `WebAppDataFetcher`）
- [ ] 大文件逐行深审（变量复用/长方法）：`WebAppSettingsScreen.kt`（1018 行）、
      `WebAppStatsScreen.kt`（614 行）、`AddWebAppActivity.kt`（589 行）——本版仅做了模式扫描
- [ ] 真机三点验证（代码路径已验证，物理效果待实机）：
      ① IP/自托管站列表图标 HTML 解析实拉；② 老快捷方式手动重建一次；
      ③ OEM launcher 对 `updateShortcuts` 刷新已 pin 图标的支持度
- [ ] 表格横滑 vs 左右手势冲突（待实机复现确认）：用户反馈"左右滑动手势
      影响带左右滑动条表格的滑动"。历史修复（2.1.19 手势加固 / 2.1.20 不消费 UP
      事件）针对的是"手势后上下滑动失效"，表格横滑被抢占无专门修复——
      实机复现后在 `WebViewActivity` 手势分发处修（随第二刀迁移时注意回归）

## 完成标准

- `app/src/main/java` 无 `.java` 文件，sourceSet 移除
- `./gradlew testDebugUnitTest lintDebug assembleRelease` 全绿 + 真机全功能回归
- 本文件随迁移完成归档删除
