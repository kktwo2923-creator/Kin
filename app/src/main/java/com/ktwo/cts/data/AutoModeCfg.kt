package com.ktwo.cts.data

import org.json.JSONObject

/**
 * 智能档(自动模式)配置模型,直接读写 daemon 的 config.json 中的 Function.AutoMode 节点。
 *
 * 设计与 CpuControl 一致:
 *  - 读-改-写:只动 Function.AutoMode 的字段,其余 Function 子节点 / Policy / models / meta 等原样保留。
 *  - 值统一写成字符串("true"/"57"),与出厂 config.json 及 daemon 的 readBool/readInt(两者皆容错)一致。
 *  - 默认值对齐 daemon include/Config.hpp 的 Config::AutoMode,缺字段时回退默认而非 0。
 *
 * 字段含义(详见 daemon Config.hpp / AutoMode.hpp):
 *  enable           总开关(mode=auto 时是否真正接管;false 退回 balance)
 *  fsmEnable        主动调频(FSM):true=六状态实时调频率界;false=仅按学习 alpha 选整档
 *  coolLeanPerf     凉爽且低负载时是否允许冲性能档(对应 cool_lean="performance")
 *  learnRate        学习强度(/1000,1..1000)
 *  tickSec          决策周期(秒,亮屏;3..120)
 *  drainHigh        高耗电判定(亮屏掉电率 %/h)
 *  autoBatteryEnable 低电自动省电开关
 *  lowBatt/critBatt 低电 / 极低电触发电量(%)
 *  nightQuietEnable 夜间安静开关
 *  nightStart/End   夜间时段(小时,[start,end),支持跨零点)
 *  warm/cool/hot/vhot/vetoTemp 温度信号阈值(℃):学习方向与热否决用
 *  fsmSustainedEnter/Exit FSM 热限进/出温度(℃),enter 必须 > exit
 */
