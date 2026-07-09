// 实时调度优先级(M6): per-app「线程名通配 → 调度策略+优先级」规则, 与绑核规则(ThreadAllocRules)平行。
// 设计要点:
//  1. 数据类 TaPrioRule(线程名 glob → 策略 SCHED_FIFO/RR/OTHER + 优先级), 按包名分组;
//  2. 持久化用与 AppOpt priority.prop 兼容的纯文本 `包名{线程}=SCHED_FIFO 优先级`(可与其生态互导);
//  3. 落盘 filesDir/thread_priority_rules.conf, 每次写都推给守护(RootShell.writeThreadPriorityRules) 热重载;
//  4. 内置安全预设: 优先级取低值(1-3), 避免实时线程饿死系统; 复用 ThreadAllocRules 的簇布局与主线程名;
//  5. 全部 IO 走 Dispatchers.IO + Mutex 串行化, runCatching 兜底, 永不抛出。
package com.ktwo.cts.data

import android.content.Context
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一条实时优先级规则。
 * thread = 线程名匹配模式(glob, 空串=该包默认兜底);
 * policy = SCHED_OTHER(标准分时, prio 必须 0) / SCHED_FIFO / SCHED_RR(实时, prio 1-99);
 * prio   = 调度优先级。
 */
data class TaPrioRule(
    val thread: String,
    val policy: String,
    val prio: Int,
)

/** 实时优先级预设(核区间无关, 只挑关键线程给低值实时优先级)。 */
data class TaPrioPreset(
    val id: String,
    val name: String,
    val desc: String,
    val build: (pkg: String) -> List<TaPrioRule>,
)

object ThreadPrioRules {

    const val OTHER = "SCHED_OTHER"
    const val FIFO = "SCHED_FIFO"
    const val RR = "SCHED_RR"

    val POLICIES = listOf(OTHER, FIFO, RR)

    private val PKG_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9._-]*(:[a-zA-Z0-9._-]+)?$")
    private const val MAX_THREAD_LEN = 31

    /** 规范化: 剔除非法(策略未知/优先级越界/超长模式), 同模式后者覆盖, 默认兜底只留一条放最后。 */
    fun normalize(rules: List<TaPrioRule>): List<TaPrioRule> {
        val threads = LinkedHashMap<String, TaPrioRule>()
        var def: TaPrioRule? = null
        rules.forEach { r ->
            val v = validate(r) ?: return@forEach
            if (v.thread.isEmpty()) def = v
            else if (v.thread.length <= MAX_THREAD_LEN) threads[v.thread] = v
        }
        val out = threads.values.toMutableList()
        def?.let { out += it }
        return out
    }

    /** 校验单条规则; 非法返回 null。FIFO/RR 优先级夹到 1..99; OTHER 优先级强制 0。 */
    fun validate(r: TaPrioRule): TaPrioRule? {
        val policy = r.policy.trim().uppercase().let {
            when (it) {
                "SCHED_OTHER", "OTHER" -> OTHER
                "SCHED_FIFO", "FIFO" -> FIFO
                "SCHED_RR", "RR" -> RR
                else -> return null
            }
        }
        val prio = when (policy) {
            FIFO, RR -> r.prio.coerceIn(1, 99)
            else -> 0
        }
        return TaPrioRule(r.thread.trim(), policy, prio)
    }

    /**
     * 解析 AppOpt priority.prop 兼容文本 → 包名 → 规则列表。
     * 行格式: 包名{线程}=SCHED_FIFO 10 或 包名=SCHED_OTHER 0(默认兜底)。跳过空行/#注释/USER_ 行。
     */
    fun parse(text: String): LinkedHashMap<String, List<TaPrioRule>> {
        val grouped = LinkedHashMap<String, MutableList<TaPrioRule>>()
        text.lineSequence().forEach { raw ->
            var line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("USER_")) return@forEach
            val hash = line.indexOf('#')
            if (hash >= 0) line = line.substring(0, hash).trim()
            val eq = line.indexOf('=')
            if (eq <= 0 || eq == line.length - 1) return@forEach
            val left = line.substring(0, eq).trim()
            val right = line.substring(eq + 1).trim()
            // 右侧: 策略 + 优先级(空白分隔)
            val parts = right.split(Regex("\\s+"))
            val policy = parts.getOrNull(0) ?: return@forEach
            val prio = parts.getOrNull(1)?.toIntOrNull() ?: 0

            val brace = left.indexOf('{')
            val pkg: String
            val thread: String
            if (brace >= 0) {
                if (!left.endsWith("}")) return@forEach
                pkg = left.substring(0, brace).trim()
                thread = left.substring(brace + 1, left.length - 1).trim()
                if (thread.isEmpty()) return@forEach
            } else {
                pkg = left
                thread = ""
            }
            if (!PKG_REGEX.matches(pkg)) return@forEach
            val v = validate(TaPrioRule(thread, policy, prio)) ?: return@forEach
            grouped.getOrPut(pkg) { mutableListOf() }.add(v)
        }
        val out = LinkedHashMap<String, List<TaPrioRule>>()
        grouped.forEach { (k, list) ->
            val norm = normalize(list)
            if (norm.isNotEmpty()) out[k] = norm
        }
        return out
    }

    /** 序列化为 AppOpt priority.prop 兼容文本。 */
    fun serialize(
        rules: Map<String, List<TaPrioRule>>,
        labelOf: (String) -> String? = { null },
    ): String {
        val sb = StringBuilder()
        sb.append("# OnePlus V Pro 实时优先级规则 (兼容 AppOpt priority.prop 语法)\n")
        sb.append("# 格式: 包名{线程名通配}=策略 优先级 | 策略: SCHED_OTHER(0) / SCHED_FIFO(1-99) / SCHED_RR(1-99)\n")
        rules.forEach { (pkg, list) ->
            if (list.isEmpty()) return@forEach
            sb.append('\n')
            sb.append("# ====================").append(labelOf(pkg) ?: pkg).append("====================\n")
            list.filter { it.thread.isNotEmpty() }.forEach { r ->
                sb.append(pkg).append('{').append(r.thread).append("}=").append(r.policy).append(' ').append(r.prio).append('\n')
            }
            list.firstOrNull { it.thread.isEmpty() }?.let { r ->
                sb.append(pkg).append('=').append(r.policy).append(' ').append(r.prio).append('\n')
            }
        }
        return sb.toString()
    }

    /** 该包是否配了任何实时(FIFO/RR)规则。 */
    fun hasRealtime(rules: List<TaPrioRule>): Boolean =
        rules.any { it.policy == FIFO || it.policy == RR }

    /**
     * 内置安全预设: 优先级刻意取低值(1-3), 只提关键线程, 避免实时线程霸占 CPU。
     * 线程名沿用 AppOpt/游戏引擎常见命名; 主线程名按包名尾 15 字符(内核 comm 截断)。
     */
    fun presets(): List<TaPrioPreset> = listOf(
        TaPrioPreset(
            id = "game_key",
            name = "游戏关键线程 → 实时(推荐)",
            desc = "UnityMain/UE 主线程/渲染线程给 SCHED_FIFO 低优先级, 抢占普通线程更不掉帧",
        ) { _ ->
            listOf(
                TaPrioRule("UnityMain", FIFO, 2),
                TaPrioRule("UEGameThread", FIFO, 2),
                TaPrioRule("*GameThread", FIFO, 2),
                TaPrioRule("MainThread", FIFO, 2),
                TaPrioRule("RenderThread*", FIFO, 1),
                TaPrioRule("Render Thread*", FIFO, 1),
                TaPrioRule("UnityGfx*", RR, 1),
            )
        },
        TaPrioPreset(
            id = "render_only",
            name = "仅渲染/主线程 → 实时(轻量)",
            desc = "只把渲染线程与 UnityMain 提到 SCHED_FIFO 1, 干预最小",
        ) { pkg ->
            listOf(
                TaPrioRule("UnityMain", FIFO, 1),
                TaPrioRule("RenderThread*", FIFO, 1),
                TaPrioRule(ThreadAllocRules.mainThreadComm(pkg), FIFO, 1),
            )
        },
        TaPrioPreset(
            id = "clear",
            name = "清除(全部恢复普通调度)",
            desc = "把该应用所有线程规则设为 SCHED_OTHER, 等于取消实时",
        ) { _ ->
            listOf(TaPrioRule("", OTHER, 0))
        },
    )
}

