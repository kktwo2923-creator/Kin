// 线程分配(M5+) per-app 规则模型与持久化: AppOpt 式「包名{线程名通配}=核范围」规则。
// 设计要点:
//  1. 数据类 TaRule(线程名 glob 模式 → 核范围字符串), 按包名分组存放;
//  2. 持久化直接用 AppOpt applist.conf 兼容的纯文本格式(filesDir/thread_alloc_rules.conf),
//     这样导入/导出与 AppOpt 生态互通, 解析器同时就是导入器;
//  3. 注释行(#)与 USER_ 前缀行在解析时跳过(与 AppOpt WebUI 的保护约定一致), 导出时重新生成区块注释;
//  4. 内置常用预设(摘自 AppOpt 预设库), 核区间按本机 CPU 簇拓扑自适应(clusterPlan);
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
 * 一条线程分配规则。
 * thread = 线程名匹配模式(shell glob 语义: * ? [..] [!..]), 匹配 /proc/<pid>/task/<tid>/comm;
 *          空串表示「包默认兜底」(该包所有未被其它规则命中的线程), 对应 AppOpt 无花括号行。
 * cpus   = 核范围字符串, taskset/cpuset 风格: "7"、"4-6"、"0-2,7"。
 */
data class TaRule(
    val thread: String,
    val cpus: String,
)

/** 本机 CPU 簇布局(按 cpuinfo_max_freq 升序归档), 预设套用时把逻辑档位换算成实际核号。 */
data class TaClusterPlan(
    val small: List<Int>,   // 小核(最低频簇)
    val mid: List<Int>,     // 中核(次低频; 不足三簇时与大核共用)
    val big: List<Int>,     // 大核(次高频簇)
    val prime: List<Int>,   // 超大核(最高频簇)
    val all: List<Int>,     // 全部核心
) {
    /** 除超大核以外的核(游戏模板的默认兜底常用)。 */
    val nonPrime: List<Int>
        get() = all.filter { it !in prime }.ifEmpty { all }
}

/** 内置预设: 一键生成一套规则(核区间按 TaClusterPlan 自适应)。摘自 AppOpt 预设库代表条目。 */
data class TaPreset(
    val id: String,
    val name: String,
    val desc: String,
    val game: Boolean,                                          // 展示「游戏/应用」标签用
    val build: (pkg: String, plan: TaClusterPlan) -> List<TaRule>,
)

/**
 * 规则的解析/序列化/匹配工具 + 预设库(纯函数, 无状态)。
 * 文本格式与 AppOpt applist.conf 兼容:
 *   # 注释行
 *   com.example.app{Thread-*}=0-3      ← 线程级规则
 *   com.example.app=0-2                ← 包默认兜底
 *   com.example.app:push=0-1           ← 子进程规则(完整进程名作为独立键)
 */
object ThreadAllocRules {

