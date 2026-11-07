# DSH-Go 借鉴批次实施计划（v2.4.16 候选）

- 状态：待执行（等用户指令开工）
- 生成：2026-11-07
- 来源：dsh-Go（github.com/ccch1mneyyy/dsh-Go，13.6k stars/13 天）三轮调研 + 谷歌官方/依赖账面核对
- 原则：只取同域交叉里**真有落点**的；不无脑复刻功能（壳定位/权限最小化/高性能低损耗为准绳）
- 本地参考：`tmp/dsh-Go`（浅克隆，已 gitignore；批次落地后删除）

---

## 0. 范围

**做（7 项）**（2026-11-07 复盘更新：C6 经评估保留——43 行模式、与坑位 21「系统框顶掉」教训同源、消除「明明允许了却没开成」的用户困惑）：

| 项 | 内容 | 批次 |
|---|---|---|
| #2 | StatsAnim 动效三档分类学注释 | A |
| #3 | 颜色常量对比度数值注释（落点已收窄） | A |
| #4 | THIRD_PARTY_NOTICES.md 集中合规 | A |
| #5 | 审查工作流协议文件（skills/） | B |
| #6 | 诊断导出读出侧二次脱敏 | C |
| #7 | RiskNotice 风险提示组件化（接入点待 C2 定谳） | C |
| C5 | FileChooserUriNormalizer 物化字节预算（实锤缺口） | C |
| C6 | 矩阵格权限排队批量分发（C 批末尾执行） | C |

**不做**：README 截图资产（#1）、README 免责段（#8）、SafeMode PROBATION 试用期、桌宠悬浮窗、l10n 工具化、onboarding/预览卡展开、proot/Flutter 路线一切内容。

**挂起**：#9 矩阵内存深化——现状三层分工（CapacityGate 事前 fail-open / onTrimMemory 事中 / 崩溃恢复兜底）已完整；触发条件=矩阵内存压力探针告警或用户报障，触发后按「forwardsTrim 抽纯函数 → 隐藏格预降级」两步走。

---

## 1. 批次 A：文档与注释批（#2/#3/#4，零行为变化）

### A1 动效三档归档注释

- 落点：`app/src/main/kotlin/com/cylonid/nativealpha/ui/StatsAnim.kt` 头部 KDoc
- 内容：
  1. 三档对照表：反馈类 ≤160ms（按压/图标切换，「快到感觉不到在等」）/ 结构类 240-340ms（折叠/变形/计数，「看得出从哪来」）/ 环境类慢周期（呼吸/加载，「慢到不打扰」）
  2. 现有常量归档：EXPRESSIVE_PRESS_SPRING→反馈；EXPRESSIVE_SETTLE_SPRING(_DP)/COUNT_UP_MS/STAGGER_STEP_MS→结构；Lottie loading→环境
  3. 准入规则一句话：新增动效先归档再选参数，禁止调用点自定参数（与现有「时长缓动只在本文件定义」纪律合并表述）
- 已核实：WebAppStatsMetrics 对 `tween(COUNT_UP_MS)` 是合法常量引用，无散落收口前置
- 验收：diff 纯注释；`testDebugUnitTest lintDebug` 全绿

### A2 颜色常量对比度数值注释

- 落点：
  - `app/src/main/kotlin/com/cylonid/nativealpha/util/StatAccent.kt`：FALLBACK_COLOR_HEX、MIN_SATURATION、heatScale 5 档
  - `app/src/main/kotlin/com/cylonid/nativealpha/util/IconGenerator.kt`：7 组渐变端色（首字母白字对比渐变底的可读性）
  - 错误页色板（res/colors 相关）
- 内容：
  1. 先跑脚本算**真实 WCAG 对比度**再填注释（诚实数字，禁止拍脑袋）
  2. 口径分写纪律：WCAG 对比度比值 ≠ 0.299 感知亮度，两类注释各说各的口径，不混写
  3. 文字色标注「可承载信息（≥4.6）/ 纯装饰（<3 不得承载信息）」档位
- 已核实（不动项）：ThemeUtils.isLightColor 已有 WCAG 0.5 阈值注释；WebPageColorPolicy 已写「与宿主同口径」——这两处不改
- 已核实（不引入）：ColorUtils 无颜色常量（仅 attr 解析），不在范围
- 验收：diff 纯注释；全绿
- 注意：若算出真实不达标的色值，**只记录不修**（修复另立批次评估）

