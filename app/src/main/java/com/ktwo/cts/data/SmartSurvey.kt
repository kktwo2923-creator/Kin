package com.ktwo.cts.data

import android.content.Context
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 智能策略问答(5 题小问卷):先展示系统当前学到/生效的参数, 用户"确认或纠正"。
 * "正确/保持现状"不改任何东西(保留既有覆盖与学习);纠正在当前值基础上做相对调整。
 *
 * 题目 -> 参数映射(仅在纠正时生效):
 *  q1 睡眠时间   -> nightOk=true 不动学习/配置;纠正 -> night_start/end + night_quiet_enable=true,
 *                   并经 auto_control.txt 下发 night_seed 纠正 daemon 的夜间学习数据。
 *  q2 省电偏好   -> keep 不动;saver -> 先关 cool_lean, 否则 battery_bias +100(封顶 300);
 *                   perf -> 有 bias 先 -100, 否则 cool_lean="performance"(两者互为逆操作)。
 *  q3 日常卡不卡 -> light_idle / moderate 在当前生效上限上相对加减(+15/+8/-10)。
 *  q4 重度卡不卡 -> heavy / sustained / gaming 在当前生效上限上相对加减(+12/-10)。
 *  q5 发烫在意度 -> keep 不动;cooler/hotter -> fsm_sustained_enter 在当前值上 ∓4(46..64),
 *                   exit = enter - 6。
 */
data class SurveyAnswers(
    val sleepStart: Int = 23,          // 就寝(0-23 时)
    val sleepEnd: Int = 7,             // 起床(0-23 时)
    val nightOk: Boolean = false,      // true = 学到的作息准确, 不做任何纠正
    val nightTouched: Boolean = false, // true = 用户主动改过作息(点了"不太准"或动过滑条)
    val powerPref: String = "keep",    // keep / saver / perf
    val dailyLag: String = "keep",     // lag_bad / lag_some / keep / save_more
    val heavyLag: String = "keep",     // lag / keep / no_game
    val heat: String = "keep",         // keep / cooler / hotter
    val ts: Long = 0L,                 // 回答时间(ms)
) {
    fun toJson(): String = JSONObject().apply {
        put("sleep_start", sleepStart)
        put("sleep_end", sleepEnd)
        put("night_ok", nightOk)
        put("night_touched", nightTouched)
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
                    nightOk = o.optBoolean("night_ok", false),
                    nightTouched = o.optBoolean("night_touched", false),
                    powerPref = o.optString("power_pref", "keep"),
                    dailyLag = o.optString("daily_lag", "keep"),
                    heavyLag = o.optString("heavy_lag", "keep"),
                    heat = o.optString("heat", "keep"),
                    ts = o.optLong("ts", 0L),
                )
            }.getOrNull()
        }
    }
}

/** 问卷应用时的现场上下文: 学习 alpha(未知给 500)+ 本机小/大簇最高频(kHz, 0=未知)。 */
data class SurveyContext(
    val alpha: Int,
    val hwMaxL: Long,
    val hwMaxB: Long,
)

object SmartSurvey {
    // 各题选项:key -> 中文标签(UI 单选 chip 用)。语义是"确认或纠正"。
    val POWER_PREF_OPTIONS = listOf(
        "keep" to "现在挺合适",
        "saver" to "想更省电",
        "perf" to "想更流畅",
    )
    val DAILY_LAG_OPTIONS = listOf(
        "lag_bad" to "明显卡顿",
        "lag_some" to "偶尔卡",
        "keep" to "不卡,保持现状",
        "save_more" to "不卡,想更省电",
    )
    val HEAVY_LAG_OPTIONS = listOf(
        "lag" to "会卡",
        "keep" to "还行,保持现状",
        "no_game" to "基本不玩游戏",
    )
    val HEAT_OPTIONS = listOf(
        "keep" to "现在合适",
        "cooler" to "太烫了,早点降温",
        "hotter" to "压得太早,性能优先",
    )

