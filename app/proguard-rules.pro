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
