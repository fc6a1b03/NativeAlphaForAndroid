# 统计页「站点故事 · Site Story」重写实施计划

> 状态：已拍板待开工 ｜ 提案日期：2026-09-04 ｜ 分支纪律：dev 开发（R12）
> 需求来源：用户圈定数据源 = 一档（已采集未展示）+ 三档（JS 桥可采）+ 四档（聚合/情绪化）+ 原有数据；
> 二档（使用时长/分段切换/周条形）明确不做。要求：主观、简单、个性；样式统一；统计功能可全部重写。

---

## 1. 定位与设计原则

**重定位**：统计页从「开发者诊断工具」→「这个站点与您的相处史」。现有功能（缓存清理/错误日志/导出/清空）一个不丢，只重排叙事位次。

用户三词的落点：

| 词 | 落点 |
|---|---|
| 主观 | 每站统计页用该站 favicon 主色染色（Palette 提取，提取失败回退 M3 primary，永不让页面无色） |
| 简单 | 一屏一个叙事章节；一张卡只讲一个主数字；工具性内容（缓存/错误/清空）全部收进底部折叠 |
| 个性 | 自动生成的一句话洞察（本地规则引擎）+ 活动热力图 + streak + 月度回顾（Wrapped 式） |

**诚实红线（沿用项目既有教训）**：洞察句与估算只陈述真实计算的值，禁止编造对比基线（「缓存避免了重新下载 505KB」=事实；「比 92% 站点快」=编造，禁止）。提示语声称的行为必须回读验证（导出即清空名存实亡的教训）。

**隐私红线**：全部数据本地存储，零上报；新 DataStore key 不参与备份（同 webevent 取舍，README 登记）。

## 2. 信息架构（7 章，自上而下）

```
┌─────────────────────────────────┐
│ §0 英雄卡（favicon 主色渐变底）   │ 站名 display 字号 +「已相伴 N 天」+ streak
├─────────────────────────────────┤
│ §1 洞察句（点击轮换）             │ 本地规则引擎，一次一条权重最高
├─────────────────────────────────┤
│ §2 性能             [主数字:3ms] │ Vitals 四段瀑布 + 五档竖柱分布 + 缓存省流量
├─────────────────────────────────┤
│ §3 陪伴             [热力图]     │ 近 12 周格网（5 档 favicon 主色色阶）
├─────────────────────────────────┤
│ §4 自动化           [主数字:47]  │ 通知累计大数字 + 规则触发明细
├─────────────────────────────────┤
│ §5 习惯             [三小卡]     │ 快捷键频次条 / 分享次数 / 矩阵使用
├─────────────────────────────────┤
│ §6 收纳             [折叠]       │ 存储+清理 / 错误占比+日志 / 导出 / 清空
└─────────────────────────────────┘
```

## 3. 视觉语言统一规则（「样式统一」的硬约束）

1. **圆角两档**：英雄卡 28dp、内容卡 20dp，全页禁止第三种
2. **色彩三源封顶**：M3 语义色 + favicon 主色（仅英雄卡渐变/热力图色阶/柱状主色）+ surfaceContainer 层级灰；出现第四种颜色即打回
3. **数字规范**：tabular figures 等宽、千分位缩写（1.2K）、每卡一个 display 字重主数字，次级信息 bodySmall
4. **动效三件套**（不引库，自写 Compose）：数字 CountUp（`animateIntAsState`，400ms）、卡片 stagger 进入（30ms 间隔）、柱状/环形生长（`animateFloatAsState`）；系统 `ANIMATOR_DURATION_SCALE==0` 时全跳过；禁止循环装饰动画（循环仅限 loading）
5. **深浅色**：favicon 主色两套主题各算低饱和变体，文字对比度 ≥4.5:1
6. **零值态**：数值为 0 的卡整卡灰化（统一在 StatsSection 抽象层实现，非各卡自判）

## 4. 架构与设计模式（前置抽象，杜绝冗余——R3 落地）

> 原则：抽象在写第二份实现**之前**建立，不留「以后再重构」的债。每个抽象必须有单一职责与明确边界。

