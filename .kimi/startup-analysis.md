# 启动链路剖析报告（2026-08-25，模拟器 Pixel_9a x86_64）

## 基线数据（am start -W TotalTime）

| 场景 | 耗时 | 备注 |
|------|------|------|
| debug MainActivity 冷启动 | 2188-2288ms | 3 次分布 |
| release MainActivity 冷启动 | 696-1006ms | 首次含 profile 安装 |
| release MainActivity 二次 | 587ms | 热缓存 |
| 点击卡片→WebViewActivity Displayed | +744ms | 系统 Displayed 口径 |

## debug 包 2.2s 阶段拆解（logcat 时序）

| 阶段 | 耗时 | 累计 |
|------|------|------|
| Zygote fork→classloader 就绪 | 411ms | 411 |
| App.onCreate（applyUiMode） | 124ms | 535 |
| →MainActivity applyUiMode/resolveTheme | 95ms | 630 |
| →applySystemBarColors | 97ms | 727 |
| →setContent 首帧编译 | 327ms | 1054 |
| →FreeType 字体加载 | 444ms | 1498 |
| →Surface 创建 | 316ms | 1814 |
| →首帧上屏（Skipped 59 frames） | 124ms | 1938 |

**结论 1：debug 包大头是渲染管线（1085ms 首帧编译+字体+Surface），属 JIT+x86 模拟器放大，release 仅 ~700ms——应用代码可挤空间主要在 IO/主线程同步段，不在渲染。**

## 点击→WebView 744ms 内部时序

tap(16.60) → libwebviewchromium.so 加载(16.77) → native ready(17.02) → Displayed(17.35)。
**结论 2：~230ms 是 WebView 内核 so 首次加载（进程内只发生一次）；后续同进程打开第二个 WebApp 无此段。**

## 已达标项（无需动）

- Cookie 恢复/保存：CoroutineScope(Dispatchers.IO) 异步 ✓
- StatsRecorder.recordLaunch：异步 ✓
- 崩溃日志检查：异步 ✓
- MainScreen 图标：IO 线程 resolveIcon + 异步空窗兜底 ✓
- Baseline Profile：已启用（ProfileInstaller 首启后台安装，二次启动生效）✓
- WebView 预热：已评估并否决（TrichromeWebView 6432 实机崩溃，App.kt 注释在案）

## 优化清单（S3 实施）

| # | 优化 | 类型 | 预期收益 | 风险 |
|---|------|------|---------|------|
| O1 | App.onCreate 后台预热 SharedPreferences（DataManager 的 SP 首次磁盘 IO 移出主线程） | 异步/预热 | 冷启动 -50~150ms | 低 |
| O2 | Cookie restore 异步 removeAllCookies 与 loadUrl 竞争：页面可能带着**清空后未恢复**的 cookie 发请求（登录态偶发丢失根因） | 时序修复 | 正确性+体验 | 中 |
| O3 | setupWebView 反射遍历 declaredFields 清 UA 尾巴：结果缓存 companion（字段布局进程内不变） | 懒计算 | 每次 -1~5ms | 低 |
| O4 | setDarkModeIfNeeded 三次 isFeatureSupported 合并一次 | 简化 | <5ms | 低 |
| O5 | WebViewActivity onCreate 缺 applyUiMode/setTheme 前置（规范要求，依赖 Manifest 主题）——补齐 | 规范对齐 | 视觉一致性 | 低 |

## 不做（评估后否决）

- WebView 预热池：实机崩溃风险在案（App.kt 注释）
- Compose 列表懒加载改造：MainScreen 已是 LazyColumn 且图标异步
- 渲染管线优化：debug JIT 放大，release 无此问题

## 验收口径

`am start -W` TotalTime 冷启动均值（3 次）+ 点击→Displayed，优化前后对比；testDebugUnitTest lintDebug 全绿。
