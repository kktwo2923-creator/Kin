package com.ktwo.cts.license

import android.util.Base64
import com.ktwo.cts.root.RootShell
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 授权激活:联网用卡密 + 本机 ro.serialno 换取服务器签发的令牌,写入
 * /sdcard/Android/CTS/license.json。守护(CoreTurboScheduler)开机离线校验该令牌。
 * 仅激活/续费这一步需要联网,激活后日常运行完全离线。
 */
object License {
    const val BASE_URL = "https://license.example.com"      // 购买页: $BASE_URL/buy.html
    private const val API_KEY = "APPVERIFY_REPLACE_ME" // 仅 verify 权限
    private const val LICENSE_FILE = "${RootShell.CTS_DIR}/license.json"

    /** 本机设备号(需 Root)。 */
    fun serialNo(): String = RootShell.exec("getprop ro.serialno").trim()

    /** 本地是否已激活(有令牌且未过期)。仅快速放行,真正防伪在守护端 HMAC 校验。 */
    fun isActivated(): Boolean {
        val raw = readLicenseRaw()
        if (raw.isBlank()) return false
        return runCatching {
            val o = JSONObject(raw)
            val exp = o.optString("expires_at", "")
            o.optString("token", "").isNotEmpty() && (exp.isBlank() || exp > nowIso())
        }.getOrDefault(false)
    }

    fun currentLicenseKey(): String =
        runCatching { JSONObject(readLicenseRaw()).optString("license_key", "") }.getOrDefault("")

    fun currentExpiry(): String =
        runCatching { JSONObject(readLicenseRaw()).optString("expires_at", "") }.getOrDefault("")

    data class Result(val ok: Boolean, val message: String)

    /** 联网校验卡密并写入授权文件。userNote 为用户填写的备注,仅上送给服务器供后台查看。务必在 IO 线程调用。 */
    fun activate(licenseKey: String, userNote: String = ""): Result {
        val key = licenseKey.trim()
        if (key.isEmpty()) return Result(false, "请输入卡密")
        val dev = serialNo()
        if (dev.isEmpty()) return Result(false, "无法读取设备号,请确认已授予 Root 权限")
        val body = JSONObject()
            .put("license_key", key)
            .put("device_id", dev)
            .put("device_info", "${android.os.Build.BRAND} ${android.os.Build.MODEL}")
            .put("user_note", userNote.trim())
            .toString()
        val resp = runCatching { post("$BASE_URL/api/verify", body) }
            .getOrElse { return Result(false, "连不上服务器,请开启加速器/梯子后重试") }
        val j = runCatching { JSONObject(resp) }
            .getOrElse { return Result(false, "服务器返回异常,请稍后再试") }
        if (!j.optBoolean("success", false)) return Result(false, j.optString("message", "验证失败"))
        val data = j.optJSONObject("data") ?: return Result(false, "返回数据缺失")
        val licJson = JSONObject()
            .put("license_key", data.optString("license_key"))
            .put("device_id", data.optString("device_id"))
            .put("expires_at", if (data.isNull("expires_at")) "" else data.optString("expires_at"))
            .put("token", data.optString("token"))
            .put("product_name", data.optString("product_name"))
            .put("verified_at", nowIso())
            .toString()
        if (!writeLicense(licJson)) return Result(false, "写入授权文件失败,请确认 Root 权限")
        return Result(true, "激活成功")
    }

    private fun readLicenseRaw(): String {
        runCatching {
            val f = java.io.File(LICENSE_FILE)
            if (f.canRead()) { val s = f.readText(); if (s.isNotBlank()) return s }
        }
        return RootShell.exec("cat $LICENSE_FILE 2>/dev/null")
    }

    private fun writeLicense(json: String): Boolean {
        val b64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return RootShell.execSilent(
            "mkdir -p ${RootShell.CTS_DIR}; echo '$b64' | base64 -d > $LICENSE_FILE; chmod 644 $LICENSE_FILE"
        )
    }

    private fun nowIso(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun post(urlStr: String, body: String): String {
        val c = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-API-Key", API_KEY)
        }
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        c.disconnect()
        return text
    }
}