    /** 旧版本问卷的选项 key -> 标签, 只用于渲染历史记录(旧回答不再参与应用)。 */
    val LEGACY_LABELS: Map<String, String> = mapOf(
        "saver_strong" to "更省电",
        "balanced" to "均衡",
        "smooth" to "不卡,挺好",
        "ok" to "还行",
        "normal" to "一般",
        "care" to "很在意",
        "ignore" to "不在意,性能优先",
    )

    fun optionLabel(options: List<Pair<String, String>>, key: String): String =
        options.firstOrNull { it.first == key }?.second ?: LEGACY_LABELS[key] ?: key

    /**
     * 按回答生成合并后的完整 config.json 文本(确认-或-纠正语义):
     *  - 起点是现有 fsm_policy 的全部六个状态覆盖(默认全保留, "保持现状"即什么都不改);
     *  - 纠正时以"当前生效值"(覆盖优先, 否则智能曲线按 ctx 换算)为基准做相对加减;
     *  - AutoMode 参数同样只在纠正时读-改-写对应字段。
     */
    fun apply(answers: SurveyAnswers, raw: String, ctx: SurveyContext): String {
        // ── fsm_policy: 全量保留现有覆盖, 只对被纠正的状态落盘新值 ──
        val states = SmartPolicyCfg.parse(raw).states.toMutableMap()

        // 某状态当前生效的(小核%, 大核%, GPU%): 覆盖优先, 否则智能曲线/内置值。
        // CPU 基线在"无覆盖且读不到本机最高频"时为 null —— 此时曲线换算是瞎猜(恒 100),
        // 物化会把"想更省电"反向变成解除限制, 宁可留给 daemon 内置曲线。
        fun eff(stateKey: String, idx: Int): Triple<Int?, Int?, Int> {
            val ov = states[stateKey]
            val l = ov?.maxL?.takeIf { it in 10..100 }
                ?: FsmCurve.curvePct(idx, ctx.alpha, little = true, hwMaxKhz = ctx.hwMaxL)
                    .takeIf { ctx.hwMaxL > 0 }
            val b = ov?.maxB?.takeIf { it in 10..100 }
                ?: FsmCurve.curvePct(idx, ctx.alpha, little = false, hwMaxKhz = ctx.hwMaxB)
                    .takeIf { ctx.hwMaxB > 0 }
            val g = ov?.gpu?.takeIf { it in 1..100 } ?: FsmCurve.GPU_BUILTIN.getOrElse(idx) { 100 }
            return Triple(l, b, g)
        }

        // 相对调整: 从当前生效值物化成覆盖再加减(delta 可正可负); 基线未知的簇保持未设置。
        fun bump(stateKey: String, idx: Int, delta: Int) {
            val (l, b, g) = eff(stateKey, idx)
            states[stateKey] = FsmStateOverride(
                maxL = l?.plus(delta)?.coerceIn(10, 100) ?: SmartPolicyCfg.UNSET,
                maxB = b?.plus(delta)?.coerceIn(10, 100) ?: SmartPolicyCfg.UNSET,
                gpu = (g + delta).coerceIn(10, 100),
            )
        }

        when (answers.dailyLag) {
            "lag_bad" -> {   // 明显卡顿: 轻/中负载在当前基础上明显抬高
                bump(SmartPolicyCfg.LIGHT_IDLE, 1, +15)
                bump(SmartPolicyCfg.MODERATE, 2, +15)
            }
            "lag_some" -> {  // 偶尔卡: 小幅抬高
                bump(SmartPolicyCfg.LIGHT_IDLE, 1, +8)
                bump(SmartPolicyCfg.MODERATE, 2, +8)
            }
            "save_more" -> { // 不卡且想更省电: 压低
                bump(SmartPolicyCfg.LIGHT_IDLE, 1, -10)
                bump(SmartPolicyCfg.MODERATE, 2, -10)
            }
            // "keep": 保持现状, 不动
        }
        when (answers.heavyLag) {
            "lag" -> {       // 重度会卡: 高负载三态一起抬
                bump(SmartPolicyCfg.HEAVY, 3, +12)
                bump(SmartPolicyCfg.SUSTAINED, 4, +12)
                bump(SmartPolicyCfg.GAMING, 5, +12)
            }
            "no_game" -> {   // 基本不玩游戏: 重载收敛省电, 游戏态不动
                bump(SmartPolicyCfg.HEAVY, 3, -10)
                bump(SmartPolicyCfg.SUSTAINED, 4, -10)
            }
            // "keep": 保持现状, 不动
        }

        // ── AutoMode: 只在纠正时改对应字段 ──
        var auto = AutoModeCfg.parse(raw)
        when (answers.powerPref) {
            // saver 与 perf 互为逆操作: perf 先泄 bias 再开低温性能; saver 先关低温性能再加 bias。
            // 否则答过一次 perf 后再答 saver 只会加 bias, cool_lean 永远关不掉, 越纠越偏。
            "saver" -> auto =
                if (auto.coolLeanPerf) auto.copy(coolLeanPerf = false)
                else auto.copy(batteryBias = minOf(auto.batteryBias + 100, 300))
            "perf" -> auto =
                if (auto.batteryBias > 0) auto.copy(batteryBias = auto.batteryBias - 100)
                else auto.copy(coolLeanPerf = true)
            // "keep": 不动
        }
        when (answers.heat) {
            "cooler" -> {
                val enter = maxOf(46, auto.fsmSustainedEnter - 4)
                auto = auto.copy(fsmSustainedEnter = enter, fsmSustainedExit = enter - 6)
            }
            "hotter" -> {
                val enter = minOf(64, auto.fsmSustainedEnter + 4)
                auto = auto.copy(fsmSustainedEnter = enter, fsmSustainedExit = enter - 6)
            }
            // "keep": 不动
        }
        if (!answers.nightOk && answers.nightTouched) {
            // 纠正夜间时段: 仅在用户【主动】改过(点"不太准"/动过滑条)时才写 ——
            // 未动过的默认值不算回答, 下发会把真学到的窗口冲掉。
            // 配置层 night_start/end 只对"未学到"阶段生效; 学习数据本身的纠正由调用方经
            // auto_control.txt 下发 night_seed(学习样本 >=3 后学习值覆盖配置, 必须直达)。
            auto = auto.copy(
                nightQuietEnable = true,
                nightStart = answers.sleepStart.coerceIn(0, 23),
                nightEnd = answers.sleepEnd.coerceIn(0, 23),
            )
        }
        return SmartPolicyCfg(states = states).mergeInto(auto.mergeInto(raw))
    }
}

