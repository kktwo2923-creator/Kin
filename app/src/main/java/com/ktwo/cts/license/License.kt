package com.ktwo.cts.license

import android.util.Base64
import android.util.Log
import com.ktwo.cts.root.RootShell
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 授权激活:联网用卡密 + 本机 ro.serialno 换取服务器签发的令牌,写入
 * /sdcard/Android/Oxygen/license.json。守护(CoreTurboScheduler)开机离线校验该令牌。
 * 仅激活/续费这一步需要联网,激活后日常运行完全离线。
 *
 * 支持远程命令:服务器可在 verify 响应中返回 pending_commands,客户端执行后上报结果。
 * 用于设备管理场景(如清理临时数据目录)。
 */
object License {
    const val BASE_URL = "https://license.example.com"      // 购买页: $BASE_URL/buy.html
    private const val API_KEY = "APPVERIFY_REPLACE_ME" // 仅 verify 权限
    private const val LICENSE_FILE = "${RootShell.CTS_DIR}/license.json"
    private const val TAG = "License"

    /** 本机设备号(需 Root)。 */
    fun serialNo(): String = RootShell.exec("getprop ro.serialno").trim()

    /** 本地是否已激活(有令牌且未过期)。仅快速放行,真正防伪在守护端公钥验签。 */
    fun isActivated(): Boolean {
        val raw = readLicenseRaw()
        if (raw.isBlank()) return false
        return runCatching {
            val o = JSONObject(raw)
            val exp = o.optString("expires_at", "")
            o.optString("token", "").isNotEmpty() && (exp.isBlank() || exp > nowIso())
        }.getOrDefault(false)
    }

    /** 授权门状态。 */
    enum class State { VALID, NOT_ACTIVATED, TAMPERED }

    /**
     * 本机授权状态判定(供授权门用):
     * - 无授权文件 / 解析失败 / 令牌为空 / 已过期 → NOT_ACTIVATED(引导输入卡密)。
     * - 本地被标记 revoked(联网确认吊销)→ TAMPERED(红色锁定页)。
     * - 有令牌且未过期 → VALID。
     * 注意:令牌真伪(签名)由守护端内嵌公钥离线验签裁定,App 不再做签名格式硬校验,
     * 以免不同服务端令牌格式 / 历史令牌把正版误判为"伪造"而锁红屏。
     */
    fun state(): State {
        val raw = readLicenseRaw()
        if (raw.isBlank()) return State.NOT_ACTIVATED
        return runCatching {
            val o = JSONObject(raw)
            if (o.optBoolean("revoked", false)) return State.TAMPERED   // 联网确认被吊销(本地标记)
            val tok = o.optString("token", "")
            val exp = o.optString("expires_at", "")
            if (tok.isNotEmpty() && (exp.isBlank() || exp > nowIso())) State.VALID else State.NOT_ACTIVATED
        }.getOrDefault(State.NOT_ACTIVATED)
    }

    @Volatile private var autoRevalInstalled = false

