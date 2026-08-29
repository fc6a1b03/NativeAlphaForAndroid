# WebNative ProGuard/R8 规则

# === Gson 反射序列化（模型类必须保留字段） ===
# WebApp/GlobalSettings 通过 Gson 反射读写，混淆会破坏 JSON 结构
-keep class com.cylonid.nativealpha.model.** { *; }
# 矩阵会话模型同款（P4）：@Keep 注解不足以保住 Gson 反射字段名，
# release 实测恢复回退默认布局——精确 keep 两个 data 类，不宽化到包级
-keep class com.cylonid.nativealpha.matrix.MatrixSessionState { *; }
-keep class com.cylonid.nativealpha.matrix.MatrixCellState { *; }
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
