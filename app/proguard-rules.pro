# ============ libsu(root shell;内部用反射加载 RootService) ============
-keep class com.topjohnwu.superuser.** { *; }
-keep class * extends com.topjohnwu.superuser.ipc.RootService { *; }

# ============ org.json(平台自带,稳妥起见消警告) ============
-dontwarn org.json.**

# ============ Kotlin / 协程 ============
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ============ AndroidX ViewModel(CpuControlVm 由框架反射构造) ============
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# ============ 应用数据模型(供 org.json 反射读写时稳妥保留) ============
-keep class com.ktwo.cts.data.** { *; }

# ============ Compose / Haze ============
-dontwarn androidx.compose.**
-keep class dev.chrisbanes.haze.** { *; }
-dontwarn dev.chrisbanes.haze.**

# ============ 防破解:JNI 绑定 + 混淆强化 ============
# JNI 通过「全限定类名 + 方法名」绑定 C 符号(Java_com_ktwo_cts_license_Integrity_nativeVerify),
# 因此 Integrity 类名与其 native 方法名必须保留,否则 native 层失效。
-keepclasseswithmembernames class com.ktwo.cts.license.Integrity {
    native <methods>;
}
# 通用:任何 native 方法名都保留(JNI 需要)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 更激进的混淆:打平包名、允许改访问修饰符,抬高逆向阅读成本
-allowaccessmodification
-repackageclasses ''
-optimizationpasses 5

# release 去掉调试日志,减少给攻击者的线索(保留 w/e 便于排障)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
