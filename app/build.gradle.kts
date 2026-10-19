@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- 防破解相关可配置项(通过 gradle 属性 / 环境变量传入) ----
// 发布签名证书指纹(SHA-256,小写 hex,无冒号)。留空 = 不启用重签名检测(fail-open),
//   这样 debug 构建与未配置的 CI 不会被误杀。配好发布密钥后用以下命令取指纹再填:
//   keytool -list -v -keystore <你的.jks> -alias <别名> | grep SHA256
//   去掉冒号转小写,作为   -PctsCertSha256=xxxx   传入(或写进 gradle.properties)。
val ctsCertSha256: String = (project.findProperty("ctsCertSha256") as String?)?.replace(":", "")?.lowercase() ?: ""
// 是否编译 native 防签名层(需要 NDK)。默认关,避免无 NDK 的 CI 构建失败。开启:  -PctsNative=true
val ctsNative: Boolean = (project.findProperty("ctsNative") as String?) == "true"
// 发布密钥库(从 CI secret 注入)。未配置则回退 debug 签名(此时签名指纹不稳定,检测自动放行)。
fun secret(name: String): String? =
    (project.findProperty(name) as String?) ?: System.getenv(name)
val releaseKeystore: String? = secret("CTS_KEYSTORE")
val hasReleaseKeystore: Boolean = !releaseKeystore.isNullOrBlank() && file(releaseKeystore).exists()

// ---- 私有部署地址/密钥(公开仓库不硬编码) ----
// 通过 -P 参数或 ~/.gradle/gradle.properties 注入, 源码里只有占位符:
//   ctsLicenseBase  授权/支付服务器      (默认 https://license.example.com)
//   ctsApiKey       客户端 API 密钥      (默认 APPVERIFY_REPLACE_ME)
//   ctsUpdateBase   更新服务器           (默认 https://update.example.com)
//   ctsModuleUrl    内核模块下载地址     (默认指向 GitHub Releases)
fun cfg(name: String, default: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default
val ctsLicenseBase: String = cfg("ctsLicenseBase", "https://license.example.com")
val ctsApiKey: String = cfg("ctsApiKey", "APPVERIFY_REPLACE_ME")
val ctsUpdateBase: String = cfg("ctsUpdateBase", "https://update.example.com")
val ctsModuleUrl: String = cfg("ctsModuleUrl", "https://github.com/kktwo2923-creator/Kin/releases")

android {
    namespace = "com.ktwo.cts"
    // miuix-blur 0.9.2 要求 compileSdk 37 (同 SukiSU-Ultra)
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }

    defaultConfig {
        applicationId = "com.ktwo.kin"
        // iOS26 液态玻璃依赖 AGSL RuntimeShader(Android 13+), 不再适配低版本
        minSdk = 33
        targetSdk = 35
        versionCode = 20000
        versionName = "2.0.0"
        vectorDrawables { useSupportLibrary = true }

        // P2-15: 只构建 arm64-v8a,收口 ABI 减小包体(目标设备均为 64 位 ARM)
        ndk { abiFilters += "arm64-v8a" }

        // 防破解:编译期固定签名指纹 + native 层开关,供 Integrity.kt 读取
        buildConfigField("String", "SIGNING_CERT_SHA256", "\"$ctsCertSha256\"")
        buildConfigField("boolean", "NATIVE_GUARD", ctsNative.toString())

        // ---- 单一版本(已合并原 公益版/捐赠版 两个 flavor)----
        // App 打开即用、不做整机卡密/联网/激活校验(NO_LICENSE=true);
        // 付费点仅为【功能级】的「智能版 Pro」解锁,走在线支付。EDITION 留空 = 不显示版本后缀。
        buildConfigField("boolean", "NO_LICENSE", "true")
        buildConfigField("String", "EDITION", "\"\"")

        // 私有部署地址/密钥: 由构建参数注入, 源码中只有占位符。
        buildConfigField("String", "LICENSE_BASE_URL", "\"$ctsLicenseBase\"")
        buildConfigField("String", "LICENSE_API_KEY", "\"$ctsApiKey\"")
        buildConfigField("String", "UPDATE_BASE_URL", "\"$ctsUpdateBase\"")
        buildConfigField("String", "MODULE_DOWNLOAD_URL", "\"$ctsModuleUrl\"")
        // 授权服务器是 HTTP 明文时才放行 cleartext; HTTPS/占位符时为 false(Android 默认安全姿态)。
        manifestPlaceholders["cleartextTraffic"] =
            if (ctsLicenseBase.startsWith("http://")) "true" else "false"
    }

    signingConfigs {
        // 仅当通过 CI secret/属性提供了密钥库才生效;否则 release 回退用 debug 签名。
        create("release") {
            if (hasReleaseKeystore) {
                storeFile = file(releaseKeystore!!)
                storePassword = secret("CTS_KEYSTORE_PASSWORD")
                keyAlias = secret("CTS_KEY_ALIAS")
                keyPassword = secret("CTS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有发布密钥库就用稳定的发布签名(签名指纹检测才有意义);否则回退 debug。
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    // native 防签名层(可选,需 NDK)。默认不参与构建,开启:  -PctsNative=true
    if (ctsNative) {
        externalNativeBuild {
            cmake { path = file("src/main/cpp/CMakeLists.txt") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 真实背景模糊(玻璃卡片) — Haze, Apache-2.0
    implementation("dev.chrisbanes.haze:haze:1.6.10")
    // SukiSU-Ultra 同款顶/底栏模糊 + 液态玻璃 backdrop 引擎 (Miuix KMP)
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.2")

    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")

    implementation("org.json:json:20240303")

    // Ed25519 验签(纯 Java, 全 Android 版本可用; 平台自带 Ed25519 需 API 34+, 而 minSdk=33)
    implementation("net.i2p.crypto:eddsa:0.3.0")
}