| # | 抽象 | 模式 | 消解的冗余 | 落点 |
|---|---|---|---|---|
| A1 | `StatsSection`（密封接口：title/content/visibleWhen/collapsed 默认值） | 组合 + 模板 | 7 张卡各自重复「空态判断/折叠头/圆角边距」样板——灰化与折叠集中一处实现 | `ui/StatsSection.kt` 新 |
| A2 | `JsonPrefsStore<T>` 抽象基类（read/write/prune/decode 钩子） | 模板方法 | StatsDailyStore、WebVitalsStore 与既有 PageErrorRepository 三处同范式 JSON-DataStore 样板 | `util/JsonPrefsStore.kt` 新；两个新 Store 为子类；PageErrorRepository **本期不动**（避免扩散回归面），下批迁入并在本文件登记 |
| A3 | `WebBridgeKit.install(...)` 统一桥安装器（origin 规则+双特性探测+document-start+listener 挂接） | 门面 + 策略 | WebPerfBridge 若仿写 WebShareBridge 即成第二份复制——先抽象，share 桥同批迁入 | `util/WebBridgeKit.kt` 新；WebShareBridge.attach 改为一行委托（行为零变更，桥测锚点断言守护） |
| A4 | `InsightStrategy` 策略接口 + 注册表编排（evaluate(ctx): Insight?，按 weight 排序取最高，点击轮换） | 策略 + 开闭 | 洞察规则写 if-else 链 = 每加一条改一次引擎 | `ui/StatsInsights.kt`；规则注册表 `listOf(...)` 一行一条 |
| A5 | `StatAccent` 门面（`accentColor(webapp)/heatScale(...)`，内部封装 Palette IO 与回退） | 门面 | 调用方各自碰 Bitmap/Palette 细节 | `util/StatTheme.kt` |
| A6 | `rememberCountUp(target)` + `Modifier.statsEnter(index)` | 组合函数封装 | 动效参数/跳过逻辑散落各卡 | `ui/StatsAnim.kt` |
| A7 | `StatsUiState` 单一聚合状态 + 单 loader（SP 字段/daily/vitals/FeatureMetrics 快照一次装配） | 单一数据源 | Activity 内散落多个 `mutableStateOf` 异步竞态 | Screen 入参重构为 state 对象 |
| A8 | `StatsClearer.clearAll(ctx)` 清空编排（SP 统计 + daily + vitals + FeatureMetrics + 错误，逐一回读校验） | 命令聚合 | 清空范围分散多处易漏（「导出即清空」名存实亡教训） | `util/StatsClearer.kt` 新，清空后回读断言 |

**明确禁止的冗余**（技术债红线，R13）：
- 不留 TODO/FIXME/临时兼容出仓；@Suppress 必须注明保留原因
- 同一逻辑出现第二份实现即违规——先查 A1-A8 是否已覆盖，未覆盖先扩抽象再写实现
- 不做「以后可能用到」的参数与缓存（R11）

## 5. 数据层设计

### 5.1 新 DataStore key（AppStorage 范式，stringPreferencesKey + JSON）

| key | JSON 结构 | 裁剪策略 | 用途 |
|---|---|---|---|
| `daily_activity` | `{"2026-09-04":{"opens":5,"hours":[24 个 int]}}` | 滚动 90 天，写入时顺带 prune | §3 热力图 / streak / §1 时段洞察 |
| `web_vitals` | `{"<webappId>":[{"dns":12,"tcp":3,"ttfb":45,"fcp":120,"lcp":230,"cls":0.01,"dom":812,"at":1725…}]}` | 每站最近 10 条 | §2 性能瀑布 |

- **零迁移红线**：不新增 WebApp 数据类字段，不改 Gson schema；现有统计字段（statLaunches 等）原样消费
- 埋点挂点：`StatsRecorder.recordLaunch` 内追加 `StatsDailyStore.appendOpenAsync()`（复用既有单线程队列，不新增线程模型）；hour 桶取 `Calendar.HOUR_OF_DAY`
- 读取口：FeatureMetrics `moduleSnapshot(module)`（internal 已存在，零改动）

### 5.2 WebPerfBridge（三档数据采集）

