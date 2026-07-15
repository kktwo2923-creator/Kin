package com.ktwo.cts.data

import org.json.JSONObject

/**
 * 接管方式:
 *  - 默认接管 = 本 App 接管调度, 应用画像/智能分类可用(Function.AppProfile.enable=true)。
 *  - 第三方接管 = 交给 Scene 等调度管理器 —— 停用应用画像, 守护只按基础模式
 *    (省电/均衡/性能/极速)执行, 档位切换由第三方经 powercfg.sh 协议下发。
 */
object TakeoverCfg {
    /** 从 config.json 读是否第三方接管(AppProfile.enable=false 即第三方); 读不到按默认接管。 */
    fun isThirdParty(raw: String): Boolean = runCatching {
        when (val e = JSONObject(raw).optJSONObject("Function")
            ?.optJSONObject("AppProfile")?.opt("enable")) {
            is Boolean -> !e
            is String -> e.trim().lowercase() in setOf("false", "0", "off", "no")
            else -> false
        }
    }.getOrDefault(false)

    /** 写回接管方式(读-改-写, 其余节点保留)。非空但解析失败抛异常, 由调用方按"写失败"处理。 */
    fun mergeInto(raw: String, thirdParty: Boolean): String {
        val o = if (raw.isBlank()) JSONObject() else JSONObject(raw)
        val f = o.optJSONObject("Function") ?: JSONObject().also { o.put("Function", it) }
        val a = f.optJSONObject("AppProfile") ?: JSONObject().also { f.put("AppProfile", it) }
        a.put("enable", (!thirdParty).toString())
        return o.toString(4)
    }
}
