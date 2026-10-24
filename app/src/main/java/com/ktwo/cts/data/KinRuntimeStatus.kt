package com.ktwo.cts.data

/**
 * Shared, tolerant view of daemon auto_status.txt.
 *
 * 数据对齐口径（与 Kin-Daemon 的 STATUS_PROTOCOL.md 一致）：
 * - 缺键/空值/老 schema 一律回落到默认值，绝不因为某个键缺失就判定整份无效。
 * - 所有 `*_x1000` 类字段（alpha/conf/consistency/loadgov_perf_*）是千分比整数。
 * - 这些值是**决策目标**，不是硬件写确认；`lastWrite*` 只是守护进程写入器的结果。
 * - schema_version 3 新增 loadgov_* 与 fas_frame_*（负载闭环 / FAS 帧级分析）。
 */
data class AutoStatus(
    val schemaVersion: Int = 1,
    val seq: Long = -1L,
    val timestamp: Long = -1L,
    val resolved: String = "",
    val fsm: String = "",
    val bucket: Int = -1,
    val daypart: Int = -1,
    val alpha: Int = -1,
    val alphaRaw: Int = -1,
    val conf: Int = -1,
    val temp: Int = -1,
    val batt: Int = -1,
    val gpu: Int = -1,
    val load1: Float = -1f,
    val cpuBusy: Int = -1,
    val curMa: Int = -1,
    val powerW: Int = -1,
    val nightSleep: Int = -1,
    val nightWake: Int = -1,
    val nightSamples: Int = 0,
    val cpuMaxLittle: Int = 0,
    val cpuMaxBig: Int = 0,
    val charging: Boolean = false,
    val screen: Int = 1,
    val lowbat: Boolean = false,
    val headroom: Int = -1,
    val headroomValid: Boolean = false,
    val owner: String = "",
    val reason: String = "",
    val event: String = "",
    val foregroundPackage: String = "",
    val foregroundCategory: String = "",
    val foregroundSource: String = "",
    // ── 厂商压频 / anti-clamp 遥测 ──
    val softClamp: Boolean = false,
    val hardClamp: Boolean = false,
    val vendorClamp: Boolean = false,
    val vendorClamp1h: Int = 0,
    val anticlampAttack: Boolean = false,
    val anticlampAttackTotal: Long = 0L,
    // ── Smart 学习诊断 ──
    val explore: Int = 0,
    val baseCost: Int = -1,
    val consistency: Int = -1,
    // ── 策略解析诊断（schema 2 起）──
    val resolvedPolicy: String = "",
    val policyReason: String = "",
    val policyScope: String = "",
    val fasState: String = "",
    val writeVerification: String = "",
    val lastWriteScope: String = "",
    val lastWriteResult: String = "",
    val lastWriteNodes: String = "",
    val lastWriteFailed: String = "",
    val batteryState: String = "",
    val thermalState: String = "",
    // ── 负载闭环调频（schema 3）──
    // loadGovPerfLittle/Big 是闭环自己算出的该簇上限（千分比），
    // 与 cpuMaxLittle/Big（最终落盘值）不等时，说明闭环意图被 FAS/类别/热/PolicyResolver 又收紧了。
    val loadGovEnable: Boolean = false,
    val loadGovBusyLittle: Int = -1,
    val loadGovBusyBig: Int = -1,
    val loadGovPerfLittle: Int = -1,
    val loadGovPerfBig: Int = -1,
    // ── FAS 帧级分析（schema 3）──
    val fasFrameAnalysis: Boolean = false,
    val fasLoading: Boolean = false,
    val fasPostGuard: Int = 0,
    val fasGearDwell: Int = 0,
    val fasFrameSource: String = "",
    val stale: Boolean = false,
    val valid: Boolean = false,
) {
    companion object {
        fun parse(raw: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): AutoStatus {
            if (raw.isBlank()) return AutoStatus()
            val values = raw.lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()
            fun intValue(key: String, default: Int = -1) = values[key]?.toIntOrNull() ?: default
            fun longValue(key: String, default: Long = -1L) = values[key]?.toLongOrNull() ?: default
            fun floatValue(key: String, default: Float = -1f) = values[key]?.toFloatOrNull() ?: default
            fun boolValue(key: String) = intValue(key, 0) == 1
            fun strValue(key: String) = values[key].orEmpty()
            val timestamp = longValue("timestamp")
            val isStale = timestamp > 0L && nowEpochSeconds - timestamp > 5L
            return AutoStatus(
                schemaVersion = intValue("schema_version", 1),
                seq = longValue("seq"), timestamp = timestamp,
                resolved = strValue("resolved"), fsm = strValue("fsm"),
                bucket = intValue("bucket"), daypart = intValue("daypart"),
                alpha = intValue("alpha"), alphaRaw = intValue("alpha_raw"), conf = intValue("conf"),
                temp = intValue("temp"), batt = intValue("batt"), gpu = intValue("gpu"),
                load1 = floatValue("load1"), cpuBusy = intValue("cpu_busy"), curMa = intValue("cur_ma"),
                powerW = intValue("power_w"),
                nightSleep = intValue("night_sleep"), nightWake = intValue("night_wake"),
                nightSamples = intValue("night_samples", 0),
                cpuMaxLittle = intValue("cpu_max_little", 0), cpuMaxBig = intValue("cpu_max_big", 0),
                charging = boolValue("charging"), screen = intValue("screen", 1),
                lowbat = boolValue("lowbat"),
                headroom = intValue("headroom"), headroomValid = boolValue("headroom_valid"),
                owner = strValue("owner"), reason = strValue("reason"),
                event = strValue("event"),
                foregroundPackage = strValue("foreground_package"),
                foregroundCategory = strValue("foreground_category"),
                foregroundSource = strValue("foreground_source"),
                softClamp = boolValue("soft_clamp"), hardClamp = boolValue("hard_clamp"),
                vendorClamp = boolValue("vendor_clamp"), vendorClamp1h = intValue("vendor_clamp_1h", 0),
                anticlampAttack = boolValue("anticlamp_attack"),
                anticlampAttackTotal = longValue("anticlamp_attack_total", 0L),
                explore = intValue("explore", 0), baseCost = intValue("base_cost"),
                consistency = intValue("consistency"),
                resolvedPolicy = strValue("resolved_policy"), policyReason = strValue("policy_reason"),
                policyScope = strValue("policy_scope"), fasState = strValue("fas_state"),
                writeVerification = strValue("write_verification"),
                lastWriteScope = strValue("last_write_scope"),
                lastWriteResult = strValue("last_write_result"),
                lastWriteNodes = strValue("last_write_nodes"),
                lastWriteFailed = strValue("last_write_failed"),
                batteryState = strValue("battery_state"), thermalState = strValue("thermal_state"),
                loadGovEnable = boolValue("loadgov_enable"),
                loadGovBusyLittle = intValue("loadgov_busy_little"),
                loadGovBusyBig = intValue("loadgov_busy_big"),
                loadGovPerfLittle = intValue("loadgov_perf_little"),
                loadGovPerfBig = intValue("loadgov_perf_big"),
                fasFrameAnalysis = boolValue("fas_frame_analysis"),
                fasLoading = boolValue("fas_loading"),
                fasPostGuard = intValue("fas_post_guard", 0),
                fasGearDwell = intValue("fas_gear_dwell", 0),
                fasFrameSource = strValue("fas_frame_source"),
                stale = isStale, valid = values.isNotEmpty(),
            )
        }
    }
}

