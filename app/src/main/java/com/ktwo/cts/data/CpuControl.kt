package com.ktwo.cts.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * CPU 控制面板数据模型,直接读写 daemon 的 config.json。
 *
 * config.json 结构要点:
 *  - Policy.cN 映射到物理 policy 编号(如 SM8850: c0->policy0, c1->policy6)。
 *  - freq/GPU/CoreOnline 值均为字符串类型,与 daemon 解析逻辑一致。
 *  - 读-改-写:只动选中 model 的字段,其余 model/meta/Function/Policy/blacklist 原样保留。
 */

/** cN 索引 -> 物理 policy 编号(-1 表示该簇不存在)。 */
data class PolicyMap(val cToPolicy: List<Int> = listOf(0, 6, -1, -1)) {
    val activeClusters: List<Int> get() = cToPolicy.indices.filter { cToPolicy[it] >= 0 }
    fun policyOf(cIndex: Int): Int = cToPolicy.getOrElse(cIndex) { -1 }
    fun displayName(cIndex: Int): String = when (cIndex) {
        0 -> "性能核"
        1 -> "大核"
        else -> "簇$cIndex"
    }

    companion object {
        fun parse(o: JSONObject): PolicyMap {
            val p = o.optJSONObject("Policy") ?: return PolicyMap()
            val list = (0..3).map { i ->
                // Policy 值可能是字符串或数字;255/-1 均表示无效
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

/** 单个簇(cN)在某个 model 里的频率 + 调速器设定;值保持字符串与 daemon 一致。 */
data class ClusterModelCfg(
    val cIndex: Int,            // 对应 freq_*_cN / gov_cN
    val policy: Int,            // 由 PolicyMap 映射出的物理 policy
    val minFreqKhz: String = "",
    val maxFreqKhz: String = "",
    val governor: String = "",
) {
    val minKhzLong: Long get() = minFreqKhz.toLongOrNull() ?: 0L
    val maxKhzLong: Long get() = maxFreqKhz.toLongOrNull() ?: 0L
}

data class ProfileModel(
    val modelName: String,
    val mode: String = "",
    val isGame: Boolean = false,
    val clusters: List<ClusterModelCfg> = emptyList(),
    val gpuMinMhz: String = "",
    val gpuMaxMhz: String = "",
    val coreOnline: List<Int> = List(8) { -1 },   // -1=不指定, 0=关, 1=开
) {
    val gpuMinInt: Int get() = gpuMinMhz.toIntOrNull() ?: 0
    val gpuMaxInt: Int get() = gpuMaxMhz.toIntOrNull() ?: 0
}

/** config.json 的面板可编辑视图;rawJson 保留原始文本用于读-改-写。 */
data class CpuControlCfg(
    val policyMap: PolicyMap = PolicyMap(),
    val profiles: List<ProfileModel> = emptyList(),
    val rawJson: String = "",
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

    /** 把选中 model 的改动合并回原始 JSON;其余 model/字段原样保留。 */
    fun mergeProfileInto(raw: String, p: ProfileModel): String {
        val o = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val models = o.optJSONArray("models") ?: JSONArray().also { o.put("models", it) }

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

        p.clusters.forEach { c ->
            target.put("freq_min_c${c.cIndex}", c.minFreqKhz)
            target.put("freq_max_c${c.cIndex}", c.maxFreqKhz)
            // 整体保留原 gov_cN(尤其 params: up_rate_limit_us/down_rate_limit_us 等 governor 调参),
            // 只在确有新值时覆盖 governor;留空不覆盖,避免把原调速器/参数抹掉。
            val govObj = target.optJSONObject("gov_c${c.cIndex}") ?: JSONObject()
            if (c.governor.isNotEmpty()) govObj.put("governor", c.governor)
            target.put("gov_c${c.cIndex}", govObj)
        }

        target.put("gpu_min_mhz", p.gpuMinMhz)
        target.put("gpu_max_mhz", p.gpuMaxMhz)

        // CoreOnline 值写字符串;-1 让 daemon 跳过该核
        target.put("CoreOnline", JSONObject().apply {
            p.coreOnline.forEachIndexed { i, v -> put("Core$i", v.toString()) }
        })

        return o.toString(2)
    }
}

/** App 自读 sysfs 得到的实时状态(功耗/电量/GPU/各簇频率与调速器)。 */
data class LiveStatus(
    val watt: Double = 0.0,
    val charging: Boolean = false,
    val battPct: Int = -1,
    val gpuMhz: Int = -1,
    val clusterFreqMhz: Map<Int, Int> = emptyMap(),  // policy -> 当前频率 MHz
    val clusterGov: Map<Int, String> = emptyMap(),   // policy -> 当前 governor
) {
    companion object {
        val EMPTY = LiveStatus()
    }
}
