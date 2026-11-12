# 第三方素材与依赖声明（THIRD PARTY NOTICES）

WebNative 是 [NativeAlphaForAndroid](https://github.com/cylonid/NativeAlphaForAndroid)
的深度修改分支，整体依 GPL-3.0 授权（见 README「Credits & License」）。本文件
集中声明项目使用的第三方素材与依赖的许可信息；许可全文以各上游仓库为准。

## 1. 设计素材

| 素材 | 来源 / 作者 | 许可 | 修改说明 | 项目内位置 |
|---|---|---|---|---|
| 「Dog walking」加载动画（`lottie_dog_walk.json`） | LottieFiles，作者 Syed Asim Ali Shah | LottieFiles Free License（要求保留署名——本表即署名） | 移除地面横线 layer，其余未改 | `app/src/main/res/raw/lottie_dog_walk.json`，由 `WebViewPageChrome.startLoadingAnimal()` 播放 |

## 2. 分发物依赖（进入 APK）

以下依赖以 Apache-2.0 / MIT 授权发布，与 GPL-3.0 兼容。版本以
`gradle/libs.versions.toml`（Version Catalog）为准；license 信息实勘自各
Maven 坐标 pom（2026-11-07）。

**Android / AndroidX / Google 系（Apache-2.0）**：

- `com.android.tools.build:gradle`（AGP，构建工具链）
- `com.google.android.material:material`
- `androidx.core:core-ktx`、`androidx.core:core-splashscreen`
- `androidx.appcompat:appcompat`、`androidx.activity:activity-compose`
- `androidx.compose:compose-bom`（及经 BOM 引入的 ui/material3 系列构件）
- `androidx.lifecycle:lifecycle-runtime-ktx`、`androidx.fragment:fragment-ktx`
- `androidx.webkit:webkit`、`androidx.profileinstaller:profileinstaller`
- `androidx.datastore:datastore-preferences`、`androidx.palette:palette-ktx`
- `androidx.camera:camera-camera2` / `camera-lifecycle` / `camera-view`

**第三方库**：

- `org.jetbrains.kotlin:*`（Kotlin 工具链与标准库）—— Apache-2.0
- `org.jetbrains.kotlinx:kotlinx-coroutines-*` —— Apache-2.0
- `com.airbnb.android:lottie`（动画播放）—— Apache-2.0
- `io.noties.markwon:core`（Markdown 渲染）—— Apache-2.0
- `com.google.code.gson:gson`（JSON 序列化）—— Apache-2.0
- `com.google.zxing:core`（二维码）—— Apache-2.0（该 pom 未含 licenses 节点，
  以 [zxing 仓库 LICENSE](https://github.com/zxing/zxing/blob/master/LICENSE) 为准）
- `org.jsoup:jsoup`（HTML 解析）—— MIT

## 3. 仅测试依赖（不进入 APK，无分发义务）

- `junit:junit` —— Eclipse Public License 1.0
- `org.json:json` —— Public Domain
- `org.robolectric:robolectric` —— MIT
- `org.mockito:mockito-core`、`org.mockito.kotlin:mockito-kotlin` —— MIT
- `androidx.test:*` / `androidx.test.ext:*` / `androidx.test.espresso:*` —— Apache-2.0

## 4. 增补规则

新增素材（图片/动画/字体/音效）或新依赖时，必须在本文件补行声明
（来源/作者/许可/修改说明）；源码注释中的授权说明改为本文件的指针引用，
不重复维护两处。
