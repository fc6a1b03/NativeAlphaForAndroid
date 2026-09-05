# WebNative ProGuard/R8 规则

# === Gson 反射序列化（模型类必须保留字段） ===
# WebApp/GlobalSettings 通过 Gson 反射读写，混淆会破坏 JSON 结构
-keep class com.cylonid.nativealpha.model.** { *; }
# 矩阵会话模型同款（P4）：@Keep 注解不足以保住 Gson 反射字段名，
# release 实测恢复回退默认布局——精确 keep 两个 data 类，不宽化到包级
-keep class com.cylonid.nativealpha.matrix.MatrixSessionState { *; }
-keep class com.cylonid.nativealpha.matrix.MatrixCellState { *; }
# 事件规则模型（P5）：Gson 反射同款 + JS 桥 @JavascriptInterface 成员
-keep class com.cylonid.nativealpha.webevent.EventRule { *; }

# 统计页 Gson 模型（v2.3.5 实机 ClassCastException 修复）：与 model.** 同理，
# 字段名混淆后 Gson 解析 List<WebVitalsEntry> 元素退化为 LinkedTreeMap，
# UI 访问字段时强转崩溃——精确 keep 四个模型类，不宽化到包级
-keep class com.cylonid.nativealpha.util.WebVitalsEntry { *; }
-keep class com.cylonid.nativealpha.util.WebVitalsMap { *; }
-keep class com.cylonid.nativealpha.util.StatsDailyStore$DayEntry { *; }
-keep class com.cylonid.nativealpha.util.StatsDailyStore$Snapshot { *; }
-keep class com.cylonid.nativealpha.util.StatsDailyStore$Raw { *; }
-keepclassmembers class com.cylonid.nativealpha.webevent.WebEventBridge {
    public *;
}
-keepattributes Signature
-keepattributes *Annotation*

# === jsoup（HTML 解析） ===
# jsoup 编译期引用 javax.annotation 注解，运行时不需要，R8 需忽略缺失类
-dontwarn javax.annotation.**
# jsoup 仅用于添加向导 HTML 解析（无反射调用点），交 R8 按可达性裁剪
-dontwarn org.jsoup.**

# === Baseline Profile ===
# profileinstaller 需要保留 profile 相关类（R8 否则裁剪导致 profile 失效）
-dontwarn androidx.profileinstaller.**

# === WebView JS 接口（如后续添加） ===
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# 保留行号信息便于崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
