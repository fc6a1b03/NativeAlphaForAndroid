---
name: android-emulator-adb
description: 本机 Android 模拟器与 adb 操作手册——从启动模拟器（独立于 IDE）、安装应用、adb 调试、截图取证、UI 交互模拟、日志监控到性能检查的完整实操流程。Windows 环境专用路径与坑点已实测沉淀。适用于任何需要在本机模拟器上验证/测试/排查 Android 应用的子代理。与 android-cli（Google 官方 CLI 工具手册）不冲突：本 skill 是直接可执行的 adb/emulator 命令手册。
license: MIT
metadata:
  author: kingon
  last-updated: '2026-08-20'
  keywords:
  - android
  - emulator
  - adb
  - 模拟器
  - 截图
  - logcat
  - uiautomator
  - 调试
  - Windows
---

# Android 模拟器 + adb 操作手册（Windows 实测版）

## 📌 通用性说明（子代理必读）

- 本手册以 WebNative 项目（包名 `com.cylonid.nativealpha.debug`）为示例，**所有路径/包名/APK 位置请替换为你实际的项目**。
- 包名获取：`adb shell pm list packages | grep <关键词>`。
- APK 路径：通常 `app/build/outputs/apk/debug/app-debug.apk`（debug）或 `app-release.apk`（release）。
- 截图/UI dump 拉到**当前工作目录**（如 `doc/`、`./`），不要用 `/tmp`（Windows python 不认）。

本机环境固定路径（来自 local-env-index，已实测）：
- adb：`/d/software/ambient/android/platform-tools/adb`
- emulator：`/d/software/ambient/android/emulator/emulator.exe`
- AVD：`Pixel_9a`（配置在 `~/.android/avd/Pixel_9a.avd`）

## ⚠️ 前置铁律（每条命令都适用）

1. **必须加 `export MSYS_NO_PATHCONV=1`**：否则 Git Bash 会把 `/data/local/tmp/xxx` 转成 `D:/data/local/tmp/xxx` 导致 adb 报错。
2. **包名注意 debug 后缀**：本仓库 debug 包 = `com.cylonid.nativealpha.debug`，release = `com.cylonid.nativealpha`。用错包名会报 `Activity not started`。
3. **先确认模拟器在线**：`adb devices` 输出含 `emulator-5554 device` 才继续。
4. **截图/UI dump 先拉回本地再解析**：`adb pull /data/local/tmp/x.png doc/x.png`，然后 python 解析（Windows python 不认 `/tmp`，必须用项目内路径）。

## 1. 启动模拟器（独立于 IDEA）

模拟器可后台启动，不需要开 IDEA：

```bash
export MSYS_NO_PATHCONV=1
# 后台启动（& 放后台，不阻塞）
/d/software/ambient/android/emulator/emulator.exe -avd Pixel_9a -no-snapshot-load -no-boot-anim &
# 等 boot 完成（返回 1 即就绪）
/d/software/ambient/android/platform-tools/adb wait-for-device
/d/software/ambient/android/platform-tools/adb shell getprop sys.boot_completed
```

如果模拟器已在跑但 adb 不识别：`adb kill-server && adb start-server && adb devices`。

## 2. 安装应用

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
# 安装 APK（-r 覆盖安装保留数据）
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
# 清数据（干净环境测试）
$ADB shell pm clear com.cylonid.nativealpha.debug
```

## 3. 启动/停止应用

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
# 启动主界面
$ADB shell am start -n com.cylonid.nativealpha.debug/com.cylonid.nativealpha.MainActivity
# 强停（干净冷启动）
$ADB shell am force-stop com.cylonid.nativealpha.debug
# 测冷启动耗时（关键性能指标）
$ADB shell am start -W -n com.cylonid.nativealpha.debug/com.cylonid.nativealpha.MainActivity  # 看 TotalTime
# 当前顶部 Activity（确认在哪个页面）
$ADB shell dumpsys activity top | grep ACTIVITY | head -3
```