### A3 THIRD_PARTY_NOTICES.md

- 落点：根目录新建 `THIRD_PARTY_NOTICES.md`
- 内容：
  1. 素材节：Lottie「Dog walking」——作者 Syed Asim Ali Shah / 来源 / LottieFiles Free License / 修改说明（去地面横线 layer）——署名义务落实
  2. 分发物依赖节：33 项直接依赖逐个列 license（全 Apache-2.0/MIT 系，与 GPL-3.0 兼容，逐条注明）
  3. 仅测试依赖节：JUnit（EPL-1.0）、org.json 等单列（不进 APK，无分发义务）
  4. 引用分工：README 致谢段（95-105 行）= 致谢+链接；notices = 完整许可清单，两者互链
  5. 源码散落授权注释改指针：`helper/WebViewPageChrome.kt:179`、`gradle/libs.versions.toml:43`
- 验收：notices 文件覆盖 Lottie 授权全要素；`grep -rn "LottieFiles" app/ gradle/` 仅剩指针引用；全绿

---

## 2. 批次 B：审查协议批（#5，纯文档）

### B1 协议文件

- 落点：`skills/ReviewFixProtocol.md`（与 GoogleCodingStandards.md 同级）
- 结构（阶段化 + 记账制）：
  1. 阶段：诊断 → 查现成方案 → 方案 → 实现 → 验证 → 发版门
  2. 不变量（收口既有血泪教训，逐条给出来源）：
     - 候选信号 ≠ 结论：探针只证「应用了什么值」，像素才证用户所见（坑位 16）
     - 新方案 = 全量重构 + 契约单测防丢失（坑位 16 / handoff 事故 2）
     - 改源必 assembleDebug（handoff 事故 3）
     - 验证完整前禁止发版；tag 打 dev 线（R12 + 记忆 app-version-minimal-bump）
     - 实机只读优先、UI 自动化先申报、禁复现错误页+站点切换序列（记忆 realdevice-operation-discipline）
  3. 记账制：每阶段记「已验证事实/排除项/下一步」，未闭环不得交付结论
- 权威源分工：编码规范在 GoogleCodingStandards.md、流程协议在本文件——互不复制，只引用

### B2 引用接线

- `AGENTS.md` 头部「阅读本文件前，请先查阅 skills」段加协议指针
- 协议文件内引用 GCS/坑位/记忆时给精确出处，不复制正文

### B3 记忆收口

- 记忆 `webnative-fix-completeness-expectation` 改写为指向协议文件的引用（消除双权威漂移）

- 验收：协议覆盖 handoff 第 4 节（四条事故红线）+ 坑位 16 全部教训，逐条可对账；AGENTS/GCS/协议引用关系无环无重复

---

## 3. 批次 C：代码批（#6/#7/C5，含单测+实机）

### C1 导出读出侧二次脱敏（#6）

- 落点：`SettingsActivity` 导出链（exportAppErrorsGuarded → exportAppErrorsToUri → export）最终文本组装点
- 内容：对整篇导出文本再跑一次 `ErrorReporter.redactUrlsInText`（已核实：正则 `scheme://` 形态覆盖 content://；对一次脱敏结果幂等；同口径保留 host/路径主干——诊断可读性优先）
- 单测：写侧绕过形态→读出侧拦截；幂等性（二次不改变一次结果）
- 验收：单测全绿；真机导出日志抽验（脱敏生效 + 诊断可读性未损）

### C2 接入点盘点（#7 先行，产出定谳再动组件）

- 内容：现场 grep 定谳手写确认弹窗分布——候选：备份导入覆盖、删除站点（markInactive 不可逆）、清空统计（StatsClearer）、Cookie 隔离关闭
- 已定谳剔除：LNA 错误页引导（webnative://lna-grant 是 HTML 页，不适配 Compose 组件）
- 产出：接入点清单（准入门=不可逆/隐私敏感），作为 C3 输入

### C3 RiskNotice 组件（#7）

