package com.ktwo.cts.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * CPU 控制面板数据模型 —— 直接读写 daemon 的 /sdcard/Android/CTS/config.json。
 *
 * 【方案丙】面板是 daemon「画像(model)」系统的可视化编辑器:
 *   顶部画像选择器(powersave/balance/performance/fast/game_heavy/...)选中哪个画像,
 *   下面就编辑 config.json 里 models[] 中那个 model 的频率/调速器/GPU/核心开关。
 *   daemon 一个字不用改 —— 它本来就在按前台 App 匹配 model 并写 sysfs。
 *
 * 与 daemon 的 config.json 结构 1:1 对齐(见 CTS-Daemon/Json/config_8E5.json):
 * {
 *   "meta": {...}, "Function": {...}, "package_blacklist": [...],
 *   "Policy": { "c0":"0", "c1":"6", "c2":"-1", "c3":"-1" },   // cN -> 物理 policy 映射
 *   "models": [
 *     {
 *       "model_name":"balance", "mode":"balance", "is_game":false, "packages":[],
 *       "freq_min_c0":"384000", "freq_max_c0":"3000000",        // 字符串! 单位 kHz
 *       "freq_min_c1":"768000", "freq_max_c1":"3600000",
 *       "gpu_min_mhz":"0", "gpu_max_mhz":"500",                 // 字符串, 单位 MHz
 *       "CoreOnline": { "Core0":"1", ..., "Core7":"0" },        // 对象! 值是字符串 "0"/"1"
 *       "gov_c0": { "governor":"walt", "params":{...} },        // governor 嵌在 gov_cN 里
 *       "gov_c1": { "governor":"walt" }
 *     }, ...
 *   ]
 * }
 *
 * 设计要点:
 *  - cN 索引(c0/c1/...)通过顶层 Policy.cN 映射到物理 policy(SM8850: c0->policy0 性能核,
 *    c1->policy6 大核)。面板按 cN 编辑, 展示时用 Policy 映射出真实 policy 号去读实时频率。
 *  - 频率/GPU/CoreOnline 在 JSON 里都是字符串, 解析/写回都按字符串处理, 与 daemon 完全一致。
 *  - 读-改-写: 只改选中 model 的字段, 其余 model、meta、Function、Policy、blacklist 原样保留。
 */

/** 顶层 Policy 映射: cN 索引 -> 物理 policy 编号(-1 表示该簇不存在) */
data class PolicyMap(val cToPolicy: List<Int> = listOf(0, 6, -1, -1)) {
    /** 有效簇的 cN 索引列表(policy != -1) */
    val activeClusters: List<Int> get() = cToPolicy.indices.filter { cToPolicy[it] >= 0 }
    fun policyOf(cIndex: Int): Int = cToPolicy.getOrElse(cIndex) { -1 }
    /** 簇显示名: 按位置约定 c0=性能核(小核簇,6 核), c1=大核 */
    fun displayName(cIndex: Int): String = when (cIndex) {
        0 -> "性能核"
        1 -> "大核"
        else -> "簇$cIndex"
    }

    companion object {
        fun parse(o: JSONObject): PolicyMap {
            val p = o.optJSONObject("Policy") ?: return PolicyMap()
            val list = (0..3).map { i ->
                // Policy 值可能是字符串 "0" 或数字 0; 255/-1 都表示无效
                val raw = p.opt("c$i")
                val v = when (raw) {
                    is Number -> raw.toInt()
                    is String -> raw.toIntOrNull() ?: -1
                    else -> -1
                }
                if (v == 255) -1 else v
            }
            return PolicyMap(list)
        }
    }
}

/** 单个簇(cN)在某个 model 里的频率 + 调速器设定。值保持字符串, 与 daemon 一致。 */
data class ClusterModelCfg(
    val cIndex: Int,            // 0 / 1 / 2 / 3(对应 freq_*_cN / gov_cN)
    val policy: Int,            // 由 PolicyMap 映射出的物理 policy(用于读实时频率)
    val minFreqKhz: String = "",
    val maxFreqKhz: String = "",
    val governor: String = "",
) {
    val minKhzLong: Long get() = minFreqKhz.toLongOrNull() ?: 0L
    val maxKhzLong: Long get() = maxFreqKhz.toLongOrNull() ?: 0L
}

/** 一个完整画像(model) */
data class ProfileModel(
    val modelName: String,                 // "balance" 等, = 选择器里的画像 key
    val mode: String = "",
    val isGame: Boolean = false,
    val clusters: List<ClusterModelCfg> = emptyList(),
    val gpuMinMhz: String = "",
    val gpuMaxMhz: String = "",
    val coreOnline: List<Int> = List(8) { -1 },   // 8 核; -1=不指定, 0=关, 1=开
) {
    val gpuMinInt: Int get() = gpuMinMhz.toIntOrNull() ?: 0
    val gpuMaxInt: Int get() = gpuMaxMhz.toIntOrNull() ?: 0
}