    /** 包名/进程名合法形态: 字母开头 + [字母数字._], 可带一个 :子进程 后缀。 */
    private val PKG_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9._]*(:[a-zA-Z0-9._]+)?$")

    /** 游戏规则识别关键词(与 AppOpt WebUI identifyRuleType 同步)。 */
    private val GAME_KEYS = listOf("UnityMain", "Unity[!M]", "Job.Worker", "GameThread", "TaskGraphNP")

    /** comm 上限 16 字节(含结尾符), 模式串按 AppOpt 约定限制在 31 字符内。 */
    private const val MAX_THREAD_LEN = 31

    /** 该包的规则集是否像游戏规则(任一模式串含游戏引擎关键词)。 */
    fun isGameRules(rules: List<TaRule>): Boolean =
        rules.any { r -> GAME_KEYS.any { r.thread.contains(it) } }

    /**
     * 解析 AppOpt 格式文本 → 包名(含 :子进程) → 规则列表。
     * 跳过空行、# 注释、USER_ 全局变量行; 核范围非法(解析不出任何核号)的行丢弃;
     * 每包规范化为「线程规则在前(同模式后者覆盖前者) + 至多一条默认兜底在最后」。
     */
    fun parse(text: String): LinkedHashMap<String, List<TaRule>> {
        val grouped = LinkedHashMap<String, MutableList<TaRule>>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("USER_")) return@forEach
            // 规则行内不允许出现多余 '='(AppOpt 约定), 用最后一个 '=' 切分最稳妥
            val eq = line.lastIndexOf('=')
            if (eq <= 0 || eq == line.length - 1) return@forEach
            val left = line.substring(0, eq).trim()
            val cpus = line.substring(eq + 1).trim()
            if (ThreadAllocator.parseCpuList(cpus).isEmpty()) return@forEach

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
            grouped.getOrPut(pkg) { mutableListOf() }.add(TaRule(thread, cpus))
        }
        val out = LinkedHashMap<String, List<TaRule>>()
        grouped.forEach { (k, v) ->
            val norm = normalize(v)
            if (norm.isNotEmpty()) out[k] = norm
        }
        return out
    }

    /** 规范化一包规则: 剔除非法核范围/超长模式, 同模式后者覆盖, 默认兜底只留一条且放最后。 */
    fun normalize(rules: List<TaRule>): List<TaRule> {
        val threads = LinkedHashMap<String, String>()
        var def: String? = null
        rules.forEach { r ->
            val t = r.thread.trim()
            val c = r.cpus.trim()
            if (ThreadAllocator.parseCpuList(c).isEmpty()) return@forEach
            if (t.isEmpty()) def = c
            else if (t.length <= MAX_THREAD_LEN) threads[t] = c
        }
        val out = threads.map { TaRule(it.key, it.value) }.toMutableList()
        def?.let { out += TaRule("", it) }
        return out
    }

    /**
     * 序列化为 AppOpt 兼容文本(导出/落盘共用)。
     * labelOf 提供包名 → 应用名(区块注释里更可读), 取不到时用包名。
     */
    fun serialize(
        rules: Map<String, List<TaRule>>,
        labelOf: (String) -> String? = { null },
    ): String {
        val sb = StringBuilder()
        sb.append("# OnePlus V Pro 线程分配规则 (兼容 AppOpt applist.conf 语法)\n")
        sb.append("# 格式: 包名{线程名通配}=核范围 | 包名=核范围(默认兜底)\n")
        rules.forEach { (pkg, list) ->
            if (list.isEmpty()) return@forEach
            sb.append('\n')
            sb.append("# ====================")
                .append(labelOf(pkg) ?: pkg)
                .append("====================\n")
            list.filter { it.thread.isNotEmpty() }.forEach { r ->
                sb.append(pkg).append('{').append(r.thread).append("}=").append(r.cpus).append('\n')
            }
            list.firstOrNull { it.thread.isEmpty() }?.let { r ->
                sb.append(pkg).append('=').append(r.cpus).append('\n')
            }
        }
        return sb.toString()
    }

    /**
     * shell glob → Regex(与 AppOpt 的 fnmatch 语义对齐):
     * '*' 任意串、'?' 单字符、'[..]' 字符类、'[!..]' 取反字符类; 其余字符按字面匹配。
     * 编译失败(用户输入畸形字符类)时退化为整串字面匹配, 永不抛出。
     */
    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '[' -> {
                    val end = glob.indexOf(']', i + 1)
                    if (end > i + 1) {
                        var body = glob.substring(i + 1, end)
                        if (body.startsWith("!")) body = "^" + body.substring(1)
                        sb.append('[').append(body.replace("\\", "\\\\")).append(']')
                        i = end
                    } else {
                        sb.append("\\[")
                    }
                }
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        sb.append('$')
        return runCatching { Regex(sb.toString()) }
            .getOrElse { Regex("^" + Regex.escape(glob) + "$") }
    }

    /** glob 是否命中线程名(一次性匹配的便捷入口; 批量匹配请先 globToRegex 预编译)。 */
    fun globMatch(glob: String, name: String): Boolean =
        runCatching { globToRegex(glob).matches(name) }.getOrDefault(false)

    /** 核号列表 → 紧凑范围串: [0,1,2,7] → "0-2,7"。空列表返回空串。 */
    fun cpusToSpec(cpus: List<Int>): String {
        val s = cpus.distinct().sorted()
        if (s.isEmpty()) return ""
        val sb = StringBuilder()
        var start = s[0]
        var prev = s[0]
        fun flush() {
            if (sb.isNotEmpty()) sb.append(',')
            if (start == prev) sb.append(start) else sb.append(start).append('-').append(prev)
        }
        for (i in 1 until s.size) {
            val v = s[i]
            if (v == prev + 1) prev = v
            else { flush(); start = v; prev = v }
        }
        flush()
        return sb.toString()
    }

    /**
     * 主线程 comm: 内核把 comm 截断到 15 字符, 主线程名 = 包名尾部 15 字符
     * (AppOpt 官方预置的 "m.taobao.taobao" 写法即由此来)。
     */
    fun mainThreadComm(pkg: String): String = pkg.substringBefore(':').takeLast(15)

    /**
     * 按 cpufreq 拓扑归档簇布局: policy 按 cpuinfo_max_freq 升序 → 小/中/大/超大。
     * sysfs 读不到时按核数对半兜底(前半小核、后半大核、最后一个当超大核)。
     */
    fun clusterPlan(): TaClusterPlan = runCatching {
        val groups = SysfsReader.discoverPolicies().mapNotNull { p ->
            val cpus = SysfsReader.relatedCpus(p).filter { it in 0..30 }.sorted()
            if (cpus.isEmpty()) null else cpus to SysfsReader.minMaxFreqMhz(p).second
        }.sortedBy { it.second }
        if (groups.isEmpty()) return@runCatching fallbackPlan()
        val c = groups.map { it.first }
        val all = c.flatten().distinct().sorted()
        when (c.size) {
            1 -> TaClusterPlan(c[0], c[0], c[0], c[0], all)
            2 -> TaClusterPlan(c[0], c[0], c[1], c[1], all)
            3 -> TaClusterPlan(c[0], c[1], c[1], c[2], all)
            else -> TaClusterPlan(c[0], c[1], c[c.size - 2], c[c.size - 1], all)
        }
    }.getOrElse { fallbackPlan() }

    private fun fallbackPlan(): TaClusterPlan {
        val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
        val all = (0 until n).toList()
        val small = all.take((n / 2).coerceAtLeast(1))
        val big = all.drop(small.size).ifEmpty { all }
        val prime = listOf(all.last())
        val mid = big.filter { it !in prime }.ifEmpty { big }
        return TaClusterPlan(small, mid, big, prime, all)
    }

    /**
     * 常用预设库(从 AppOpt 报告的模板/预设摘取代表性条目):
     * 普通应用模板(genAppRules)、Unity/非 Unity 游戏模板(A/B)、两条轻量单点预设、省电模板。
     */
    fun presets(): List<TaPreset> = listOf(
        TaPreset(
            id = "game_unity",
            name = "Unity 游戏 (有 UnityMain)",
            desc = "UnityMain 绑超大核, 其余 Unity 线程绑大核, Job.Worker 绑中核 (AppOpt 游戏模板A)",
            game = true,
        ) { _, p ->
            listOf(
                TaRule("UnityMain", cpusToSpec(p.prime)),
                TaRule("Unity[!M]*", cpusToSpec(p.big)),
                TaRule("Job.Worker*", cpusToSpec(p.mid)),
                TaRule("NativeThread", cpusToSpec(p.mid)),
                TaRule("Thread-*", cpusToSpec(p.small)),
                TaRule("*[!e!r]Thread", cpusToSpec(p.small)),
                TaRule("", cpusToSpec(p.nonPrime)),
            )
        },
        TaPreset(
            id = "game_ue",
            name = "UE/自研引擎游戏 (无 UnityMain)",
            desc = "MainThread/GameThread 绑超大核, 渲染线程绑大核 (AppOpt 游戏模板B)",
            game = true,
        ) { _, p ->
            listOf(
                TaRule("MainThread", cpusToSpec(p.prime)),
                TaRule("*GameThread", cpusToSpec(p.prime)),
                TaRule("Render Thread*", cpusToSpec(p.big)),
                TaRule("*[!e!r]Thread", cpusToSpec(p.big)),
                TaRule("NativeThread", cpusToSpec(p.mid)),
                TaRule("Thread*", cpusToSpec(p.small)),
                TaRule("", cpusToSpec(p.nonPrime)),
            )
        },
        TaPreset(
            id = "unity_prime",
            name = "UnityMain+GLThread 绑超大核 (轻量)",
            desc = "只把 UnityMain 与 GLThread 固定到超大核, 其余线程不干预",
            game = true,
        ) { _, p ->
            listOf(
                TaRule("UnityMain", cpusToSpec(p.prime)),
                TaRule("GLThread*", cpusToSpec(p.prime)),
            )
        },
        TaPreset(
            id = "render_big",
            name = "渲染线程绑大核 (轻量)",
            desc = "只把 RenderThread 类线程固定到大核, 其余线程不干预",
            game = false,
        ) { _, p ->
            listOf(
                TaRule("RenderThread*", cpusToSpec(p.big)),
                TaRule("Render Thread*", cpusToSpec(p.big)),
            )
        },
        TaPreset(
            id = "app_balanced",
            name = "普通应用 · 均衡",
            desc = "主线程/渲染/解码放中核, binder/IO 与其余线程放小核 (AppOpt 应用模板)",
            game = false,
        ) { pkg, p ->
            val mid = cpusToSpec(p.mid)
            val small = cpusToSpec(p.small)
            listOf(
                TaRule(mainThreadComm(pkg), mid),
                TaRule("Thread-*", mid),
                TaRule("Thread*", mid),
                TaRule("*[!e!r]Thread", mid),
                TaRule("binder:*", small),
                TaRule("MediaCodec_*", mid),
                TaRule("VDecod2-*", mid),
                TaRule("", small),
            )
        },
        TaPreset(
            id = "app_powersave",
            name = "普通应用 · 省电",
            desc = "全部线程压到小核, 优先省电(可能影响流畅度)",
            game = false,
        ) { _, p ->
            listOf(TaRule("", cpusToSpec(p.small)))
        },
    )

    /** 按 id 取预设(找不到返回 null)。 */
    fun presetById(id: String): TaPreset? = presets().firstOrNull { it.id == id }

    // ── 自动分配(参考 JZzz「自动线程分配」的分桶思路: 按引擎类型套模板) ──

    /**
     * 各引擎的判定关键字(comm 子串, 不区分大小写)。
     * 注意: 只收【游戏引擎特有】的线程名 —— 像 RenderThread / GLThread / NativeThread 几乎每个
     * 安卓应用都有(硬件渲染线程等), 不能当游戏标志, 否则会把微信/抖音等普通应用误判成游戏。
     */
    private val UNITY_KEYS = listOf("UnityMain", "UnityGfx", "UnityMulti")   // Unity 引擎特征
    private val ENGINE_KEYS = listOf(   // 非 Unity 但明确是游戏引擎(UE/Cocos/自研)的主线程标志
        "GameThread", "UnrealMain", "RHIThread", "Cocos", "GameMain", "GfxThread",
    )

    /**
     * 按「实时线程名 + 是否游戏类别」自动判定最合适的预设 id:
     *  - 有 Unity 引擎线程(UnityMain 等)               → Unity 游戏预设(game_unity);
     *  - 否则出现 UE/自研引擎主线程 或 系统类别判定为游戏 → 非 Unity 游戏预设(game_ue);
     *  - 其余                                            → 普通应用均衡预设(app_balanced)。
     * threadNames 为空(应用没运行/读不到线程)时只靠 isGame 兜底判定。
     */
    fun detectPresetId(threadNames: List<String>, isGame: Boolean): String {
        fun hasAny(keys: List<String>): Boolean =
            threadNames.any { n -> keys.any { k -> n.contains(k, ignoreCase = true) } }
        return when {
            hasAny(UNITY_KEYS)       -> "game_unity"
            hasAny(ENGINE_KEYS)      -> "game_ue"
            isGame                   -> "game_ue"
            else                     -> "app_balanced"
        }
    }

    /**
     * 自动识别的判定来源(供 UI 如实告知, 别把兜底猜测说成"已识别"):
     *  - LIVE     : 读到应用实时线程后判定(命中引擎线程, 或据实况判为普通应用) —— 可靠;
     *  - CATEGORY : 没读到线程, 但系统把应用标为游戏 —— 中等可靠;
     *  - FALLBACK : 既没读到线程也不是游戏类别(应用没运行/无 Root), 只能按普通应用兜底 —— 不可靠。
     */
    enum class Source { LIVE, CATEGORY, FALLBACK }

    /** 自动识别结果: 预设 + 生成的规则 + 判定来源 + 实际读到的线程数(0 = 没读到)。 */
    data class AutoResult(
        val preset: TaPreset,
        val rules: List<TaRule>,
        val source: Source,
        val threadsRead: Int,
    )

    /**
     * 一步到位: 自动判定类型并生成一套规则(核区间按本机簇布局自适应)。
     * 判不出预设时退回普通应用均衡预设; source 如实反映判定依据(见 Source)。
     */
    fun autoGenerate(
        pkg: String,
        plan: TaClusterPlan,
        threadNames: List<String>,
        isGame: Boolean,
    ): AutoResult {
        val id = detectPresetId(threadNames, isGame)
        val preset = presetById(id) ?: presets().first { it.id == "app_balanced" }
        val source = when {
            threadNames.isNotEmpty() -> Source.LIVE       // 读到线程 → 基于实况(命中引擎或判为普通应用)
            isGame                   -> Source.CATEGORY   // 没线程但系统标为游戏
            else                     -> Source.FALLBACK   // 既没线程也非游戏类别 → 纯兜底猜测
        }
        return AutoResult(preset, normalize(preset.build(pkg, plan)), source, threadNames.size)
    }
}

