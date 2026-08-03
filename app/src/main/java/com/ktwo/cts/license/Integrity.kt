package com.ktwo.cts.license

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.ktwo.cts.BuildConfig
import java.security.MessageDigest

/**
 * 防重打包/重签名:运行时核对本应用「签名证书」的 SHA-256 是否等于编译期固定的指纹。
 *
 * 原理:破解版要改 APK 就必须重新签名(攻击者拿不到你的发布私钥),签名证书随之改变,
 * 指纹对不上即可识别。配合 R8 混淆,让「随手改一改再签名」失效。
 *
 * 双层校验:Kotlin 一层 + (可选)JNI native 一层 —— native 比 dex 更难 patch。
 *
 * ⚠️ 前提:必须用「固定的发布密钥库」签名(不能用每次都变的 debug key)。
 *   - 没配置指纹(BuildConfig.SIGNING_CERT_SHA256 为空)时本检查「放行」(fail-open),
 *     这样 debug 构建与未配置 CI 不会被误杀。
 *   - 配好发布签名后,把证书指纹填进 gradle 属性 ctsCertSha256(见 app/build.gradle.kts 注释),
 *     检查才真正生效。
 */
object Integrity {

    @Volatile private var nativeLoaded = false

    init {
        if (BuildConfig.NATIVE_GUARD) {
            nativeLoaded = runCatching { System.loadLibrary("ctsguard") }.isSuccess
        }
    }

    /** true = 签名可信(或未配置指纹时放行);false = 检测到重签名/篡改。 */
    fun verify(context: Context): Boolean {
        val pin = BuildConfig.SIGNING_CERT_SHA256.trim().lowercase()
        if (pin.isEmpty()) {
            // P2-7: release 构建未配置完整性校验 pin → 写一条告警日志(不崩溃);debug/未配置 CI 仍放行
            if (!BuildConfig.DEBUG) {
                Log.w("Integrity", "release 构建未配置完整性校验 pin(SIGNING_CERT_SHA256 为空), 重签名检测未生效")
            }
            return true
        }
        val actual = currentSignatureSha256(context)
        if (actual == null) {
            return false
        }
        val kotlinOk = constantTimeEquals(actual, pin)
        // native 层独立再判一次(已加载时);未编译 native 则只看 Kotlin 层
        val nativeOk = if (BuildConfig.NATIVE_GUARD && nativeLoaded) {
            runCatching { nativeVerify(rawSignature(context) ?: ByteArray(0), pin) }.getOrDefault(false)
        } else {
            // P1-m fail-closed: release 构建 + 非公益版(NO_LICENSE=false) + native .so 缺失 → 不放行,
            // 防剥离 .so 绕过 native 校验层;debug 构建 / 公益版(NO_LICENSE=true)允许 native 缺失(开发与公益环境)
            !( !BuildConfig.DEBUG && !BuildConfig.NO_LICENSE && !nativeLoaded )
        }
        return kotlinOk && nativeOk
    }

    /** 本应用签名证书的 SHA-256(小写 hex);多签名者时任一匹配即可。 */
    private fun currentSignatureSha256(context: Context): String? {
        val raw = rawSignature(context) ?: return null
        return sha256Hex(raw)
    }

    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun rawSignature(context: Context): ByteArray? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val si = info.signingInfo ?: return null
        val signers = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        signers?.firstOrNull()?.toByteArray()
    }.getOrNull()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    /** native 层:对证书 DER 计算 SHA-256 并与 pinHex 比对(实现见 src/main/cpp/ctsguard.cpp)。 */
    private external fun nativeVerify(certDer: ByteArray, pinHex: String): Boolean
}
