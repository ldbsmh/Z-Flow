# 小窗引擎的 R8 规则（宿主 Z-Flow 打包时合并）。
#
# 引擎入口写在 META-INF/xposed/java_init.list 里，靠反射加载；
# 大量系统内部类/字段名也按字符串查找，混淆或内联会直接让 Hook 失效。
-keep class dev.local.sunwindowdemo.** { *; }
-keepnames class kotlin.** { *; }
-keepnames class androidx.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable

# libxposed API 101 由 LSPosed 在运行时注入，编译期 compileOnly，R8 里必然缺失。
-dontwarn io.github.libxposed.api.**
