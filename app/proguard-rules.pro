# WebNative ProGuard/R8 规则

# === Gson 反射序列化（模型类必须保留字段） ===
# WebApp/GlobalSettings 通过 Gson 反射读写，混淆会破坏 JSON 结构
-keep class com.cylonid.nativealpha.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# === Compose ===
# Compose 运行时依赖注解保留
-keep class androidx.compose.** { *; }

# === aboutlibraries（开源声明） ===
-keep class com.mikepenz.aboutlibraries.** { *; }
-keep class .R
-keep class **.R$* {
    <fields>;
}

# === WebView JS 接口（如后续添加） ===
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# 保留行号信息便于崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
