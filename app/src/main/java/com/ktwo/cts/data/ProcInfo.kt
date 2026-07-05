// 进程管理器(M4)数据层: 全量进程列表 + 单进程详情 + 结束/强停。需 Root。
package com.ktwo.cts.data

import android.content.Context
import android.graphics.drawable.Drawable
import com.ktwo.cts.root.RootShell

/**
 * 进程管理器数据源。仅在后台线程调用。
 *  - 列表: toybox `top -b -n 1`(一次 exec, 自带 %CPU/RES/USER/S/CMDLINE)。
 *  - 详情: /proc/<pid>/{status,cmdline,cpuset,cgroup,oom_score_adj,oom_adj}(root cat, 一次 exec)。
 * 所有 root 读写 runCatching 降级: 无 root / 读失败一律返回空表或 null, 字段缺失填 "--", 不造假。
 */
object ProcInfo {

    /** 列表行: top 一行解析结果。rssMb=-1 表示解析失败。 */
    data class ProcFull(
        val pid: Int,
        val name: String,       // cmdline 首段 basename(安卓应用即 包名[:子进程])
        val cpuPct: Float,
        val rssMb: Int,
        val user: String,
        val state: Char,
        val cmdline: String,    // top 的完整 CMDLINE 列
    )

    /** 详情(全部来自 /proc, 取不到的字段为 "--")。CPU%/User/应用名由列表行补充。 */
    data class ProcDetail(
        val pid: Int,
        val ppid: String,
        val uid: String,
        val state: String,       // status 的 State 行, 如 "S (sleeping)"
        val command: String,     // status 的 Name
        val cmdline: String,     // /proc/<pid>/cmdline, \0 → 空格
        val oomAdj: String,
        val oomScoreAdj: String,
        val cpuset: String,
        val cgroup: String,
        val cpusAllowed: String, // Cpus_allowed_list
        val resMb: String,       // VmRSS(MB)
        val swapMb: String,      // VmSwap(MB)
    )

    /** 进程名主段: 第一个 ':' 前(安卓子进程形如 pkg:push), 即候选包名/分组键。 */
    fun mainPkg(procName: String): String = procName.substringBefore(':').trim()

    // 与 ProcSampler.resToMb 同思路: top 的 RES 列带 K/M/G 后缀, 纯数字按 KB。
    private fun resToMb(res: String): Int {
        val r = res.trim()
        if (r.isEmpty()) return -1
        val num = r.dropLast(1).toFloatOrNull()
        return when (r.last()) {
            'G', 'g' -> ((num ?: return -1) * 1024).toInt()
            'M', 'm' -> (num ?: return -1).toInt()
            'K', 'k' -> ((num ?: return -1) / 1024).toInt()
            else -> r.toLongOrNull()?.let { (it / 1024).toInt() } ?: -1
        }
    }

