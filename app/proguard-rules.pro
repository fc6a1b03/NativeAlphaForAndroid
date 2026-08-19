# WebNative ProGuard/R8 规则

# === Gson 反射序列化（模型类必须保留字段） ===
# WebApp/GlobalSettings 通过 Gson 反射读写，混淆会破坏 JSON 结构
-keep class com.cylonid.nativealpha.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# === jsoup（HTML 解析） ===
# jsoup 编译期引用 javax.annotation 注解，运行时不需要，R8 需忽略缺失类
-dontwarn javax.annotation.**
-keep class org.jsoup.** { *; }

# === Compose ===
# Compose 运行时依赖注解保留
-keep class androidx.compose.** { *; }

# === WebView JS 接口（如后续添加） ===
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# 保留行号信息便于崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