## 4. 截图取证（每步操作后截图留证）

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
# 截图到设备临时目录 → 拉回项目 doc/ 下（方便 Read/vision 读取）
$ADB shell screencap -p /data/local/tmp/shot.png
$ADB pull /data/local/tmp/shot.png doc/shot.png
```

截图后必须**用 vision 能力读取确认内容**（不要假装看过）。如果主模型无视觉能力，委派 `vision-reader` 子代理读图。

## 5. UI 结构抓取（uiautomator dump）

Compose 页面部分元素可能不进 dump，但原生控件/文本可拿到坐标：

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
$ADB shell uiautomator dump /data/local/tmp/ui.xml
$ADB shell cat /data/local/tmp/ui.xml > doc/ui.xml
# 用 python 解析文本+坐标（Windows python 用项目内路径）
python -c "
import re
xml = open('doc/ui.xml', encoding='utf-8').read()
for m in re.finditer(r'text=\"([^\"]*)\"[^>]*bounds=\"(\[[^\"]*\])\"', xml):
    t, b = m.group(1), m.group(2)
    if t.strip(): print(f'{t}  {b}')
"
```

## 6. 模拟点击/输入/滑动（UI 交互测试）

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
# 点击（坐标从 UI dump 或截图估算；Compose 按钮坐标需先 dump 确认）
$ADB shell input tap X Y
# 输入文字（ASCII 安全；中文/特殊字符会被输入法转换，慎用）
$ADB shell input text 'example.com'
# 按键（返回/主页/Enter）
$ADB shell input keyevent 4        # BACK
$ADB shell input keyevent 3        # HOME
$ADB shell input keyevent 66       # ENTER
# 长按（模拟长按弹菜单，swipe 同点 1200ms）
$ADB shell input swipe 540 400 540 400 1200
# 滑动（滚动页面）
$ADB shell input swipe 540 2000 540 500 600
```

**坐标定位技巧**：先 uiautomator dump 拿元素 bounds（`[x1,y1][x2,y2]`），中心点 = `((x1+x2)/2, (y1+y2)/2)`。Compose 图标按钮常无独立节点，需从截图目测或试点。

## 7. 日志监控（logcat）

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
# 清空旧日志（开始新测试前）
$ADB logcat -c
# 抓崩溃（FATAL/崩溃/渲染进程错误）
$ADB logcat -d | grep -iE "FATAL|AndroidRuntime|Exception|SIGSEGV|SIGILL"
# 抓指定 Tag（如主题/WebView）
$ADB logcat -d -s ThemeUtils
# WebView 渲染/JS 错误（过滤站点自身噪音：alicdn/埋点等）
$ADB logcat -d | grep -iE "chromium|ERR_|didFailLoad" | grep -viE "AccessibilityUserState|Nl80211"
```

**判断标准**：`FATAL`/`AndroidRuntime`/`SIGSEGV` 出现 = 崩溃；`ERR_UNKNOWN_URL_SCHEME` = 未知 scheme 问题；`[INFO:CONSOLE]` 是站点 JS 噪音可忽略。

## 8. 内存/性能检查

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
# 进程内存（TOTAL PSS 是真实占用；4 个 WebView 约 250-300MB 正常）
$ADB shell dumpsys meminfo com.cylonid.nativealpha.debug | grep TOTAL
# WebView 实例数
$ADB shell dumpsys meminfo com.cylonid.nativealpha.debug | grep WebView
# 冷启动耗时
$ADB shell am force-stop com.cylonid.nativealpha.debug && sleep 1
$ADB shell am start -W -n com.cylonid.nativealpha.debug/com.cylonid.nativealpha.MainActivity | grep TotalTime
```

## 9. 录屏（视频/动画/手势测试）

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
# 录 5 秒屏幕（默认 1080p）
$ADB shell screenrecord --time-limit 5 /data/local/tmp/rec.mp4
$ADB pull /data/local/tmp/rec.mp4 doc/rec.mp4
```

## 10. 分辨率/密度（坐标换算参考）

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
$ADB shell wm size     # 如 Physical size: 1080x2424
$ADB shell wm density  # 如 Physical density: 420
```

截图坐标 = 屏幕像素坐标，UI dump 的 bounds 也是像素，直接对应。

## 11. 权限管理（相机/定位等运行时权限测试）

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
# 授予权限
$ADB shell pm grant com.cylonid.nativealpha.debug android.permission.CAMERA
# 撤销权限
$ADB shell pm revoke com.cylonid.nativealpha.debug android.permission.CAMERA
# 查看已授予权限
$ADB shell dumpsys package com.cylonid.nativealpha.debug | grep -A 20 "runtime permissions" | head -25
```

