# Design System Master File

> **LOGIC:** When building a specific page, first check `design-system/pages/[page-name].md`.
> If that file exists, its rules **override** this Master file.
> If not, strictly follow the rules below.

---

**Project:** WebNative (WebShell)
**Generated:** 2026-08-19 10:03:56
**Updated:** 2026-08-19 (UI 全面 Compose 化 + 靛蓝主题落地)
**Category:** Mobile PWA Shell (Android)

---

## Global Rules

### Color Palette (Material 3 · seed `#4F46E5` Indigo)

Android 实现（`res/values/colors.xml` + `values-night/colors.xml`），与启动屏、动态图标渐变同源。

| Role | Light | Dark | Note |
|------|-------|------|------|
| Primary | `#4A47D6` | `#C1BFFF` | 品牌靛蓝 |
| On Primary | `#FFFFFF` | `#1A1B80` | |
| Primary Container | `#E0DFFF` | `#3232AD` | 顶栏/图标底 |
| On Primary Container | `#000066` | `#E0DFFF` | |
| Secondary | `#5B5D72` | `#C4C5DD` | 中性蓝灰 |
| Tertiary | `#77536D` | `#E9B9DC` | 点缀 |
| Error | `#BA1A1A` | `#FFB4AB` | |
| Background | `#FBF8FF` | `#131318` | 近白/近黑 |
| Surface | `#FBF8FF` | `#131318` | |
| Surface Variant | `#E3E1EC` | `#46464F` | |
| Surface Container Low | `#F5F2FB` | `#1B1B21` | 卡片底色 |
| Outline | `#767680` | `#91909A` | |
| Splash | `#4F46E5` | `#4F46E5` | 开屏 |

**Color Notes:** 靛蓝品牌色 + 中性蓝灰 secondary + 柔和 tertiary，全面替代旧棕红系。

### Typography

Android 端使用系统默认字体（Material3 默认），满足可读性：
- Heading: `headlineSmall/Medium`（粗体，用于页标题/品牌卡）
- Body: `bodyLarge/bodyMedium`（设置项/正文）
- Label: `labelLarge`（区块标题，primary 色）
- 不使用自定义字体（避免 APK 体积与加载损耗，符合「低损耗」目标）

### Spacing (dp)

| Token | Value | Usage |
|-------|-------|-------|
| `--space-xs` | 4dp | 微间隙 |
| `--space-sm` | 8dp | 图标与文字间隙 |
| `--space-md` | 16dp | 卡片内边距、列表间距 |
| `--space-lg` | 24dp | 区块间距、页面边距 |
| `--space-xl` | 32dp | 大区块间距 |

### Shape

| Token | Value | Usage |
|-------|-------|-------|
| 卡片 | 16dp 圆角 | 列表卡片 / 设置区块卡片 |
| 图标底 | 12dp 圆角 | 设置行图标容器 |
| 品牌 Logo | 20-24dp 圆角 | 关于页 W 图标 |
| 输入框 | M3 默认 OutlinedTextField | 全应用 |

---

## Component Specs (Compose Material 3)

### TopAppBar
```kotlin
TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
)
```
- 所有二级页面统一：返回箭头 + 标题 + primaryContainer 底

### Cards
- 列表卡 / 设置区块卡：`RoundedCornerShape(16.dp)` + `surfaceContainerLow` + `elevation 1dp`

### Buttons
- 主操作：`Button`（primary 填充）
- 次操作：`OutlinedButton` / `TextButton`

### Inputs
- 一律 `OutlinedTextField`（M3 轮廓式，浮动标签 + helper/error text）
- 添加向导 Step1：`KeyboardType.Uri` + `ImeAction.Next`
- 名称输入：`ImeAction.Done`

### 设置行（SettingsRow）
- 左侧 40dp 圆角色块图标容器（primaryContainer 底）
- 中间标题（bodyLarge）
- 右侧 `Switch`（行为开关）或 chevron（跳转项）

---

## Pages

| 页面 | 实现 | 状态 |
|------|------|------|
| 主界面（卡片列表） | Compose `MainScreen` | ✅ 已完成 |
| 添加向导（两步） | Compose `AddWebAppScreen` | ✅ 已完成 |
| 全局设置 | Compose `GlobalSettingsScreen` | ✅ 已完成 |
| WebApp 设置 | Compose `WebAppSettingsScreen` | ✅ 已完成 |
| 关于 | Compose `AboutScreen` | ✅ 已完成 |
| WebView 渲染 | Java View（保留） | ✅ 保留不动 |

---

## Style Guidelines

**Style:** Material 3 · 靛蓝 · 卡片化
**Keywords:** M3, indigo, card-based, touch-first, minimal, flat, modern

- 全部交互即时反馈（Switch 默认动画）
- 不使用 emoji 作为图标（一律 Material Icons）
- 支持深色模式（跟随系统，values-night 色板）
- 触控目标 ≥ 48dp（行高）

---

## Anti-Patterns (Do NOT Use)

- ❌ 旧棕红配色（#904A43 系）— 已全面移除
- ❌ 旧版 underline 输入框（TextInputLayout）— 全部改 M3 OutlinedTextField
- ❌ 原 AlertDialog 双输入框一次性添加 — 改两步向导
- ❌ emoji 图标
- ❌ 无圆角/零阴影的旧布局
- ❌ 一次性把所有设置堆在一个长页（分组卡片已按语义分区）

---

## Pre-Delivery Checklist

- [x] 无 emoji 图标（全部 Material Icons）
- [x] 触控目标 ≥ 48dp
- [x] 深色模式色板同步（values-night）
- [x] 对比度：primary on container 均符合 WCAG（M3 tonal palette 保证）
- [x] 键盘优化：添加向导 IME Next/Done
- [x] 动画：仅系统默认（150-300ms 级），无过度动画
