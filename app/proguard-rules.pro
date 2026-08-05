# ============ libsu(root shell;内部用反射加载 RootService) ============
-keep class com.topjohnwu.superuser.** { *; }
-keep class * extends com.topjohnwu.superuser.ipc.RootService { *; }

# ============ org.json(平台自带,稳妥起见消警告) ============
-dontwarn org.json.**

# ============ Kotlin / 协程 ============
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ============ Ed25519 验签库(net.i2p.crypto:eddsa, License.kt 直接类调用) ============
-keep class net.i2p.crypto.eddsa.** { *; }
# 该库编译时引用了 JDK 内部类 sun.security.x509.X509Key(仅 JCA 互操作用, Android 无此包), R8 会误报缺失类, 忽略
-dontwarn sun.security.**

# ============ AndroidX ViewModel(CpuControlVm 由框架反射构造) ============
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# ============ 应用数据模型(供 org.json 反射读写时稳妥保留) ============
-keep class com.ktwo.cts.data.** { *; }

# ============ Compose / Haze ============
-dontwarn androidx.compose.**
-keep class dev.chrisbanes.haze.** { *; }
-dontwarn dev.chrisbanes.haze.**

# ============ Rust native 采样库(libctssampler.so)JNI 桥 ============
# JNI 按「全限定类名 + 方法名」绑定符号(Java_com_ktwo_cts_nativebridge_NativeSampler_*),
# R8 改名会导致 UnsatisfiedLinkError, 故整类保留。
-keep class com.ktwo.cts.nativebridge.NativeSampler { *; }

# ============ 防破解:JNI 绑定 + 混淆强化 ============
# JNI 通过「全限定类名 + 方法名」绑定 C 符号(如 Java_com_ktwo_cts_license_Integrity_nativeVerify),
# 故所有含 native 方法的类(含 Integrity、NativeSampler)的类名与方法名都需保留。
# 下方通用规则已覆盖 Integrity 等所有含 native 方法的类,无需单独 keep。
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
