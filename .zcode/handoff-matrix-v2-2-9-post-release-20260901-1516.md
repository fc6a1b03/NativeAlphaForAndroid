# Handoff: 矩阵全屏满铺+可移动窗数胶囊（v2.2.9 发版后交接）

## 1. 任务目标

多窗矩阵沉浸化迭代：顶栏撤除、可移动窗数胶囊（浮窗助手交互）、零间隙满铺布局，处理过程中连带修复多个渲染/交互 bug，清零代码债后以 v2.2.9 发版。

## 2. 当前状态

- **已完成（全部已发版，dev/main/tag v2.2.9 同步，工作区干净）**：
  - [x] 顶栏撤除（64dp）：返回走系统手势（BackHandler 两段语义不变）；MatrixScreen 顶层 Column→Box
  - [x] 每格标题条（favicon+调节/放大/关闭）保留原位常驻——用户澄清「顶部」只指全局顶栏，格工具条整行是长按拖拽手柄勿压
  - [x] 可移动窗数胶囊（MatrixChrome.kt）：默认顶部居中、拖动自由摆放、左右缘吸边隐藏（露把手+手势排除区防误触）、顶缘吸顶收纳、2s 无操作自动吸顶（已吸左右保持）、用户手动拖动过即 userTookControl=true 本会话不再自动吸顶（点把手弹回=交还系统）
  - [x] 胶囊点击扩开快捷菜单：标题「⊞ n 窗」（plurals 单复数）+ tonal 步进圆钮 + 退出 secondaryContainer 胶囊菜单项；透明遮罩防误触、点遮罩收回；菜单收起态用 AnimatedVisibility 不组合（修复 alpha=0 透明层吞点击的结构性 bug）
  - [x] 零间隙满铺布局：App 内四边零边距、格间 0dp、格子直角；状态栏/导航条可见（Scaffold inset 唯一边界）
  - [x] 渲染修复：FitFrameLayout ratio=1 也显式 EXACTLY 强制 WebView=容器（AT_MOST 下 chromium 按网页内容高度自测致底部留白）；缩放钳制 50..150% 三层收口（44% 时 WebView 超大测量尺寸致 chromium 合成截断只画上半）
  - [x] 把手手势排除区外扩 8dp（边缘首击被系统手势缓冲吞掉）
  - [x] 代码债：MatrixScreen 878→338 行，拆 MatrixCell.kt（格子渲染层）/MatrixCellPicker.kt（选站面板）；删死代码 matrix_title/matrix_drag_hint/MatrixMenuSheet
  - [x] 错误导出成功即清空历史（AppErrorLogRepository.clearAll，提示语「已导出，错误历史已清空」）
- **未完成/未验证**：
  - [ ] 真机未装新包验证（用户真机仍是 v2.2.7 旧正式包——判断依据：用户截图顶部有「← 多窗矩阵」标题栏）
  - [ ] 拖拽排序（长按标题换位）本轮未触碰也未重测
  - [ ] 菜单展开/把手点击的 adb 自动化验证不可靠（多层浮层+动画时序污染），最终交互手感待用户真机/模拟器手测

## 3. 关键决策

| 决策 | 依据 | 状态 |
|------|------|------|
| 顶栏撤除+格工具条保留 | 用户澄清「顶部」只指全局顶栏 | 已确认 |
| 胶囊可移动+三向吸附+2s 自动吸顶 | 用户逐条拍板（浮窗助手交互） | 已确认 |
| 用户拖动过即不再自动吸顶 | 用户原话「当成用户接手胶囊位置了，不可再自动改变」 | 已确认 |
| 菜单居中覆盖胶囊展开 | 用户原话「从胶囊中间往四周展开并居中覆盖胶囊」 | 已确认 |
| 只排除把手自身手势区，其他边缘/底部虚拟键不动 | 用户原话「只屏蔽影响胶囊操作的左右手势」 | 已确认 |
| 状态栏/导航条可见，App 内零留白 | 用户原话「系统状态栏需要可看到」 | 已确认 |
| 格间 0dp 紧贴+直角满铺 | 用户原话「矩阵与矩阵之间不要有间隙」 | 已确认 |
| M3 DropdownMenu 方案废弃 | 实测其锚定只认组合位置 (0,0)，无法感知 offset 绝对定位浮层（弹点失控截图 menu_v10） | 已确认 |
| v2.2.9 patch（首误发 v2.3.0 已删，版本号经用户拍板） | 用户暴怒「谁允许你发 2.3.0」+ AskUserQuestion 拍板 v2.2.8 惯例 | 已确认 |

