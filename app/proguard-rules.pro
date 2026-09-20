# Xposed 入口：assets/xposed_init 与 META-INF/xposed/java_init.list 按类名反射加载，
# R8 静态分析看不到该引用，必须显式保留
-keep class io.github.angbang852.manjiao.Module { *; }

# libxposed API：api 包由 LSPosed 运行时注入（compileOnly），interface/service 包随
# APK 打包且被 Manifest/Service 反射使用——全部保名
-keep class io.github.libxposed.api.** { *; }
# ★ service 包此前遗漏（审阅 2026-09 P2）：XposedProvider/XposedService 走
# Manifest authorities + 远程接口反射，被 R8 改名会静默失效
-keep class io.github.libxposed.service.** { *; }
-dontwarn io.github.libxposed.**
# minify 后保留行号便于线上排障
-keepattributes SourceFile,LineNumberTable

# JNI 按名解析：loghook.cpp 通过 Java_io_github_angbang852_manjiao_hook_PerfHook_nativeInitLogHook
# 符号查找方法，混淆后会 UnsatisfiedLinkError
-keepclasseswithmembernames class io.github.angbang852.manjiao.** { native <methods>; }