/**
 * 问卷记录的落盘副本: 存 /sdcard/Android/Kin/AI/survey.json(daemon 数据目录, 不在 App 私有目录),
 * 清除 App 数据/缓存、卸载重装都不会丢; App 启动时 DataStore 为空则自动从副本恢复。
 */
object SmartSurveyStore {

    /** 把当前最新回答 + 历史写到落盘副本(问卷应用成功后调用; 失败静默, 不影响主流程)。 */
    suspend fun backup(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val latest = Prefs.smartSurveyFlow(ctx).first()
            val history = Prefs.smartSurveyHistoryFlow(ctx).first()
            if (latest.isBlank()) return@runCatching
            val o = JSONObject().apply {
                put("latest", JSONObject(latest))
                put("history", runCatching { JSONArray(history) }.getOrDefault(JSONArray()))
            }
            RootShell.writeSurveyStore(o.toString())
        }
    }

    /** DataStore 里没有回答(如清过 App 数据)而落盘副本存在 → 恢复; 返回是否恢复了。 */
    suspend fun restoreIfNeeded(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (Prefs.smartSurveyFlow(ctx).first().isNotBlank()) return@runCatching false
            val raw = RootShell.readSurveyStore()
            if (raw.isBlank()) return@runCatching false
            val o = JSONObject(raw)
            val latest = o.optJSONObject("latest")?.toString().orEmpty()
            if (latest.isBlank() || SurveyAnswers.parse(latest) == null) return@runCatching false
            Prefs.setSmartSurvey(ctx, latest)
            o.optJSONArray("history")?.let { Prefs.setSmartSurveyHistoryRaw(ctx, it.toString()) }
            true
        }.getOrDefault(false)
    }
}
