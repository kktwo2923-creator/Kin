// 线程分配 → Kin 守护的配置下发: 读-改-写 config.json 的 Function.ThreadAlloc 节点。
// 守护读该节点(enable/interval_s/big_only/game_pkgs)后有两条生效路径, 持续生效, 区别于
// ThreadAllocator.applyAffinity 的"立即应用一次":
//   ① 规则引擎(AppOpt 式): 读 thread_alloc_rules.conf(见 ThreadAllocRuleStore.syncToDaemon /
//      RootShell.writeThreadRules), 扫 /proc 对命中 "pkg{线程}=核" 规则的所有进程逐线程绑核;
//   ② 游戏库快路径: 前台是 game_pkgs 中的游戏且未被规则覆盖时, 把渲染类线程绑到大核。
// 本类只管 config.json 节点(总开关 + 游戏库); 具体规则文本走 ThreadAllocRuleStore 独立下发。
// 铁律: 只动 ThreadAlloc 段; Function 下其它子节点(AutoMode/BgTrim/TouchBoost/...)与
// 顶层其它键(Policy/models/meta/...)原样保留 —— 做法与 AutoModeCfg.mergeInto 一致。
package com.ktwo.cts.data

import android.content.Context
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

object ThreadAllocDaemonCfg {

    private const val DEF_INTERVAL_S = 3
    private const val DEF_BIG_ONLY = true

    // config.json 里同时存着用户的调度画像/智能档设置。连点勾选游戏会并发触发 read-改-write,
    // 后发先至可能丢写。用一把 Mutex 串行化整个"读→合并→写→刷备份", 保证每次基于最新全量落盘。
    private val writeMutex = Mutex()

    /**
     * 把线程分配的总开关与游戏库同步进 config.json 的 Function.ThreadAlloc。
     *  - enable / game_pkgs 以传入值为准; interval_s / big_only 若 config 里已有合法值则保留,
     *    缺失或非法时才落默认("3" / "true"), 不会每次强制覆盖。
     *  - 值统一写字符串("true"/"3"), 与既有 config.json 及守护 readBool/readInt 容错约定一致。
     *  - 成功写回后同步刷新私有备份 config_backup.json, 否则重启时 BootReceiver 会用旧备份
     *    覆盖掉刚写入的 ThreadAlloc 段。
     *  - 全程 runCatching, IO 线程执行; 无 root/读写失败时静默返回 false, 不打断用户。
     */
    suspend fun sync(ctx: Context, enabled: Boolean, gamePkgs: Set<String>): Boolean =
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                runCatching {
                    val raw = RootShell.readConfigRaw()
                    val merged = mergeThreadAlloc(raw, enabled, gamePkgs)
                    val written = RootShell.writeConfigRaw(merged)
                    if (written) {
                        runCatching {
                            java.io.File(ctx.filesDir, "config_backup.json").writeText(merged)
                        }
                    }
                    written
                }.getOrDefault(false)
            }
        }

    /** 读-改-写: 只更新 Function.ThreadAlloc, 其余节点原样保留。raw 为空/非法时从 {} 起。 */
    fun mergeThreadAlloc(raw: String, enabled: Boolean, gamePkgs: Set<String>): String {
        val o = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val f = o.optJSONObject("Function") ?: JSONObject().also { o.put("Function", it) }
        val t = f.optJSONObject("ThreadAlloc") ?: JSONObject().also { f.put("ThreadAlloc", it) }

        t.put("enable", enabled.toString())
        t.put("interval_s", intOf(t, "interval_s", DEF_INTERVAL_S).coerceIn(1, 60).toString())
        t.put("big_only", boolOf(t, "big_only", DEF_BIG_ONLY).toString())
        t.put("game_pkgs", gamePkgs.filter { it.isNotBlank() }.sorted().joinToString(","))

        // 4 空格缩进, 与出厂 config.json / AutoModeCfg 写回保持一致。
        return o.toString(4)
    }

    /** 兼容字符串/数字/布尔三种写法读 Bool(与 AutoModeCfg 同款容错)。 */
    private fun boolOf(o: JSONObject, key: String, def: Boolean): Boolean = when (val v = o.opt(key)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> when (v.trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> def
        }
        else -> def
    }

    /** 兼容字符串/数字读 Int。 */
    private fun intOf(o: JSONObject, key: String, def: Int): Int = when (val v = o.opt(key)) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull() ?: def
        else -> def
    }
}