## 12. 通知检查

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
$ADB shell dumpsys notification --noredact | grep -E "NotificationRecord|pkg=" | head -10
```

## 13. 进程存活检查（判断崩溃/被杀）

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
# 返回 PID = 存活；空 = 已退出（崩溃或被系统杀）
$ADB shell pidof com.cylonid.nativealpha.debug
```

配合崩溃日志判断：`pidof` 空 + `logcat` 有 `FATAL` = 应用崩溃退出。

## 14. 安装失败处理

| 错误 | 原因 | 解决 |
|------|------|------|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不一致（debug/release 混装） | 先 `adb uninstall <pkg>` 再装 |
| `INSTALL_FAILED_ALREADY_EXISTS` | 已装同版本 | 加 `-r` 覆盖，或先卸载 |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | 存储不足 | `adb shell pm clear` 清理或扩容 AVD |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | ABI 不匹配 | 确认 APK 含 arm64（模拟器 x86_64 需 x86_64 或 universal） |

## 15. 查看持久化数据（SharedPreferences 验证）

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
# WebApp 数据（验证设置保存）
$ADB shell run-as com.cylonid.nativealpha.debug cat /data/data/com.cylonid.nativealpha.debug/shared_prefs/WEBSITEDATA.xml > doc/wd.xml
# 解析（注意 XML 实体转义，需 html.unescape）
python -c "
import re, json, html
data = open('doc/wd.xml', encoding='utf-8').read()
m = re.search(r'<string name=\"WEBSITEDATA\">(.*?)</string>', data, re.S)
if m:
    arr = json.loads(html.unescape(m.group(1)))
    for w in arr:
        print(f'ID={w[\"ID\"]} textZoom={w.get(\"textZoom\")}')
"
```

## 16. 三键导航 vs 手势条（insets 验证）

不同导航模式会影响底部 insets，测试必须都覆盖：

```bash
export MSYS_NO_PATHCONV=1
ADB=/d/software/ambient/android/platform-tools/adb
# 切三键导航
$ADB shell cmd overlay enable com.android.internal.systemui.navbar.threebutton
# 切回手势条
$ADB shell cmd overlay disable com.android.internal.systemui.navbar.threebutton
```

## 17. 常见坑（实测踩过）

| 坑 | 现象 | 解决 |
|----|------|------|
| 路径转换 | adb 报 `/data/local/tmp` 找不到 | 每条命令前 `export MSYS_NO_PATHCONV=1` |
| 包名后缀 | `Activity not started` | debug 包用 `.debug` 后缀 |
| 输入法转换 | `input text` 中文变全角 | 只输入 ASCII，或切系统语言 |
| Compose 节点 | uiautomator 拿不到 | 用截图+vision 确认，坐标试点 |
| 模拟器失联 | adb devices 空 | `adb kill-server && adb start-server` |
| python /tmp | Windows python 不认 /tmp | 文件拉回项目 doc/ 再解析 |
| Gradle 锁损坏 | 蓝屏后 build 报 lock protocol | 删 `gradleAppCache/caches/journal-1` 和项目 `.gradle` |
| 签名不一致 | `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 先 `adb uninstall` 再装 |
| 剪贴板验证 | `cmd clipboard get` 不可用 | 改用应用内反馈（Toast/日志）确认复制 |
| 包名错误 | run-as 报 `not debuggable` | debug 包才可 run-as，release 不行 |

## 18. 完整测试流程示例（子代理可直接套用）

```bash
# 1. 确认模拟器在线
adb devices
# 2. 装最新 APK + 清数据
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm clear com.cylonid.nativealpha.debug
# 3. 清日志 → 启动 → 等加载
adb logcat -c
adb shell am start -n com.cylonid.nativealpha.debug/com.cylonid.nativealpha.MainActivity
sleep 4
# 4. 截图取证
adb shell screencap -p /data/local/tmp/s1.png && adb pull /data/local/tmp/s1.png doc/s1.png
# 5. 交互（点卡片打开 WebView）
adb shell input tap 540 660
sleep 8
adb shell screencap -p /data/local/tmp/s2.png && adb pull /data/local/tmp/s2.png doc/s2.png
# 6. 查崩溃日志 + 进程存活
adb logcat -d | grep -iE "FATAL|AndroidRuntime" | head -5
adb shell pidof com.cylonid.nativealpha.debug   # 空 = 已崩溃退出
# 7. 截图用 vision 确认内容，UI dump 拿坐标
```

**验收纪律**：每步操作必须 ①截图 ②查日志，双证据闭环；无证据的"测试通过"不算数。