- 落点：`app/src/main/kotlin/com/cylonid/nativealpha/ui/` 新增统一组件
- 内容：标题+正文+可折叠「了解更多」+确认按钮；文案全双语资源化；stringResource 组合期预取（Compose 红线）；替换 C2 盘点出的手写弹窗（视觉统一，行为不变）
- 验收：单测（文案组合期预取/折叠态）；真机过接入点 UI

### C5 物化字节预算（实锤缺口）

- 落点：`app/src/main/kotlin/com/cylonid/nativealpha/util/FileChooserUriNormalizer.kt` 的 copyToCache
- 现状实锤：`stream.copyTo(output, BUFFER_BYTES)` 无字节上限——provider 谎报 size/巨流时物化写满 cache 分区或长时间阻塞（有 catch 不崩，但磁盘可被写满）
- 内容（参照 dsh SelectedFileBudget）：
  1. 流式累计计数，超上限即中断物化
  2. 超限处理：中断 → 记入 degraded → 走现有透传降级路径（不新增 UI，行为安全回落）
  3. 预算参数收口为常量（宿主/矩阵同源，FileChooserDelegate 统一入口修一处全覆盖）
  4. 单测：超限中断 / 谎报 size / 正常小文件三形态
- 验收：单测全绿；真机大文件场景过一遍（降级提示正常出现）

### C6 权限排队批量分发（2026-11-07 复盘保留，C 批末尾执行）

- 参照：dsh MediaPermissionQueue（owner token + waiters + resolve 批量分发 + retire 失效迟来者），43 行模式
- 现状实锤：WebPermissionCoordinator（v2.3.12 同源实现）单格内多资源已有「逐个弹窗+未决计数 settle」；**缺口在跨格维度**——coordinator 每格一实例互不知晓，两格同时首次请求同一权限→两个系统框并发→后发顶掉先发（回调丢失→链路挂起/被动 deny）
- 内容：全局单例队列（一次只允许一个同权限系统请求在途，后到者排队等同一结果批量分发）；只合并同权限类型；站点确认弹窗（站点级决策）不合并，仅系统权限步骤排队
- 落点：`helper/WebPermissionCoordinator.kt` 系统请求步骤（宿主/矩阵共用）+ 全局队列对象；**必须保持既有 Robolectric 单测全绿**（注入接口化模式已就绪）
- 单测：并发入队只发一次系统请求 / 结果批量分发（granted 与 denied 两路）/ retire 后迟到者 denied / 不同权限类型不合并
- 注意：C6 是边缘 case 修复非痛点修复（四条件叠加窗口小）——排 C 批末尾，若前序项验证压力大自然单独收尾不强行塞批次

---

## 4. 执行顺序与检查点

```
A（约 3h）→ B（约 2h）→ C2 盘点 → C1 + C3 + C5 + C6（约 1.5d，各项独立 commit）→ C4 实机验证 → bump/发版（等指令）
```

- 每批次完成即独立 commit（dev 线），批内 `testDebugUnitTest lintDebug` 全绿；批次 C 四个代码项各自独立 commit（C1→C5→C6→C3，组件 C3 最后——依赖 C2 盘点结果）
- C 任何代码改动后必须 `assembleDebug` 再真机验证（改源必产 APK，构建产物过期教训）
- C4 实机：debug 包过 C3 接入点 UI + C1 导出抽验 + C5 大文件场景 + **C6 并发实测（本地测试页造两格同权限并发——两格同时触发首次相机请求，验证单次系统框+两格均授权成功）**；遵守真机操作纪律（只读优先，不动用户登录态）

## 5. 发版口径

- 版本：v2.4.16（versionCode 2416，最小步进）；tag 打 dev 线版本提交（非 merge commit）
- 发版前：README/AGENTS 同步说明（发版文档同步纪律）；Release body 逐条读（对外产物验收）
- 批次 A/B 零行为变化、批次 C 低风险——Release body 分类：修复（C1/C5）/ 性能或安全无关不硬凑；措辞禁内部黑话

## 6. 挂起与清理

- #9 矩阵内存深化：触发即按「forwardsTrim 抽纯函数 → 隐藏格预降级」两步走
- C6：等点名
- `tmp/dsh-Go`：本计划全部落地后删除；`adbtmp/` 下 dsh-Go 调研存档（webkit/gms/agp release notes + 截图）随批次落地一并清理
