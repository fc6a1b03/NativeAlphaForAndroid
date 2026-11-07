# theme-color 桥真机验证套件（WebView 壳栏色自适应）

**用途**：任何触碰 `WebThemeColorBridge` / 系统栏着色的改动，必须用本套件
在**真机 debug 包**（CDP + 截图像素）上回归——模拟器 WebView 137 测不出
实机 131 的内核怪癖（observer callback 内 setTimeout 永不执行），模拟器
仅作逻辑回归。验证判据一律用**状态栏像素**（screencap 采样），探针
（theme_signal src=t1..t7）作归因辅助，二者缺一不可。

## 测试页（本地 http.server + adb reverse tcp:8000）

| 页面 | 形态 | 验证要点 |
|---|---|---|
| double.html | 双条 media 限定 meta + html 深背景 | S1（colorScheme 注入双向）/ S2-a（背景亮度选条） |
| nometa.html | **无任何 theme-color meta** + 深背景 #222222 | S2-b（无声明极暗/极亮直接上栏——linux.do 登录深色页实锤形态，v2.4.14 失效形态） |
| single.html | 单条无 media meta | fallback 直通（zcode 形态） |
| bodyclass.html | **背景由 body class 驱动**（Discourse 形态） | F5：站点内切主题（改 body class）栏色跟随——html/head/系统事件全不动 |

## 验证矩阵（全部真机）

1. **t1 首读**：打开页面 → 状态栏像素 == 期望 meta 色
2. **t2 observer**：CDP 切背景/切 body class → 3s 内像素翻转，探针 src=t2
3. **S1**：CDP 注入 `documentElement.style.colorScheme` dark/light → 像素跟随
4. **S2 反向**：背景跨亮度阈值（0.45/0.55）切换 → 像素跟随；透明/中间带不动
5. **t7**：`adb shell cmd uimode night yes/no`（**先读原值测完恢复**）→ 像素正确
6. **t7 活性**：探针日志出现 src=t7（run-as 读 datastore）
7. **回归**：single/double/bodyclass 三形态互切站点，栏色各自正确

## 已定谳的内核怪癖（勿回退）

- **WebView 131**：observer callback 内注册的 setTimeout 永不执行
  （cb:1/inner:0）——防抖形态即死因，callback 必须同步直读
- 判据三级：S1 colorScheme 明确 → 按声明匹配；S2 无信号 → 背景亮度
  （0.45~0.55 中间带不动、透明放弃）；无 meta 站点恒不干预
- 系统 matchMedia **彻底不参与 meta 选条**（Discourse 深浅与系统无关）