- 协议：`addDocumentStartJavaScript(webView, PERF_JS, setOf("*"))` + `addWebMessageListener(webView, "webnativePerf", ...)`，经 **A3 WebBridgeKit** 安装
- PERF_JS：`load` 后延时 2s（LCP 稳定窗口）读 `PerformanceNavigationTiming`（dns/tcp/ttfb）+ PerformanceObserver 缓存的 fcp/lcp/cls + DOM 节点数；`__wnPerfSent` 幂等防重；仅主框架上行一次
- 原生：`buildVitalsEntry(payload)` 纯函数解析（非法/缺字段返回 null 丢弃）；transferSize 跨域为 0，展示层处理不采
- 接线双点：WebViewLifecycleDelegate + MatrixCellLoader（与 share 桥同位同源）
- 特性探测前置（DOCUMENT_START_SCRIPT / WEB_MESSAGE_LISTENER 任一不支持整体跳过）；**实现前 javap 反编译 webkit 1.17.0 api.jar 核实签名**（WebShareBridge 纪律，禁止凭记忆写）；R8 无需 keep（WebMessageListener 非反射）

## 6. UI 文件拆分（行数预控，全部 ≤600）

| 文件 | 内容 | 预估 |
|---|---|---|
| `ui/StatsSection.kt` 新 | A1 区块抽象 | ~90 |
| `ui/StatsHero.kt` 新 | §0 英雄卡 | ~120 |
| `ui/StatsInsights.kt` 新 | §1 洞察卡 + A4 策略引擎 | ~140 |
| `ui/StatsPerformanceCard.kt` 新 | §2 瀑布+竖柱+省流量 | ~180 |
| `ui/StatsHeatmapCard.kt` 新 | §3 热力图（Canvas 两层循环，R4 合规） | ~140 |
| `ui/StatsAutomationCard.kt` 新 | §4 自动化 | ~100 |
| `ui/StatsHabitsCard.kt` 新 | §5 三小卡 | ~110 |
| `ui/StatsAnim.kt` 新 | A6 动效封装 | ~60 |
| `ui/WebAppStatsScreen.kt` 重写 | A7 状态装配 + 7 章编排 + §6 收纳折叠 | ~260 |
| `ui/WebAppStatsMetrics.kt` 扩 | 纯函数：insights/streak/分桶/格式化/色阶 | ~250 |
| `ui/WebAppStatsErrors.kt` 微调 | 错误分类占比条 | ~280 |
| `util/WebBridgeKit.kt` 新 | A3 | ~90 |
| `util/WebPerfBridge.kt` 新 | 5.2 | ~120 |
| `util/JsonPrefsStore.kt` 新 | A2 | ~80 |
| `util/StatsDailyStore.kt` / `util/WebVitalsStore.kt` 新 | A2 子类 | ~70+~60 |
| `util/StatTheme.kt` 新 | A5 | ~90 |
| `util/StatsClearer.kt` 新 | A8 | ~70 |
| `WebAppStatsActivity.kt` 扩 | 装配 StatsUiState（loader 移入 Screen 侧亦可，取更简者） | ~190 |

依赖新增：**仅 `androidx.palette:palette-ktx`**（toml 登记，查最新稳定版）。图表全部 Canvas 自绘，不引图表库（Vico 死依赖前科）。

## 7. 分期顺序与每期交付物

> 每 Phase 一个独立 commit（独立可回滚），当期 `testDebugUnitTest lintDebug` 全绿 + 模拟器实测收口后才进下一期。

**Phase 1 · 地基与页面重构**（纯本地，零新采集）
1. A1/A2/A5/A6/A7/A8 抽象落地（JsonPrefsStore、StatsSection、StatTheme、StatsAnim、StatsUiState、StatsClearer）
2. WebBridgeKit 抽出 + WebShareBridge 迁入委托（桥测锚点断言全绿守护零行为变更）
3. 7 章新排版全量上屏：一档数据全挂（相伴天数/快捷键频次/自动化计数/矩阵/回收/错误占比）
4. 英雄卡 favicon 染色 + 洞察句引擎（先上非时段规则：速度型/缓存型/自动化型）+ 动效三件套 + 零值灰化
5. strings ~20 key（en/zh 同批）+ `clear_stats_confirm` 扩围文案
- 实测：深浅色截图、三站三色、零值态、洞察轮换、清空后逐 key 回读断言

**Phase 2 · 按日快照与陪伴章节**
1. StatsDailyStore（append/prune/streak）+ recordLaunch 挂点 + hour 桶
2. 热力图卡 + streak 上英雄卡 + 时段洞察句点亮
- 实测：run-as 写 90 天伪快照 → 热力图/streak 回读比对；清空联动

**Phase 3 · WebPerfBridge 与性能瀑布**
1. javap 核签名 → WebPerfBridge + web_vitals store + 双接线
2. §2 瀑布图点亮（无数据态：仅柱状分布，瀑布区块隐藏）
- 实测：CDP 注入伪 payload 回读；特性探测跳过不崩；R8 包复核（**核对 APK 时间戳晚于源码**）