/** 整个 config.json 的「面板可编辑视图」。原始 JSON 文本保留, 用于读-改-写。 */
data class CpuControlCfg(
    val policyMap: PolicyMap = PolicyMap(),
    val profiles: List<ProfileModel> = emptyList(),
    val rawJson: String = "",              // 原始 config.json, 写回时读-改-写
) {
    fun profile(name: String): ProfileModel? = profiles.firstOrNull { it.modelName == name }
    val profileNames: List<String> get() = profiles.map { it.modelName }

    companion object {
        val EMPTY = CpuControlCfg()

        fun parse(raw: String): CpuControlCfg {
            if (raw.isBlank()) return EMPTY
            return runCatching {
                val o = JSONObject(raw)
                val policyMap = PolicyMap.parse(o)

                val modelsArr = o.optJSONArray("models") ?: JSONArray()
                val profiles = buildList {
                    for (i in 0 until modelsArr.length()) {
                        val m = modelsArr.optJSONObject(i) ?: continue
                        add(parseModel(m, policyMap))
                    }
                }
                CpuControlCfg(policyMap = policyMap, profiles = profiles, rawJson = raw)
            }.getOrDefault(EMPTY.copy(rawJson = raw))
        }

        private fun parseModel(m: JSONObject, pm: PolicyMap): ProfileModel {
            val clusters = pm.activeClusters.map { c ->
                ClusterModelCfg(
                    cIndex = c,
                    policy = pm.policyOf(c),
                    minFreqKhz = m.optString("freq_min_c$c"),
                    maxFreqKhz = m.optString("freq_max_c$c"),
                    governor = m.optJSONObject("gov_c$c")?.optString("governor") ?: "",
                )
            }
            // CoreOnline 是对象 { "Core0":"1", ... }, 值是字符串
            val coreObj = m.optJSONObject("CoreOnline")
            val core = (0..7).map { i ->
                val v = coreObj?.opt("Core$i")
                when (v) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull() ?: -1
                    else -> -1
                }
            }
            return ProfileModel(
                modelName = m.optString("model_name"),
                mode = m.optString("mode"),
                isGame = m.optBoolean("is_game", false),
                clusters = clusters,
                gpuMinMhz = m.optString("gpu_min_mhz"),
                gpuMaxMhz = m.optString("gpu_max_mhz"),
                coreOnline = core,
            )
        }
    }

    /**
     * 把某个 model 的改动合并回原始 JSON 文本(读-改-写)。
     * 只动 models[] 里 model_name 匹配的那个对象的相关字段, 其余原样保留。
     * 频率/GPU/CoreOnline 都写成字符串, 与 daemon 解析逻辑一致。
     */
    fun mergeProfileInto(raw: String, p: ProfileModel): String {
        val o = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val models = o.optJSONArray("models") ?: JSONArray().also { o.put("models", it) }

        // 找到对应 model; 找不到则追加一个新对象
        var target: JSONObject? = null
        for (i in 0 until models.length()) {
            val m = models.optJSONObject(i) ?: continue
            if (m.optString("model_name") == p.modelName) { target = m; break }
        }
        if (target == null) {
            target = JSONObject().apply {
                put("model_name", p.modelName)
                put("mode", p.mode.ifEmpty { p.modelName })
                put("is_game", p.isGame)
                put("packages", JSONArray())
            }
            models.put(target)
        }

        // 频率 + 调速器(按 cN)
        p.clusters.forEach { c ->
            target.put("freq_min_c${c.cIndex}", c.minFreqKhz)
            target.put("freq_max_c${c.cIndex}", c.maxFreqKhz)
            // gov_cN: 保留原 params, 只改 governor
            val govObj = target.optJSONObject("gov_c${c.cIndex}") ?: JSONObject()
            govObj.put("governor", c.governor)
            target.put("gov_c${c.cIndex}", govObj)
        }

        // GPU
        target.put("gpu_min_mhz", p.gpuMinMhz)
        target.put("gpu_max_mhz", p.gpuMaxMhz)

        // CoreOnline 对象(字符串值; -1 表示不指定 -> 写 "-1" 让 daemon 跳过)
        target.put("CoreOnline", JSONObject().apply {
            p.coreOnline.forEachIndexed { i, v -> put("Core$i", v.toString()) }
        })

        return o.toString(2)
    }
}

/** daemon 周期写出的实时状态(status.json), App 只读不写。见优化方案 §3 */
data class LiveStatus(
    val watt: Double = 0.0,
    val charging: Boolean = false,
    val battPct: Int = -1,
    val gpuMhz: Int = -1,
    /** policy -> 当前频率 MHz */
    val clusterFreqMhz: Map<Int, Int> = emptyMap(),
    /** policy -> 当前 governor */
    val clusterGov: Map<Int, String> = emptyMap(),
    val ts: Long = 0,
) {
    companion object {
        val EMPTY = LiveStatus()

        fun parse(raw: String): LiveStatus {
            if (raw.isBlank()) return EMPTY
            return runCatching {
                val o = JSONObject(raw)
                val freqMap = LinkedHashMap<Int, Int>()
                val govMap = LinkedHashMap<Int, String>()
                o.optJSONArray("clusters")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val p = c.optInt("policy")
                        freqMap[p] = c.optInt("cur_mhz", -1)
                        govMap[p] = c.optString("gov")
                    }
                }
                LiveStatus(
                    watt = o.optDouble("watt", 0.0),
                    charging = o.optBoolean("charging", false),
                    battPct = o.optInt("batt_pct", -1),
                    gpuMhz = o.optInt("gpu_mhz", -1),
                    clusterFreqMhz = freqMap,
                    clusterGov = govMap,
                    ts = o.optLong("ts", 0),
                )
            }.getOrDefault(EMPTY)
        }
    }
}