data class CapabilityEntry(
    val available: Boolean = false,
    val reason: String = "unknown",
)

data class Capabilities(
    val schemaVersion: Int = 1,
    val timestamp: Long = -1L,
    val entries: Map<String, CapabilityEntry> = emptyMap(),
    val valid: Boolean = false,
) {
    companion object {
        fun parse(raw: String): Capabilities {
            if (raw.isBlank()) return Capabilities()
            val values = raw.lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()
            val entries = values.filterKeys { it.endsWith("_exists") }.map { (key, value) ->
                val prefix = key.removeSuffix("_exists")
                prefix to CapabilityEntry(
                    value == "1" || value.equals("true", true),
                    values["${prefix}_reason"] ?: "unknown",
                )
            }.toMap()
            return Capabilities(intValue(values, "schema_version", 1), longValue(values, "timestamp"), entries, values.isNotEmpty())
        }

        private fun intValue(values: Map<String, String>, key: String, default: Int) = values[key]?.toIntOrNull() ?: default
        private fun longValue(values: Map<String, String>, key: String) = values[key]?.toLongOrNull() ?: -1L
    }
}

/**
 * AutoMonitorScreen 用的轻量快照: 把 AutoStatus + daemon 存活状态捆成一对,
 * 让 Compose 一条 collectAsStateWithLifecycle 就拿到全部。
 * daemonAlive = null 表示本周期没读到 (RootShell 失败), UI 应保留上次判定。
 */
data class RuntimeSnapshot(
    val auto: AutoStatus = AutoStatus.EMPTY,
    val daemonAlive: Boolean? = null,
)