## 4. 开放问题 / 阻塞点

- **50% 缩放下限渲染余量偏薄**：「44% 截断/50% 完整」边界仅在 720×1616 模拟器实测；大分辨率真机格子物理像素更高，50% 对应 WebView 测量高度更大，可能再触 chromium 绘制截断。根治需重设计 fit 缩放实现（放弃超尺寸测量+View scale 方案）。继续条件：真机出现同类截图即立项
- **chromium 渲染进程周期性死亡**：真机错误日志 `webview:RenderGone: render process gone` 每 ~15 分钟一条持续数日（level=WARNING，WebView 自动重建），与「网页只画一半」高度相关；模拟器 ANGLE 下同样存在。观察项：真机频发则评估 chromium 渲染进程配置
- **胶囊点击在 adb 自动化下命中不稳定**：模拟器 adb input tap 的事件注入与真机触摸路径不同；结构性修复（透明拦截层移除）已完成，真机手感待验

## 5. 相关文件

- `app/src/main/kotlin/com/cylonid/nativealpha/matrix/MatrixChrome.kt`（新，412 行）：可移动胶囊+把手+扩开菜单+matrixPillDockTarget 纯函数
- `app/src/main/kotlin/com/cylonid/nativealpha/matrix/MatrixCell.kt`（新，438 行）：格子渲染层（MatrixCell/五态/ActiveContent/Favicon16/FitFrameLayout）
- `app/src/main/kotlin/com/cylonid/nativealpha/matrix/MatrixCellPicker.kt`（新，189 行）：选站面板
- `app/src/main/kotlin/com/cylonid/nativealpha/matrix/MatrixScreen.kt`（878→338 行）：主入口+网格
- `app/src/main/kotlin/com/cylonid/nativealpha/matrix/MatrixEngine.kt`（852 行，历史超限未动）：restoreSession/applyCellAdjust 两处 clamp
- `app/src/test/kotlin/com/cylonid/nativealpha/MatrixPillDockTest.kt`：吸边判定 6 用例
- 提交：dev `c4c7707`（bump）、`0f631b7`（feat）、`0d31c15`/`b56ddc4`（v2.2.8 bump 序列，含首误发 v2.3.0 已删记录）；main `7962a38`；tag v2.2.8/v2.2.9
- Release：https://github.com/fc6a1b03/NativeAlphaForAndroid/releases/tag/v2.2.9

## 6. 测试状态

- 已通过：`./gradlew assembleDebug testDebugUnitTest lintDebug` → BUILD SUCCESSFUL，**138/138**（MatrixPillDockTest 6 用例在内）
- 已通过：模拟器全链冒烟（自动吸顶/弹回/再吸顶/扩菜单/步进 2→3/遮罩收回/退出按钮/放大收起满铺 bounds `[0,222][720,1568]`）+ logcat 0 FATAL
- 未执行：真机验证（用户真机仍 v2.2.7）；拖拽排序重测

## 7. 下一步

1. 用户真机安装 v2.2.9（Release 页 apk）验证：菜单展开手感、缩放 50% 时 Kimi 渲染、胶囊交互、拖拽排序
2. 若真机出现「内容只画一半」截图 → 对照 webview:RenderGone 时间戳定性（渲染进程死亡 vs 布局问题）
3. 下版本候选（已入 memory）：fit 缩放方案重设计、胶囊位置跨会话持久化、render gone 频发评估
4. 环境注意：模拟器已按优化清单固化（GPU angle_indirect/桌面 start-emulator.bat/queencreek 已禁/adb 37.0.1）；模拟器上只装 debug 包防双图标

## 8. 会话信息

- 模型：ZCode（GLM-5.3-Flash）
- 生成时间：2026-09-01T15:16+08:00
- 分支：dev `c4c7707` = main `7962a38`（tag v2.2.9），工作区干净
- 上份交接：`.kimi-code/handoff-v2-2-7-post-release-20260831-2350.md`（历史，部分状态已被本轮覆盖）