/**
 * per-app 实时优先级规则仓库: filesDir/thread_priority_rules.conf(AppOpt priority.prop 兼容)。
 * 与 ThreadAllocRuleStore 同构: 内存缓存 + Mutex 串行化整文件覆写, 每次写都推给守护热重载。
 */
object ThreadPrioRuleStore {

    private const val FILE_NAME = "thread_priority_rules.conf"
    private val mutex = Mutex()
    private val cache = MutableStateFlow<Map<String, List<TaPrioRule>>?>(null)

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun flow(ctx: Context): Flow<Map<String, List<TaPrioRule>>> {
        val app = ctx.applicationContext
        return cache.onStart { all(app) }.filterNotNull()
    }

    suspend fun all(ctx: Context): Map<String, List<TaPrioRule>> = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked(ctx) }
    }

    suspend fun put(ctx: Context, pkg: String, rules: List<TaPrioRule>): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val cur = LinkedHashMap(loadLocked(ctx))
                val norm = ThreadPrioRules.normalize(rules)
                if (norm.isEmpty()) cur.remove(pkg) else cur[pkg] = norm
                persistLocked(ctx, cur)
            }
        }

    suspend fun remove(ctx: Context, pkgs: Collection<String>): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val cur = LinkedHashMap(loadLocked(ctx))
                pkgs.forEach { cur.remove(it) }
                persistLocked(ctx, cur)
            }
        }

    suspend fun importRules(ctx: Context, parsed: Map<String, List<TaPrioRule>>): Int =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (parsed.isEmpty()) return@withLock 0
                val cur = LinkedHashMap(loadLocked(ctx))
                cur.putAll(parsed)
                persistLocked(ctx, cur)
                parsed.size
            }
        }

    suspend fun exportText(ctx: Context, labelOf: (String) -> String? = { null }): String =
        withContext(Dispatchers.IO) {
            mutex.withLock { ThreadPrioRules.serialize(loadLocked(ctx), labelOf) }
        }

    /** 显式把当前规则补推守护一次(开「实时调度」开关时用)。 */
    suspend fun syncToDaemon(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { RootShell.writeThreadPriorityRules(ThreadPrioRules.serialize(loadLocked(ctx))) }
                .getOrDefault(false)
        }
    }

    private fun loadLocked(ctx: Context): Map<String, List<TaPrioRule>> {
        cache.value?.let { return it }
        val text = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
        val parsed = ThreadPrioRules.parse(text)
        cache.value = parsed
        return parsed
    }

    private fun persistLocked(ctx: Context, map: Map<String, List<TaPrioRule>>) {
        val text = ThreadPrioRules.serialize(map)
        runCatching { file(ctx).writeText(text) }
        cache.value = LinkedHashMap(map)
        // 规则一改就推守护(mtime/size 变即热重载); 无 root 静默失败。
        runCatching { RootShell.writeThreadPriorityRules(text) }
    }
}
