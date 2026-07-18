// JSON 序列化统一出口: 解决"App 读写后打乱 config 原本格式"的问题。
//
// 两个根因:
//  1. org.json 序列化会把 '/' 转义成 '\/'(URL/路径被加反斜杠) —— 这里还原, 输出更贴近原文;
//  2. 键顺序: Android 的 JSONObject 底层是 LinkedHashMap, 解析→序列化【保留插入顺序】,
//     所以只要"读出来的对象上原地增改键、不重建整个对象", 顺序就不会乱。
//
// 统一用 4 空格缩进(与仓库出厂 config.json 一致)。所有写盘前的序列化都走 stableString(),
// 避免各处 toString(4) 行为不一致。
package com.ktwo.cts.data

import org.json.JSONObject

object KinJson {
    /** 4 空格缩进序列化 + 还原被 org.json 转义的斜杠, 尽量贴近原始格式。 */
    fun stableString(o: JSONObject): String = o.toString(4).replace("\\/", "/")
}

/** 便捷扩展: 与 KinJson.stableString 等价, 供替换散落各处的 .toString(4)。 */
fun JSONObject.toStableString(): String = KinJson.stableString(this)
