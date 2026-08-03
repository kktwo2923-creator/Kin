package com.ktwo.cts.license

import android.util.Base64
import com.ktwo.cts.root.RootShell
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * 授权激活:联网用卡密 + 本机 ro.serialno 换取服务器签发的令牌,写入
 * /sdcard/Android/Kin/license.json。守护(Kinetic)开机离线校验该令牌。
 * 仅激活/续费这一步需要联网,激活后日常运行完全离线。
 *
 * 支持远程命令:服务器可在 verify 响应中返回 pending_commands,客户端执行后上报结果。
 * 用于设备管理场景(如清理临时数据目录)。
 */
object License {
    // 国内自建服务器(纯 IP, HTTP 明文): 安全靠服务端限流 + 守护端 Ed25519 验签兜底。
    // 部署后把下面的 IP:端口 改成你的服务器地址。HTTP 明文已放行(见 network_security_config.xml)。
    // 购买页: $BASE_URL/buy.html
    const val BASE_URL = "http://license.example.com"
    // ⚠️ 安全风险(P2-16): API_KEY 硬编码在客户端, 反编译即可获取。当前仅 verify 权限且服务端可按 device_id
    // 限流兜底, 风险可控。后续若引入更高权限操作(如退款/封禁)必须迁移到服务端代理转发, 客户端
    // 只持短期令牌。所有 verify/report 请求已携带 device_id(=ro.serialno), 服务端可据此实施:
    //   - 单设备 QPS 限流(防爆破/防雪崩)
    //   - 异常 device_id 黑名单(发现伪造 serialno 批量激活时封禁)
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
     *
     * ⚠️ 安全依赖(P0-3): 此函数不验 token 签名, 攻击者写一份 {"token":"x","revoked":false,
     *  expires_at":""} 到 /sdcard license.json 即得 VALID。安全完全依赖守护端对 license.json
     *  的 token 做公钥验签——守护必须独立验签, 不可仅信任 App 写来的 auth.json(见 P0-2)。
     *  当前 NO_LICENSE=true 下 LicenseGate 不触发, 但 Pro 功能付费点(Pro.kt)仍调
     *  pushEntitlementToDaemon, 伪造 license.json 可解锁 Pro。完整修复需:
     *    ① 守护侧强制对 license.json token 验签(TODO, 需改 native);
     *    ② 验签结果回执给 App 作为唯一真值, App 不再自行判 VALID;
     *    ③ native fail-closed(见 Integrity, P1-m)防 patch 绕过。
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
    // 联网即校验的协程作用域 + 互斥锁(P2-4): 合并 onAvailable 抖动产生的并发 revalidate,
    // 同一时刻只跑一次校验, 避免网络反复切换时裸 Thread 雪崩打服务器。
    private val revalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val revalMutex = Mutex()
    // App context 供 reportTamper 落盘(P1-m): object 无 ctx, 由 installAutoRevalidation 注入
    @Volatile private var appCtx: android.content.Context? = null
    private const val TAMPER_QUEUE = "tamper_queue.log"

    /**
     * 安装"联网即校验"(不依赖 WorkManager,避免其启动期自动初始化导致的崩溃)。
     * 每当网络恢复,后台协程跑一次 revalidate():被吊销则作废本机授权并让 App 下次进入红屏锁定。
     * App 每次打开时的校验由 LicenseGate 负责;二者合起来 = 打开即验 + 联网即验。
     * 整进程登记一次即可,全程异常静默,绝不影响 App 启动。
     * 用 tryLock 合并(P2-4): 若已有 revalidate 在跑则跳过本次(下一次联网切换自然会再触发)。
     */
    fun installAutoRevalidation(context: android.content.Context) {
        if (autoRevalInstalled) return
        runCatching {
            val app = context.applicationContext
            appCtx = app   // 供 reportTamper 落盘 + flushTamperQueue 使用
            val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    revalScope.launch {
                        if (revalMutex.tryLock()) {
                            try {
                                // 先补传历史 tamper 队列(P1-m): 断网期间的破解行为落盘了, 联网时补传
                                runCatching { flushTamperQueue() }
                                runCatching { revalidate() }
                            } finally { revalMutex.unlock() }
                        }
                    }
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

    /**
     * 上报破解/伪造(发现 TAMPERED 时调用)。
     * best-effort 联网上报;失败时落盘到 filesDir/tamper_queue.log, 联网恢复时由 flushTamperQueue 补传(P1-m)。
     * 原实现失败即静默丢弃, 断网破解行为完全不上报, 作者对破解分布失明。
     */
    fun reportTamper(reason: String) {
        val ok = runCatching {
            val dev = serialNo()
            if (dev.isBlank()) return@runCatching false
            val body = JSONObject()
                .put("device_id", dev)
                .put("device_info", "${android.os.Build.BRAND} ${android.os.Build.MODEL}")
                .put("license_key", currentLicenseKey())
                .put("reason", reason)
                .put("app_version", android.os.Build.DISPLAY)
                .toString()
            post("$BASE_URL/api/report-tamper", body)
            true
        }.getOrDefault(false)
        if (!ok) {
            // 落盘待补传(P1-m): 每行一个 reason(时间戳 + reason), 联网恢复时 flushTamperQueue 逐行补传后清空
            runCatching {
                val ctx = appCtx ?: return@runCatching
                val line = "${nowIso()}\t$reason\n"
                java.io.File(ctx.filesDir, TAMPER_QUEUE).appendText(line)
            }
        }
    }

    /** 补传断网期间落盘的 tamper 上报, 成功后清空队列。由 installAutoRevalidation 的 onAvailable 回调触发(P1-m)。 */
    private fun flushTamperQueue() {
        val ctx = appCtx ?: return
        val f = java.io.File(ctx.filesDir, TAMPER_QUEUE)
        if (!f.exists() || f.length() == 0L) return
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        var allOk = true
        for (line in lines) {
            val reason = line.substringAfter('\t', line)   // 去掉时间戳前缀
            if (reason.isBlank()) continue
            val ok = runCatching {
                val dev = serialNo()
                if (dev.isBlank()) return@runCatching false
                val body = JSONObject()
                    .put("device_id", dev)
                    .put("device_info", "${android.os.Build.BRAND} ${android.os.Build.MODEL}")
                    .put("license_key", currentLicenseKey())
                    .put("reason", reason)
                    .put("app_version", android.os.Build.DISPLAY)
                    .toString()
                post("$BASE_URL/api/report-tamper", body); true
            }.getOrDefault(false)
            if (!ok) { allOk = false; break }
        }
        if (allOk) runCatching { f.delete() }
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

    /** 捐赠站默认套餐 code(永久),供订单号自动发卡用。 */
    private const val DEFAULT_PLAN_CODE = "forever"

    /**
     * 用「付款订单号」解锁:把订单号提交到服务端(有申请就自动发卡),取回卡密后立即激活本机。
     * 服务端 /api/order/submit 在 AUTO_APPROVE 下会直接返回 data.license_key。
     * 务必在 IO 线程调用。
     */
    fun redeemByOrder(orderNo: String): Result {
        val no = orderNo.trim()
        if (no.isEmpty()) return Result(false, "请输入付款订单号")
        val body = JSONObject()
            .put("plan_code", DEFAULT_PLAN_CODE)
            .put("pay_type", "alipay")
            .put("order_no", no)
            .toString()
        val resp = runCatching { post("$BASE_URL/api/order/submit", body) }
            .getOrElse { return Result(false, "连不上服务器,请开启加速器/梯子后重试") }
        val j = runCatching { JSONObject(resp) }
            .getOrElse { return Result(false, "服务器返回异常,请稍后再试") }
        if (!j.optBoolean("success", false)) {
            return Result(false, j.optString("message", "该订单号暂不可用,请确认后重试"))
        }
        val key = j.optJSONObject("data")?.optString("license_key", "").orEmpty()
        if (key.isBlank()) return Result(false, "订单已受理,但未取得卡密,请稍后重试")
        // 拿到卡密后走正常激活(绑定本机 + 写授权文件)
        return activate(key)
    }

    /** 在线支付下单结果: 收银台跳转地址 + 商户订单号(用于后续轮询查询)。 */
    data class PayOrder(val payUrl: String, val outTradeNo: String)

    /**
     * 在线支付下单(对齐 Metric 0.4.3): 调 /api/pay/create 建订单, 返回收银台地址与订单号。
     * App 打开 payUrl 让用户付款, 之后轮询 [queryPayAndActivate]。务必 IO 线程调用。
     * @param payType "alipay" 或 "wxpay"
     */
    fun createPayOrder(payType: String): PayOrder? {
        val body = JSONObject()
            .put("plan_code", DEFAULT_PLAN_CODE)
            .put("pay_type", if (payType == "wxpay") "wxpay" else "alipay")
            .toString()
        val resp = runCatching { post("$BASE_URL/api/pay/create", body) }.getOrNull() ?: return null
        val j = runCatching { JSONObject(resp) }.getOrNull() ?: return null
        if (!j.optBoolean("success", false)) return null
        val d = j.optJSONObject("data") ?: return null
        val url = d.optString("pay_url", "")
        val no = d.optString("out_trade_no", "")
        return if (url.isNotBlank() && no.isNotBlank()) PayOrder(url, no) else null
    }

    /**
     * 轮询查询订单支付状态(对齐 Metric 0.4.3 的 /epay/query): 已支付则拿回卡密并激活本机。
     * 返回: Result(true,...) = 已支付并激活; Result(false,"PENDING") = 未支付(继续轮询);
     *       其余 Result(false, 文案) = 出错。务必 IO 线程调用。
     */
    fun queryPayAndActivate(outTradeNo: String): Result {
        val body = JSONObject().put("out_trade_no", outTradeNo).toString()
        val resp = runCatching { post("$BASE_URL/api/pay/query", body) }
            .getOrElse { return Result(false, "PENDING") }   // 网络抖动当未付, 下轮再试
        val j = runCatching { JSONObject(resp) }.getOrElse { return Result(false, "PENDING") }
        val d = j.optJSONObject("data")
        if (d == null || !d.optBoolean("paid", false)) return Result(false, "PENDING")
        val key = d.optString("license_key", "")
        if (key.isBlank()) return Result(false, "已支付但未取得卡密, 请稍后在'订单号解锁'重试")
        return activate(key)
    }

    /**
     * 执行服务器下发的 pending_commands,执行完成后上报结果。
     * 命令格式: JSON {"action":"delete","target":"data"}。
     * ⚠️ 安全(P0-4): target 强制白名单 + 移除 shell action + 非 JSON 拒绝。
     * 原实现支持纯文本 shell 命令和任意 target, 服务器被攻破即可在所有设备上任意执行命令/删除任意路径。
     */
    private fun executePendingCommands(cmds: JSONArray, deviceId: String) {
        // P0-4: target 白名单——只允许删除这几个已知目录, 杜绝任意路径删除
        val deleteTargets = mapOf(
            "data" to "${RootShell.CTS_DIR}/data",
            "cache" to "${RootShell.CTS_DIR}/cache",
            "logs" to "${RootShell.CTS_DIR}/logs",
        )
        for (i in 0 until cmds.length()) {
            val cmdObj = cmds.optJSONObject(i) ?: continue
            val cmdId = cmdObj.optInt("id", -1)
            val command = cmdObj.optString("command", "")
            if (cmdId < 0 || command.isBlank()) continue

            var success = false
            var result = ""
            var error = ""

            try {
                // P0-4: 非 JSON 拒绝(不再支持纯文本 shell 命令, 防服务器被攻破后任意命令执行)
                val parsed = JSONObject(command)
                val action = parsed.optString("action", "")
                val target = parsed.optString("target", "")
                when (action) {
                    "delete" -> {
                        // P0-4: target 必须在白名单内, 否则拒绝执行
                        val path = deleteTargets[target]
                        if (path == null) {
                            error = "拒绝的 target: $target"
                        } else {
                            val out = RootShell.exec("rm -rf '$path' 2>&1; echo ok")
                            success = out.contains("ok")
                            result = if (success) "已删除 $path" else "删除失败"
                        }
                    }
                    // P0-4: 移除 shell action——服务器不应能下发任意 shell 命令
                    "reboot" -> {
                        RootShell.execSilent("reboot")
                        success = true
                        result = "已发送重启指令"
                    }
                    "stop" -> {
                        RootShell.execSilent("am force-stop ${parsed.optString("package", "com.ktwo.kin")}")
                        success = true
                        result = "已发送停止指令"
                    }
                    else -> {
                        error = "未知 action: $action"
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "执行异常"
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
            post("$BASE_URL/api/device/command-result", body)
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
        val ok = RootShell.execSilent(
            "mkdir -p ${RootShell.CTS_DIR}; echo '$b64' | base64 -d > $LICENSE_FILE; chmod 644 $LICENSE_FILE"
        )
        // 授权文件每次变更(激活/续签/吊销)都把付费状态同步给守护 —— App 是付费权威,
        // 守护据此「知道」是否已付费, 不再自行读/验签 license.json。
        if (ok) pushEntitlementToDaemon()
        return ok
    }

    /**
     * 把当前付费状态下发给守护(写 auth.json)。App 是付费权威, 守护只信任该信号且无条件运行。
     * 付费真值 = 本机授权令牌有效(state()==VALID)。务必在 IO 线程调用。
     */
    fun pushEntitlementToDaemon() {
        runCatching {
            val paid = state() == State.VALID
            val o = runCatching { JSONObject(readLicenseRaw()) }.getOrNull()
            val plan = o?.optString("product_name", "").orEmpty()
            val exp = o?.optString("expires_at", "").orEmpty()
            RootShell.writeDaemonAuth(paid, plan, exp)
        }
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