    /**
     * 全量进程列表(按 %CPU 由 top 预排序, 取前 300 行)。无 root 返回空表。
     * 表头/汇总行 PID 列非数字, 解析时自然跳过, 无需按行号截头。
     */
    fun listProcesses(): List<ProcFull> {
        if (!RootShell.isRooted()) return emptyList()
        val out = runCatching {
            RootShell.exec(
                "top -b -n 1 -o PID,USER,RES,%CPU,S,CMDLINE -s 4 2>/dev/null | head -n 300"
            )
        }.getOrDefault("")
        val rows = ArrayList<ProcFull>()
        out.lineSequence().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 6)
            if (parts.size < 6) return@forEach
            val pid = parts[0].toIntOrNull() ?: return@forEach
            val cpu = parts[3].toFloatOrNull() ?: return@forEach
            val state = parts[4].firstOrNull() ?: 'S'
            val cmd = parts[5].trim()
            val name = cmd.substringBefore(' ')
                .substringAfterLast('/')
                .trim('[', ']')
                .ifBlank { cmd.ifBlank { pid.toString() } }
            rows.add(ProcFull(pid, name, cpu, resToMb(parts[2]), parts[1], state, cmd))
        }
        return rows
    }

    private const val SEP = "==@CTS@=="

    /**
     * 单进程详情: 一次 root exec 串读 6 个 /proc 文件, 用分段标记切块。
     * status 读不到(进程已退出/无 root)返回 null; 其余文件缺失对应字段填 "--"。
     */
    fun detail(pid: Int): ProcDetail? {
        if (!RootShell.isRooted()) return null
        val p = "/proc/$pid"
        val out = runCatching {
            RootShell.exec(
                "echo '${SEP}status'; cat $p/status 2>/dev/null; " +
                "echo '${SEP}cmdline'; tr '\\0' ' ' < $p/cmdline 2>/dev/null; echo; " +
                "echo '${SEP}cpuset'; cat $p/cpuset 2>/dev/null; " +
                "echo '${SEP}cgroup'; cat $p/cgroup 2>/dev/null; " +
                "echo '${SEP}oom_score_adj'; cat $p/oom_score_adj 2>/dev/null; " +
                "echo '${SEP}oom_adj'; cat $p/oom_adj 2>/dev/null"
            )
        }.getOrDefault("")
        if (out.isBlank()) return null

        val sections = HashMap<String, String>()
        var cur: String? = null
        val sb = StringBuilder()
        out.lineSequence().forEach { line ->
            if (line.startsWith(SEP)) {
                cur?.let { sections[it] = sb.toString().trim() }
                cur = line.removePrefix(SEP).trim()
                sb.setLength(0)
            } else if (cur != null) {
                sb.append(line).append('\n')
            }
        }
        cur?.let { sections[it] = sb.toString().trim() }

        val status = sections["status"].orEmpty()
        if (status.isBlank()) return null

        fun sv(key: String): String {
            val v = status.lineSequence()
                .firstOrNull { it.startsWith("$key:") }
                ?.substringAfter(':')?.trim()
            return if (v.isNullOrBlank()) "--" else v
        }

        // "123456 kB" → "120 MB"; 解析失败 "--"
        fun kbToMb(raw: String): String {
            val kb = raw.split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: return "--"
            return if (kb >= 1024) "${kb / 1024} MB" else "$kb KB"
        }

        fun sec(name: String): String = sections[name]?.takeIf { it.isNotBlank() } ?: "--"

        val vmRss = sv("VmRSS")
        val vmSwap = sv("VmSwap")
        return ProcDetail(
            pid = pid,
            ppid = sv("PPid"),
            uid = sv("Uid").split(Regex("\\s+")).firstOrNull() ?: "--",
            state = sv("State"),
            command = sv("Name"),
            cmdline = sec("cmdline").let { if (it == "--") "--" else it.trim() },
            oomAdj = sec("oom_adj"),
            oomScoreAdj = sec("oom_score_adj"),
            cpuset = sec("cpuset"),
            cgroup = sec("cgroup"),
            cpusAllowed = sv("Cpus_allowed_list"),
            resMb = if (vmRss == "--") "--" else kbToMb(vmRss),
            swapMb = if (vmSwap == "--") "--" else kbToMb(vmSwap),
        )
    }

    /** 结束进程(SIGKILL)。 */
    fun kill(pid: Int): Boolean =
        runCatching { RootShell.execSilent("kill -9 $pid") }.getOrDefault(false)

    /** 强制停止应用(等价系统设置的「强行停止」)。 */
    fun forceStop(pkg: String): Boolean {
        if (pkg.isBlank() ||
            !pkg.all { it.isLetterOrDigit() || it == '.' || it == '_' }) return false
        return runCatching { RootShell.execSilent("am force-stop $pkg") }.getOrDefault(false)
    }

    // ── 应用名/图标映射(进程名主段 = 包名 尝试解析; 结果进程内缓存, null 也缓存避免反复查)──
    private val labelCache = HashMap<String, String?>()

    /** 进程名 → 应用名; 主段不含 '.' 或包不存在返回 null(调用方显进程名或"未知")。 */
    fun appLabel(ctx: Context, procName: String): String? {
        val pkg = mainPkg(procName)
        if (!pkg.contains('.')) return null
        synchronized(labelCache) {
            if (labelCache.containsKey(pkg)) return labelCache[pkg]
        }
        val label = runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
        synchronized(labelCache) { labelCache[pkg] = label }
        return label
    }

    /** 进程名 → 应用图标; 取不到返回 null(调用方用占位图标)。 */
    fun appIcon(ctx: Context, procName: String): Drawable? {
        val pkg = mainPkg(procName)
        if (!pkg.contains('.')) return null
        return runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull()
    }
}
