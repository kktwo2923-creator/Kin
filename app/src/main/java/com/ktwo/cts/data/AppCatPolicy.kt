package com.ktwo.cts.data

import org.json.JSONObject

/**
 * 应用分类封顶策略(daemon 的 Function.AutoMode.app_policy):
 * 智能模式识别前台 App 分类后, 在 FSM 状态上限之上再按分类钳制(只压不抬)。
 *
 * 与 daemon(JsonConfig.hpp app_policy 解析 / Config.hpp catPolicyTable)约定:
 *  - maxL/maxB: 该分类小核/大核 scaling_max 封顶% (本机 cpuinfo_max_freq 百分比, 10..100)。
 *  - gpu: GPU 封顶%(0..100), 0 = 不限(交给 FSM 按状态)。>0 时与状态值取小, 只压不抬。
 *  - cores: 8 字符在线掩码(cpu0..7, '0'=停泊 其余=在线)。仅 app_policy_core_enable=true
 *    且非 game/undefined 分类时生效; daemon 侧有"不关 cpu0/不关整簇"硬护栏。
 *  - app_policy_enable 总开关默认 true; app_policy_core_enable 关核开关默认 false(显式 opt-in)。
 *  - 值统一写字符串, 与 config.json 其余键一致。
 */
data class CatOverride(
    val maxL: Int,
    val maxB: Int,
    val gpu: Int,
    val cores: List<Int> = List(8) { 1 },   // 1=在线 0=停泊
) {
    val parkedCount: Int get() = cores.count { it == 0 }
    fun coresMask(): String = cores.joinToString("") { if (it == 0) "0" else "1" }
}

data class AppCatPolicyCfg(
    val enable: Boolean = true,
    val coreEnable: Boolean = false,
    val cats: Map<String, CatOverride> = DEFAULTS,
    val rawJson: String = "",
) {
    companion object {
        // 键名顺序 = daemon catPolicyName(ApplicationInfo.category 0..7 + undefined)。
        val CAT_ORDER = listOf(
            "game", "audio", "video", "image", "social", "news", "maps", "productivity", "undefined",
        )
        // 中文名与「应用策略」画像同名(音乐/阅读), 分类即由画像映射而来: 用户在应用策略里
        // 给 App 选 视频/社交/阅读/音乐/游戏 画像, 智能模式就按对应分类封顶。
        val CAT_CN = mapOf(
            "game" to "游戏",
            "audio" to "音乐",
            "video" to "视频",
            "image" to "图片",
            "social" to "社交",
            "news" to "阅读",
            "maps" to "地图",
            "productivity" to "办公",
            "undefined" to "未分类",
        )
        // 界面展示的分类 = 应用策略画像能到达的那几类(顺序对齐应用策略) + 未分类兜底。
        // image/maps/productivity 只有系统识别才会命中, 无画像入口, 不在界面单列(保持内置默认)。
        val SMART_CATS = listOf("game", "video", "social", "news", "audio", "undefined")
        // 关核对 game/undefined 无效(daemon 永远全核), 频率上也默认不钳制。
        val NO_PARK = setOf("game", "undefined")

        /** 内置默认(镜像 daemon Config.hpp catPolicyDefault, 勿擅改)。 */
        val DEFAULTS: Map<String, CatOverride> = mapOf(
            "game" to CatOverride(100, 100, 0),
            "audio" to CatOverride(40, 35, 35),
            "video" to CatOverride(44, 40, 0),
            "image" to CatOverride(70, 60, 0),
            "social" to CatOverride(60, 55, 0),
            "news" to CatOverride(55, 50, 0),
            "maps" to CatOverride(90, 85, 0),
            "productivity" to CatOverride(70, 60, 0),
            "undefined" to CatOverride(100, 100, 0),
        )

        private fun intOf(o: JSONObject, key: String, def: Int): Int = when (val x = o.opt(key)) {
            is Number -> x.toInt()
            is String -> x.trim().toIntOrNull() ?: def
            else -> def
        }

        private fun boolOf(o: JSONObject, key: String, def: Boolean): Boolean = when (val x = o.opt(key)) {
            is Boolean -> x
            is Number -> x.toInt() != 0
            is String -> when (x.trim().lowercase()) {
                "true", "1", "on", "yes" -> true
                "false", "0", "off", "no" -> false
                else -> def
            }
            else -> def
        }

        fun parse(raw: String): AppCatPolicyCfg {
            if (raw.isBlank()) return AppCatPolicyCfg(rawJson = raw)
            return runCatching {
                val a = JSONObject(raw).optJSONObject("Function")?.optJSONObject("AutoMode")
                    ?: return AppCatPolicyCfg(rawJson = raw)
                val node = a.optJSONObject("app_policy")
                val m = HashMap<String, CatOverride>()
                for (c in CAT_ORDER) {
                    val d = DEFAULTS.getValue(c)
                    val o = node?.optJSONObject(c)
                    if (o == null) { m[c] = d; continue }
                    // 与 daemon 同口径: 掩码短于 8 位的尾部按在线; 仅 '0' 视为停泊。
                    val mask = o.optString("cores", "")
                    m[c] = CatOverride(
                        maxL = intOf(o, "maxL", d.maxL).coerceIn(10, 100),
                        maxB = intOf(o, "maxB", d.maxB).coerceIn(10, 100),
                        gpu = intOf(o, "gpu", d.gpu).coerceIn(0, 100),
                        cores = List(8) { k -> if (k < mask.length && mask[k] == '0') 0 else 1 },
                    )
                }
                AppCatPolicyCfg(
                    enable = boolOf(a, "app_policy_enable", true),
                    coreEnable = boolOf(a, "app_policy_core_enable", false),
                    cats = m,
                    rawJson = raw,
                )
            }.getOrDefault(AppCatPolicyCfg(rawJson = raw))
        }
    }

    /**
     * 合并回原始 JSON(读-改-写, 其余节点保留)。九个分类全量写出(与出厂 config 风格一致,
     * daemon 热重载会先复位内置默认再逐类覆盖, 全量写不会残留旧值)。
     */
    fun mergeInto(raw: String): String {
        // 非空但解析失败的 config 抛给调用方按"保存失败"处理, 不凭空造配置。
        val o = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        val f = o.optJSONObject("Function") ?: JSONObject().also { o.put("Function", it) }
        val a = f.optJSONObject("AutoMode") ?: JSONObject().also { f.put("AutoMode", it) }
        a.put("app_policy_enable", enable.toString())
        a.put("app_policy_core_enable", coreEnable.toString())
        val node = JSONObject()
        for (c in CAT_ORDER) {
            val v = cats[c] ?: DEFAULTS.getValue(c)
            node.put(c, JSONObject().apply {
                put("maxL", v.maxL.coerceIn(10, 100).toString())
                put("maxB", v.maxB.coerceIn(10, 100).toString())
                put("gpu", v.gpu.coerceIn(0, 100).toString())
                put("cores", v.coresMask())
            })
        }
        a.put("app_policy", node)
        return o.toString(4)
    }
}