/**
 * per-app 规则的持久化仓库: filesDir/thread_alloc_rules.conf(AppOpt 兼容文本)。
 * 内存缓存 MutableStateFlow 供 UI collectAsState; 所有写操作 Mutex 串行化后整文件覆写,
 * 与 AppOpt WebUI「整文件文本变换 + 覆写」同一模型。修改后由调用方负责 syncRules 下发守护。
 */
object ThreadAllocRuleStore {

    private const val FILE_NAME = "thread_alloc_rules.conf"
    private val mutex = Mutex()
    private val cache = MutableStateFlow<Map<String, List<TaRule>>?>(null)

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    /** 全量规则流(首次订阅时懒加载文件); UI 用 collectAsState(initial = emptyMap())。 */
    fun flow(ctx: Context): Flow<Map<String, List<TaRule>>> {
        val app = ctx.applicationContext
        return cache.onStart { all(app) }.filterNotNull()
    }

    /** 读全量规则(带缓存)。任何异常都返回空表, 不抛出。 */
    suspend fun all(ctx: Context): Map<String, List<TaRule>> = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked(ctx) }
    }

    /** 写入/覆盖某包(或 pkg:子进程 键)的规则; rules 为空视为删除该键。 */
    suspend fun put(ctx: Context, pkg: String, rules: List<TaRule>): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val cur = LinkedHashMap(loadLocked(ctx))
                val norm = ThreadAllocRules.normalize(rules)
                if (norm.isEmpty()) cur.remove(pkg) else cur[pkg] = norm
                persistLocked(ctx, cur)
            }
        }

    /** 删除若干键(主进程 + 子进程一起删时传全部键)。 */
    suspend fun remove(ctx: Context, pkgs: Collection<String>): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val cur = LinkedHashMap(loadLocked(ctx))
                pkgs.forEach { cur.remove(it) }
                persistLocked(ctx, cur)
            }
        }

    /**
     * 合并导入(剪贴板导入用): parsed 覆盖同名键, 其余保留。返回导入的键数。
     * 调用方先用 ThreadAllocRules.parse 解析并向用户确认, 再调本方法落盘。
     */
    suspend fun importRules(ctx: Context, parsed: Map<String, List<TaRule>>): Int =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (parsed.isEmpty()) return@withLock 0
                val cur = LinkedHashMap(loadLocked(ctx))
                cur.putAll(parsed)
                persistLocked(ctx, cur)
                parsed.size
            }
        }

    /** 导出为 AppOpt 兼容文本(labelOf 提供应用名注释)。 */
    suspend fun exportText(ctx: Context, labelOf: (String) -> String? = { null }): String =
        withContext(Dispatchers.IO) {
            mutex.withLock { ThreadAllocRules.serialize(loadLocked(ctx), labelOf) }
        }

    /**
     * 把当前全量规则下发给 Kin 守护(写 /sdcard/Android/Kin/thread_alloc_rules.conf, 守护热重载后按规则绑核)。
     * 每次规则增删改都会自动下发一次(见 persistLocked); 这里额外提供显式入口,
     * 供「启用线程分配」开关打开时补推一次(用户可能先建了规则、后开开关)。
     * 无 root / 写失败静默(不抛出), 返回是否写成功。
     */
    suspend fun syncToDaemon(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { RootShell.writeThreadRules(ThreadAllocRules.serialize(loadLocked(ctx))) }
                .getOrDefault(false)
        }
    }

    // ── 内部: 以下两个方法假定已持有 mutex ──

    private fun loadLocked(ctx: Context): Map<String, List<TaRule>> {
        cache.value?.let { return it }
        val text = runCatching {
            file(ctx).takeIf { it.exists() }?.readText()
        }.getOrNull().orEmpty()
        val parsed: Map<String, List<TaRule>> = ThreadAllocRules.parse(text)
        cache.value = parsed
        return parsed
    }

    private fun persistLocked(ctx: Context, map: Map<String, List<TaRule>>) {
        val text = ThreadAllocRules.serialize(map)
        runCatching { file(ctx).writeText(text) }
        cache.value = LinkedHashMap(map)
        // 规则一改就下发守护: 守护读 thread_alloc_rules.conf(mtime 变即热重载)后按新规则绑核。
        // 无 root 时静默失败, 不影响 App 内规则的保存与展示。
        runCatching { RootShell.writeThreadRules(text) }
    }
}
