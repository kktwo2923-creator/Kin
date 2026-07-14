package com.ktwo.cts.data

import org.json.JSONObject

/**
 * 智能策略问答(5 题小问卷):把用户口语化的使用感受翻译成 config.json 里的调度参数。
 *
 * 题目 -> 参数映射:
 *  q1 睡眠时间   -> Function.AutoMode.night_start / night_end + night_quiet_enable=true
 *  q2 省电偏好   -> battery_bias(更省电 200 / 偏省电 100 / 均衡·偏流畅 0)+ cool_lean(偏流畅 = "performance")
 *  q3 日常卡不卡 -> fsm_policy 的 light_idle / moderate 上限覆盖
 *  q4 重度卡不卡 -> fsm_policy 的 heavy / sustained / gaming 上限覆盖
 *  q5 发烫在意度 -> fsm_sustained_enter / exit(压频起始/松手温度)
 */
data class SurveyAnswers(
    val sleepStart: Int = 23,          // 就寝(0-23 时)
    val sleepEnd: Int = 7,             // 起床(0-23 时)
    val powerPref: String = "balanced", // saver_strong / saver / balanced / perf
    val dailyLag: String = "smooth",    // lag_bad / lag_some / smooth / save_more
    val heavyLag: String = "ok",        // lag / ok / no_game
    val heat: String = "normal",        // care / normal / ignore
    val ts: Long = 0L,                  // 回答时间(ms)
) {
    fun toJson(): String = JSONObject().apply {
        put("sleep_start", sleepStart)
        put("sleep_end", sleepEnd)
        put("power_pref", powerPref)
        put("daily_lag", dailyLag)
        put("heavy_lag", heavyLag)
        put("heat", heat)
        put("ts", ts)
    }.toString()

    companion object {
        /** 解析失败/空串返回 null(= 从未回答)。 */
        fun parse(raw: String): SurveyAnswers? {
            if (raw.isBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                SurveyAnswers(
                    sleepStart = o.optInt("sleep_start", 23).coerceIn(0, 23),
                    sleepEnd = o.optInt("sleep_end", 7).coerceIn(0, 23),
                    powerPref = o.optString("power_pref", "balanced"),
                    dailyLag = o.optString("daily_lag", "smooth"),
                    heavyLag = o.optString("heavy_lag", "ok"),
                    heat = o.optString("heat", "normal"),
                    ts = o.optLong("ts", 0L),
                )
            }.getOrNull()
        }
    }
}

object SmartSurvey {
    // 各题选项:key -> 中文标签(UI 单选 chip 用)。
    val POWER_PREF_OPTIONS = listOf(
        "saver_strong" to "更省电",
        "saver" to "偏省电",
        "balanced" to "均衡",
        "perf" to "偏流畅",
    )
    val DAILY_LAG_OPTIONS = listOf(
        "lag_bad" to "明显卡顿",
        "lag_some" to "偶尔卡",
        "smooth" to "不卡,挺好",
        "save_more" to "不卡,想更省电",
    )
    val HEAVY_LAG_OPTIONS = listOf(
        "lag" to "会卡",
        "ok" to "还行",
        "no_game" to "基本不玩游戏",
    )
    val HEAT_OPTIONS = listOf(
        "care" to "很在意",
        "normal" to "一般",
        "ignore" to "不在意,性能优先",
    )

    fun optionLabel(options: List<Pair<String, String>>, key: String): String =
        options.firstOrNull { it.first == key }?.second ?: key

    /**
     * 按回答生成合并后的完整 config.json 文本:
     * 先把 AutoMode 参数(夜间/省电偏向/温控)读-改-写,再把 fsm_policy 覆盖整体重写。
     * 注意:每轮问卷完全决定 fsm_policy("不卡"即清掉对应状态的覆盖,交回智能曲线)。
     */
    fun apply(answers: SurveyAnswers, raw: String): String {
        val bias = when (answers.powerPref) {
            "saver_strong" -> 200
            "saver" -> 100
            else -> 0   // balanced / perf
        }
        val leanPerf = answers.powerPref == "perf"
        val (enter, exit) = when (answers.heat) {
            "care" -> 50 to 45
            "ignore" -> 58 to 50
            else -> 54 to 48   // normal
        }
        val step1 = AutoModeCfg.parse(raw).copy(
            nightQuietEnable = true,
            nightStart = answers.sleepStart.coerceIn(0, 23),
            nightEnd = answers.sleepEnd.coerceIn(0, 23),
            batteryBias = bias,
            coolLeanPerf = leanPerf,
            fsmSustainedEnter = enter,
            fsmSustainedExit = exit,
        ).mergeInto(raw)

        // q3/q4 -> 状态频率上限覆盖(maxL, maxB, gpu),未列出的状态跟随智能曲线。
        val states = HashMap<String, FsmStateOverride>()
        // deep_idle 不归任何问卷题目管, 保留用户在策略页手动设置的覆盖, 问卷不清除它。
        SmartPolicyCfg.parse(raw).states[SmartPolicyCfg.DEEP_IDLE]?.let {
            states[SmartPolicyCfg.DEEP_IDLE] = it
        }
        when (answers.dailyLag) {
            "lag_bad" -> {   // 明显卡顿:轻/中负载明显抬高上限
                states[SmartPolicyCfg.LIGHT_IDLE] = FsmStateOverride(70, 65, 55)
                states[SmartPolicyCfg.MODERATE] = FsmStateOverride(85, 80, 70)
            }
            "lag_some" -> {  // 偶尔卡:小幅抬高
                states[SmartPolicyCfg.LIGHT_IDLE] = FsmStateOverride(60, 55, 50)
                states[SmartPolicyCfg.MODERATE] = FsmStateOverride(78, 72, 65)
            }
            "save_more" -> { // 不卡且想更省电:压低上限
                states[SmartPolicyCfg.LIGHT_IDLE] = FsmStateOverride(45, 40, 45)
                states[SmartPolicyCfg.MODERATE] = FsmStateOverride(60, 55, 55)
            }
            // "smooth":不卡,挺好 -> 保持智能曲线,不覆盖
        }
        when (answers.heavyLag) {
            "lag" -> {       // 重度会卡:高负载全放开
                states[SmartPolicyCfg.HEAVY] = FsmStateOverride(100, 100, 90)
                states[SmartPolicyCfg.SUSTAINED] = FsmStateOverride(90, 85, 85)
                states[SmartPolicyCfg.GAMING] = FsmStateOverride(100, 100, 100)
            }
            "no_game" -> {   // 基本不玩游戏:重载适度收敛省电,游戏态保持智能曲线
                states[SmartPolicyCfg.HEAVY] = FsmStateOverride(85, 80, 80)
                states[SmartPolicyCfg.SUSTAINED] = FsmStateOverride(75, 70, 75)
            }
            // "ok":还行 -> 保持智能曲线
        }
        return SmartPolicyCfg(states = states).mergeInto(step1)
    }
}