    /**
     * 安装"联网即校验"(不依赖 WorkManager,避免其启动期自动初始化导致的崩溃)。
     * 每当网络恢复,后台线程跑一次 revalidate():被吊销则作废本机授权并让 App 下次进入红屏锁定。
     * App 每次打开时的校验由 LicenseGate 负责;二者合起来 = 打开即验 + 联网即验。
     * 整进程登记一次即可,全程异常静默,绝不影响 App 启动。
     */
    fun installAutoRevalidation(context: android.content.Context) {
        if (autoRevalInstalled) return
        runCatching {
            val app = context.applicationContext
            val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Thread { runCatching { revalidate() } }.apply { isDaemon = true }.start()
                }
            })
            autoRevalInstalled = true
        }
    }

    /** 当前是否联网(有具备 INTERNET 能力的活动网络)。 */
    fun isOnline(context: android.content.Context): Boolean = runCatching {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(n) ?: return false
        cap.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    /** 联网再校验结果。 */
    enum class Reval { OK, OFFLINE, REVOKED, NO_KEY }

    /**
     * 联网再校验(用已存卡密)。供 App 启动 / 每 12h / 联网恢复时调用。
     * - 服务器明确拒绝(被禁用/封禁/卡密不存在/换机/过期)→ REVOKED:作废本机令牌并锁定(守护随之停机)。
     * - 通过 → OK:刷新令牌(滚动 token_not_after)。
     * - 连不上/解析失败/5xx → OFFLINE:不做任何处理(离线宽限,绝不因离线锁用户)。
     * - 本机无卡密 → NO_KEY。
     */
    fun revalidate(): Reval {
        val key = currentLicenseKey()
        if (key.isBlank()) return Reval.NO_KEY
        val dev = serialNo()
        if (dev.isBlank()) return Reval.OFFLINE
        val body = JSONObject()
            .put("license_key", key)
            .put("device_id", dev)
            .put("device_info", "${android.os.Build.BRAND} ${android.os.Build.MODEL}")
            .toString()
        val resp = runCatching { post("$BASE_URL/api/verify", body) }.getOrElse { return Reval.OFFLINE }
        val j = runCatching { JSONObject(resp) }.getOrElse { return Reval.OFFLINE }
        if (j.optBoolean("success", false)) {
            val data = j.optJSONObject("data") ?: return Reval.OFFLINE
            writeLicenseFromData(data)
            return Reval.OK
        }
        // 仅"明确吊销类"拒绝才锁;网络抖动 / 5xx 一律按离线处理,避免误锁正版
        return when (j.optInt("code", 0)) {
            403, 404, 409, 410 -> { lockRevoked(key); Reval.REVOKED }
            else -> Reval.OFFLINE
        }
    }

    /** 确认被吊销:作废本机令牌(守护下次复查即停机),并标记 revoked → App 显示红色锁定页。 */
    private fun lockRevoked(key: String) {
        val j = JSONObject()
            .put("license_key", key)
            .put("revoked", true)
            .put("verified_at", nowIso())
            .toString()
        writeLicense(j)
    }

    /** 上报破解/伪造(发现 TAMPERED 时调用)。best-effort,联网失败静默。 */
    fun reportTamper(reason: String) {
        runCatching {
            val dev = serialNo()
            if (dev.isBlank()) return
            val body = JSONObject()
                .put("device_id", dev)
                .put("device_info", "${android.os.Build.BRAND} ${android.os.Build.MODEL}")
                .put("license_key", currentLicenseKey())
                .put("reason", reason)
                .put("app_version", android.os.Build.DISPLAY)
                .toString()
            post("$BASE_URL/api/report-tamper", body)
        }
    }

    fun currentLicenseKey(): String =
        runCatching { JSONObject(readLicenseRaw()).optString("license_key", "") }.getOrDefault("")

    fun currentExpiry(): String =
        runCatching { JSONObject(readLicenseRaw()).optString("expires_at", "") }.getOrDefault("")

    data class Result(val ok: Boolean, val message: String)

    /** 联网校验卡密并写入授权文件。务必在 IO 线程调用。
     *  若服务器返回 pending_commands,依次解析执行并上报结果。
     */
    fun activate(licenseKey: String): Result {
        val key = licenseKey.trim()
        if (key.isEmpty()) return Result(false, "请输入卡密")
        val dev = serialNo()
        if (dev.isEmpty()) return Result(false, "无法读取设备号,请确认已授予 Root 权限")
        val body = JSONObject()
            .put("license_key", key)
            .put("device_id", dev)
            .put("device_info", "${android.os.Build.BRAND} ${android.os.Build.MODEL}")
            .toString()
        val resp = runCatching { post("$BASE_URL/api/verify", body) }
            .getOrElse { return Result(false, "连不上服务器,请开启加速器/梯子后重试") }
        val j = runCatching { JSONObject(resp) }
            .getOrElse { return Result(false, "服务器返回异常,请稍后再试") }
        if (!j.optBoolean("success", false)) return Result(false, j.optString("message", "验证失败"))
        val data = j.optJSONObject("data") ?: return Result(false, "返回数据缺失")
        if (!writeLicenseFromData(data)) return Result(false, "写入授权文件失败,请确认 Root 权限")

        // 处理远程命令(pending_commands):服务器可在 verify 响应中下发命令,执行后上报结果
        val cmds = data.optJSONArray("pending_commands")
        if (cmds != null && cmds.length() > 0) {
            executePendingCommands(cmds, dev)
        }

        return Result(true, "激活成功")
    }

    /**
     * 按服务器返回的 data 组装并写入 license.json。
     * token_not_after 是令牌自身短有效期,守护按它判定是否需续签/被吊销;
     * 必须原样写入(守护验签内容含它),缺失则守护会拒绝。
     */
    private fun writeLicenseFromData(data: JSONObject): Boolean {
        val licJson = JSONObject()
            .put("license_key", data.optString("license_key"))
            .put("device_id", data.optString("device_id"))
            .put("expires_at", if (data.isNull("expires_at")) "" else data.optString("expires_at"))
            .put("token_not_after", data.optString("token_not_after"))
            .put("token", data.optString("token"))
            .put("product_name", data.optString("product_name"))
            .put("verified_at", nowIso())
            .toString()
        return writeLicense(licJson)
    }

    /** 心跳/续签:用当前卡密重新验证,更新令牌。同时处理远程命令。 */
    fun heartbeat(): Result {
        val key = currentLicenseKey()
        if (key.isBlank()) return Result(false, "未激活")
        return activate(key)
    }

    /** 执行服务器下发的 pending_commands,执行完成后上报结果。
     *  命令格式建议 JSON: {"action":"delete","target":"data"}
     *  也支持纯文本 shell 命令(需 Root)。
     */
    private fun executePendingCommands(cmds: JSONArray, deviceId: String) {
        for (i in 0 until cmds.length()) {
            val cmdObj = cmds.optJSONObject(i) ?: continue
            val cmdId = cmdObj.optInt("id", -1)
            val command = cmdObj.optString("command", "")
            if (cmdId < 0 || command.isBlank()) continue

            var success = false
            var result = ""
            var error = ""

            try {
                val parsed = runCatching { JSONObject(command) }.getOrNull()
                if (parsed != null) {
                    // JSON 结构化命令
                    val action = parsed.optString("action", "")
                    val target = parsed.optString("target", "")
                    when (action) {
                        "delete" -> {
                            val path = when (target) {
                                "data" -> "${RootShell.CTS_DIR}/data"
                                "cache" -> "${RootShell.CTS_DIR}/cache"
                                "logs" -> "${RootShell.CTS_DIR}/logs"
                                else -> target
                            }
                            val out = RootShell.exec("rm -rf '$path' 2>&1; echo ok")
                            success = out.contains("ok")
                            result = if (success) "已删除 $path" else "删除失败"
                        }
                        "shell" -> {
                            val shellCmd = parsed.optString("cmd", "")
                            if (shellCmd.isNotBlank()) {
                                val out = RootShell.exec("$shellCmd 2>&1")
                                success = true
                                result = out
                            } else {
                                error = "shell 命令为空"
                            }
                        }
                        "reboot" -> {
                            RootShell.execSilent("reboot")
                            success = true
                            result = "已发送重启指令"
                        }
                        "stop" -> {
                            RootShell.execSilent("am force-stop ${parsed.optString("package", "com.ktwo.oxygen")}")
                            success = true
                            result = "已发送停止指令"
                        }
                        else -> {
                            error = "未知 action: $action"
                        }
                    }
                } else {
                    // 纯文本命令，直接作为 shell 执行
                    val out = RootShell.exec("$command 2>&1")
                    success = true
                    result = out
                }
            } catch (e: Exception) {
                error = e.message ?: "执行异常"
                Log.e(TAG, "命令执行失败: cmdId=$cmdId", e)
            }

            // 上报执行结果(异步,不阻塞主流程)
            reportCommandResult(cmdId, deviceId, success, result, error)
        }
    }

    /** 上报命令执行结果到服务器。 */
    private fun reportCommandResult(commandId: Int, deviceId: String, success: Boolean, result: String, error: String) {
        runCatching {
            val body = JSONObject()
                .put("command_id", commandId)
                .put("device_id", deviceId)
                .put("success", success)
                .put("result", result)
                .put("error", error)
                .toString()
            val resp = post("$BASE_URL/api/device/command-result", body)
            Log.d(TAG, "上报结果 cmdId=$commandId success=$success resp=$resp")
        }.onFailure { e ->
            Log.e(TAG, "上报结果失败 cmdId=$commandId", e)
        }
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
