package com.ktwo.cts.data

import org.json.JSONObject

/**
 * 智能策略(FSM 状态频率上限覆盖)配置模型,读写 config.json 的 Function.AutoMode.fsm_policy 节点。
 *
 * 节点结构(key = 小写状态名,顺序对应 daemon AutoFsm::State 枚举 0..5):
 *   "fsm_policy": { "deep_idle": { "maxL": "40", "maxB": "35", "gpu": "40" }, ... }
 *
 * 语义(与 daemon 侧解析约定一致):
 *  - maxL / maxB:该状态下小核/大核频率上限,按本机 cpuinfo_max_freq 的百分比,有效 10..100;
 *    缺 key / 缺状态 / 越界 = 未设置(-1)= 该项继续跟随内置智能曲线。
 *  - gpu:GPU 频率上限百分比 0..100;0 或缺失 = 跟随内置 AutoFsm::gpuPctForState。
 *  - 没有总开关:fsm_policy 节点缺失/为空即"无覆盖"。
 *  - 值统一写成字符串("40"),与 app_policy 及 daemon readInt(接受数字字符串)约定一致。
 */
data class FsmStateOverride(
    val maxL: Int = -1,   // 小核上限%(10..100),-1 = 未设置(跟随智能曲线)
    val maxB: Int = -1,   // 大核上限%(10..100),-1 = 未设置
    val gpu: Int = 0,     // GPU 上限%(1..100),0 = 未设置(跟随内置)
) {
    /** 是否设置了任一上限(全未设置的状态不写入 JSON)。 */
    val anySet: Boolean get() = maxL in 10..100 || maxB in 10..100 || gpu in 1..100
}

data class SmartPolicyCfg(
    val states: Map<String, FsmStateOverride> = emptyMap(),
    val rawJson: String = "",
) {
    companion object {
        const val UNSET = -1

        // 状态名与 daemon AutoFsm::State 枚举顺序一一对应(0..5),也是 fsm_policy 的 key。
        const val DEEP_IDLE = "deep_idle"
        const val LIGHT_IDLE = "light_idle"
        const val MODERATE = "moderate"
        const val HEAVY = "heavy"
        const val SUSTAINED = "sustained"
        const val GAMING = "gaming"
        val STATE_ORDER = listOf(DEEP_IDLE, LIGHT_IDLE, MODERATE, HEAVY, SUSTAINED, GAMING)

        /** 状态中文名(用户可见)。 */
        val STATE_CN = mapOf(
            DEEP_IDLE to "深度空闲",
            LIGHT_IDLE to "轻度使用",
            MODERATE to "中度负载",
            HEAVY to "高负载",
            SUSTAINED to "持续高载",
            GAMING to "游戏",
        )

        /** 兼容字符串/数字读百分比;缺失或越界返回 unset。 */
        private fun JSONObject.pctOf(key: String, lo: Int, hi: Int, unset: Int): Int {
            val v = when (val x = opt(key)) {
                is Number -> x.toInt()
                is String -> x.trim().toIntOrNull() ?: return unset
                else -> return unset
            }
            return if (v in lo..hi) v else unset
        }

        fun parse(raw: String): SmartPolicyCfg {
            if (raw.isBlank()) return SmartPolicyCfg(rawJson = raw)
            return runCatching {
                val node = JSONObject(raw)
                    .optJSONObject("Function")
                    ?.optJSONObject("AutoMode")
                    ?.optJSONObject("fsm_policy")
                    ?: return SmartPolicyCfg(rawJson = raw)
                val m = HashMap<String, FsmStateOverride>()
                for (s in STATE_ORDER) {
                    val o = node.optJSONObject(s) ?: continue
                    val ov = FsmStateOverride(
                        maxL = o.pctOf("maxL", 10, 100, UNSET),
                        maxB = o.pctOf("maxB", 10, 100, UNSET),
                        gpu = o.pctOf("gpu", 0, 100, 0),
                    )
                    if (ov.anySet) m[s] = ov
                }
                SmartPolicyCfg(states = m, rawJson = raw)
            }.getOrDefault(SmartPolicyCfg(rawJson = raw))
        }
    }

    /**
     * 合并回原始 JSON(读-改-写,其余节点原样保留):
     * 只写有设置项的状态;越界值按未设置省略;六个状态全未设置时整体移除 fsm_policy 节点。
     */
    fun mergeInto(raw: String): String {
        // 非空但解析失败的 config 直接抛异常交给调用方按"保存失败"处理 ——
        // 绝不能凭空造一份只剩 fsm_policy 的 config 覆盖掉用户全部配置。
        val o = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        val f = o.optJSONObject("Function") ?: JSONObject().also { o.put("Function", it) }
        val a = f.optJSONObject("AutoMode") ?: JSONObject().also { f.put("AutoMode", it) }
        val node = JSONObject()
        for (s in STATE_ORDER) {
            val ov = states[s] ?: continue
            if (!ov.anySet) continue
            val so = JSONObject()
            if (ov.maxL in 10..100) so.put("maxL", ov.maxL.toString())
            if (ov.maxB in 10..100) so.put("maxB", ov.maxB.toString())
            if (ov.gpu in 1..100) so.put("gpu", ov.gpu.toString())
            node.put(s, so)
        }
        if (node.length() == 0) a.remove("fsm_policy") else a.put("fsm_policy", node)
        // 4 空格缩进,与出厂 config.json / AutoModeCfg 保持一致。
        return o.toStableString()
    }
}