**Phase 4 · 月度回顾**（Wrapped 式全屏，独立入口按钮；数据不足 30 天时按钮隐藏）
- 回顾页组件 + 分享卡片导出（Bitmap 截图复用现有二维码导出范式）
- 实测：回顾页数据逐项与明细页一致（同一数据源断言）

## 8. 编码规范对照（`.kimi/GoogleCodingStandards.md` 逐条，交付前自查）

| 条款 | 本任务落实 |
|---|---|
| R1 禁硬编码 | 数字全计算值；文案全 R.string（en+zh 同批，提交前键位 diff）；色仅 §3 三源；常量归位（文件内 private const val，跨文件进 Const.kt） |
| R2 常量归位 | 桶常量留 Metrics；90 天/10 条/动画时长等各归其文件；全局语义常量进 Const.kt |
| R3 禁重复 | A1-A8 抽象前置；新实现先查抽象覆盖；单文件 ≤600（§6 预控）、单方法 ≤80 |
| R4 禁深嵌套 | 热力图两层 forEachIndexed 封顶；其余集合操作/提前 return 消解 |
| R5 无 Java | 全 Kotlin |
| R6 注释纪律 | KDoc 写「为什么」；逻辑块行注释；禁止空行分段（仅 import 组/顶层声明间可空行） |
| R7 禁全限定内联 | import 后短名引用 |
| R8 主线程 | 埋点走 StatsRecorder 单线程队列；Palette/Bitmap 解码走 IO；UI 只读快照（A7） |
| R9 交付即绿 | 每期全绿 + 新功能配单测 + 运行证据贴输出 |
| R10 不可变优先 | UiState 全 val；集合 buildList/只读传递 |
| R11 内存最小颗粒 | Bitmap 复用 resolveIconCached 缓存不重复解码、用后不持有；Palette 结果 LruCache（上限 1 张/站，有失效）；无静态持 Activity |
| R12 分支纪律 | dev 提交，发版走 main（发版时另走确认流程） |
| R13 技术债清零 | 无 TODO/FIXME 出仓；@Suppress 注明原因；交付复盘四问 |
| XR8/R8 keep | 无新增 @JavascriptInterface；WebMessageListener 非反射无需 keep；assembleRelease 实测并核对 APK 时间戳 |
| 单测规范 | Robolectric `@Config(sdk=[34])`；方法名行为描述；纯函数优先（insights/streak/解析/分桶全可测） |
| 文案纪律 | 说人话去黑话；删改 UI 同批对齐关联提示语（clear_stats_confirm 扩围） |

## 9. 测试清单

**单测（新增/扩展）**：
- `StatsInsightsTest`：每条规则触发条件/权重排序/无数据不产句/诚实性（不含编造基线文案）
- `StatsDailyStoreTest`：append 聚合/prune 90 天/streak 连续周计算/跨月边界
- `WebPerfBridgeTest`：payload 解析容错（缺字段/非法 JSON→null）/JS 锚点断言/__wnPerfSent 幂等
- `JsonPrefsStoreTest`：读写/prune 模板（子类钩子）
- `Metrics` 扩展：分桶边界/色阶映射/streak 纯函数

**模拟器实测**：见 §7 各期收口项；总收口加「与明细页同源一致性」（回顾页 vs 卡片数字）。

## 10. 风险与回滚

| 风险 | 缓解 |
|---|---|
| WebBridgeKit 迁入 share 桥引入回归 | 桥测锚点断言全绿 + 模拟器 share 全链路复跑（面板弹出/回执清理） |
| Palette 小图/无图标 | 回退 M3 primary（StatAccent 内聚，永不外泄失败） |
| webkit API 记忆偏差 | javap 前置核实（WebShareBridge 已踩平的纪律） |
| 增量构建假交付 | 每期装包核对 APK 时间戳晚于源码 |
| 新 key 数据不可恢复 | 不参与备份已在文档登记；清空操作 A8 逐一回读 |

## 11. 明确不做（防范围蔓延）

使用时长/前台会话统计、分段时间切换、周条形图、第三方图表库、网络上报、站间横向对比页、云端同步、二档其余项。Phase 4 月度回顾若数据底座不足 30 天自动隐藏入口（不硬造内容）。