data class AutoModeCfg(
    val enable: Boolean = true,
    val fsmEnable: Boolean = true,
    val coolLeanPerf: Boolean = false,
    val learnRate: Int = 80,
    val tickSec: Int = 12,
    val drainHigh: Int = 14,
    val autoBatteryEnable: Boolean = true,
    val lowBatt: Int = 20,
    val critBatt: Int = 10,
    val nightQuietEnable: Boolean = true,
    val nightStart: Int = 0,
    val nightEnd: Int = 6,
    val warmTemp: Int = 50,
    val coolTemp: Int = 55,
    val hotTemp: Int = 70,
    val vhotTemp: Int = 80,
    val vetoTemp: Int = 65,
    val fsmSustainedEnter: Int = 57,
    val fsmSustainedExit: Int = 49,
    // ── 系统优化 (ASB,移植自 AutoSystemBoost,与档位无关,所有模式生效) ──
    // 对应 daemon 的 Function.SysTune / BootTrim / BgTrim(详见 SysTweaks.hpp)。
    //  sysTuneEnable    每档 VM/网络/WiFi(power_save、txqueuelen)/TCP 调优(低风险,默认开)
    //  bootTrimEnable   开机停 4 个调试/遥测服务 + 关 WiFi 后台扫描(pm disable 跨重启持久,默认关)
    //  bgTrimEnable     周期性后台内存软回收(只 trim 不杀进程,默认关)
    //  bgTrimIntervalS  后台回收周期(秒)
    val sysTuneEnable: Boolean = true,
    val bootTrimEnable: Boolean = false,
    val bgTrimEnable: Boolean = false,
    val bgTrimIntervalS: Int = 90,
    val rawJson: String = "",
) {
    companion object {
        val EMPTY = AutoModeCfg()

        /** 兼容字符串/数字/布尔三种写法读 Bool。 */
        private fun JSONObject.boolOf(key: String, def: Boolean): Boolean = when (val v = opt(key)) {
            is Boolean -> v
            is Number -> v.toInt() != 0
            is String -> when (v.trim().lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off", "" -> false
                else -> def
            }
            else -> def
        }

        /** 兼容字符串/数字读 Int。 */
        private fun JSONObject.intOf(key: String, def: Int): Int = when (val v = opt(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull() ?: def
            else -> def
        }

        fun parse(raw: String): AutoModeCfg {
            if (raw.isBlank()) return EMPTY.copy(rawJson = raw)
            return runCatching {
                val fn = JSONObject(raw).optJSONObject("Function")
                    ?: return EMPTY.copy(rawJson = raw)
                val a = fn.optJSONObject("AutoMode") ?: JSONObject()
                // ASB 三块是 Function 下与 AutoMode 同级的兄弟节点;缺失时回退默认。
                val sysTune = fn.optJSONObject("SysTune") ?: JSONObject()
                val bootTrim = fn.optJSONObject("BootTrim") ?: JSONObject()
                val bgTrim = fn.optJSONObject("BgTrim") ?: JSONObject()
                val d = EMPTY
                AutoModeCfg(
                    enable            = a.boolOf("enable", d.enable),
                    fsmEnable         = a.boolOf("fsm_enable", d.fsmEnable),
                    coolLeanPerf      = a.optString("cool_lean").trim().equals("performance", true),
                    learnRate         = a.intOf("learn_rate", d.learnRate),
                    tickSec           = a.intOf("tick_sec", d.tickSec),
                    drainHigh         = a.intOf("drain_high", d.drainHigh),
                    autoBatteryEnable = a.boolOf("auto_battery_enable", d.autoBatteryEnable),
                    lowBatt           = a.intOf("low_batt", d.lowBatt),
                    critBatt          = a.intOf("crit_batt", d.critBatt),
                    nightQuietEnable  = a.boolOf("night_quiet_enable", d.nightQuietEnable),
                    nightStart        = a.intOf("night_start", d.nightStart),
                    nightEnd          = a.intOf("night_end", d.nightEnd),
                    warmTemp          = a.intOf("warm_temp", d.warmTemp),
                    coolTemp          = a.intOf("cool_temp", d.coolTemp),
                    hotTemp           = a.intOf("hot_temp", d.hotTemp),
                    vhotTemp          = a.intOf("vhot_temp", d.vhotTemp),
                    vetoTemp          = a.intOf("veto_temp", d.vetoTemp),
                    fsmSustainedEnter = a.intOf("fsm_sustained_enter", d.fsmSustainedEnter),
                    fsmSustainedExit  = a.intOf("fsm_sustained_exit", d.fsmSustainedExit),
                    sysTuneEnable     = sysTune.boolOf("enable", d.sysTuneEnable),
                    bootTrimEnable    = bootTrim.boolOf("enable", d.bootTrimEnable),
                    bgTrimEnable      = bgTrim.boolOf("enable", d.bgTrimEnable),
                    bgTrimIntervalS   = bgTrim.intOf("interval_s", d.bgTrimIntervalS),
                    rawJson           = raw,
                )
            }.getOrDefault(EMPTY.copy(rawJson = raw))
        }
    }

    /** 把当前设置合并回原始 JSON;Function 下其它节点与顶层字段原样保留。 */
    fun mergeInto(raw: String): String {
        val o = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val f = o.optJSONObject("Function") ?: JSONObject().also { o.put("Function", it) }
        val a = f.optJSONObject("AutoMode") ?: JSONObject().also { f.put("AutoMode", it) }

        // enter 必须 > exit,否则进出抖动;非法时回退默认 57/49(与 daemon 解析护栏一致)。
        var enter = fsmSustainedEnter
        var exit = fsmSustainedExit
        if (enter <= exit) { enter = 57; exit = 49 }

        a.put("enable", enable.toString())
        a.put("fsm_enable", fsmEnable.toString())
        a.put("cool_lean", if (coolLeanPerf) "performance" else "")
        a.put("learn_rate", learnRate.coerceIn(1, 1000).toString())
        a.put("tick_sec", tickSec.coerceIn(3, 120).toString())
        a.put("drain_high", drainHigh.coerceIn(1, 100).toString())
        a.put("auto_battery_enable", autoBatteryEnable.toString())
        a.put("low_batt", lowBatt.coerceIn(1, 100).toString())
        a.put("crit_batt", critBatt.coerceIn(1, 100).toString())
        a.put("night_quiet_enable", nightQuietEnable.toString())
        a.put("night_start", nightStart.coerceIn(0, 23).toString())
        a.put("night_end", nightEnd.coerceIn(0, 23).toString())
        a.put("warm_temp", warmTemp.coerceIn(30, 100).toString())
        a.put("cool_temp", coolTemp.coerceIn(30, 100).toString())
        a.put("hot_temp", hotTemp.coerceIn(40, 110).toString())
        a.put("vhot_temp", vhotTemp.coerceIn(40, 120).toString())
        a.put("veto_temp", vetoTemp.coerceIn(40, 110).toString())
        a.put("fsm_sustained_enter", enter.coerceIn(40, 100).toString())
        a.put("fsm_sustained_exit", exit.coerceIn(35, 99).toString())

        // ── 系统优化 (ASB):写 Function.SysTune / BootTrim / BgTrim(与 AutoMode 同级) ──
        val sysTune = f.optJSONObject("SysTune") ?: JSONObject().also { f.put("SysTune", it) }
        sysTune.put("enable", sysTuneEnable.toString())
        val bootTrim = f.optJSONObject("BootTrim") ?: JSONObject().also { f.put("BootTrim", it) }
        bootTrim.put("enable", bootTrimEnable.toString())
        val bgTrim = f.optJSONObject("BgTrim") ?: JSONObject().also { f.put("BgTrim", it) }
        bgTrim.put("enable", bgTrimEnable.toString())
        bgTrim.put("interval_s", bgTrimIntervalS.coerceIn(30, 600).toString())

        return o.toString(2)
    }
}
