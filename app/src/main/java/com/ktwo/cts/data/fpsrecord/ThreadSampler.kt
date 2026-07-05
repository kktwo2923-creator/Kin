// M3 线程负载采样器: 录制期对锁定应用的 pid, 每 2s 用一条 root shell 遍历
// /proc/<pid>/task/*/ 读 comm/stat/status, 跨采样点做 jiffies 差分得到每线程 cpu%,
// 同时记录所在核心(stat 第 39 字段 processor)与亲和(Cpus_allowed_list)变化。
// 全部读取 runCatching, 无 root / 读失败一律静默返回, 不影响帧率录制主流程。
package com.ktwo.cts.data.fpsrecord

import com.ktwo.cts.root.RootShell
import kotlin.math.roundToInt

/**
 * 有状态: 持有上次采样的 per-tid jiffies 与总 jiffies, 供差分。
 * 非线程安全, 只在录制服务的单一协程里使用(与 FpsSampler 相同约束)。
 * 用法: 录制循环每 2s 调 [sampleOnce]; 停止时调 [aggregate] 得到 TOP 线程汇总。
 */
class ThreadSampler {

    /** 每条线程的累计状态(整个会话)。 */
    private class Acc(var name: String) {
        val loadPoints = ArrayList<Float>()   // 每次差分采样一个 %(单核口径)
        val corePoints = ArrayList<Int>()     // 与 loadPoints 等长, -1=未知
        var lastAffinity: String? = null
        var affinityChanges = 0
    }

    // ── 差分缓存 ──
    private var lastTotalJiffies = -1L
    private val lastThreadJiffies = HashMap<Int, Long>()
    private val accs = HashMap<Int, Acc>()

    private val ncpu = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** 单次样本(解析一行线程数据的结果)。 */
    private data class Snap(
        val tid: Int,
        var name: String = "",
        var jiffies: Long = -1L,
        var core: Int = -1,
        var affinity: String = "",
    )

    /**
     * 采一次: 一条 root shell 遍历 /proc/<pid>/task/*, 首行附带 /proc/stat 总 jiffies。
     * 第一次调用只建立基线(不产生负载点); 之后每次差分追加 load/core 点。
     * 任何失败(无 root / 进程退出 / 输出为空)静默跳过本次。
     */
    fun sampleOnce(pid: Int) {
        if (pid <= 0) return
        runCatching {
            if (!RootShell.isRooted()) return
            // @T 总 jiffies; 每条线程 4 行: @I tid / @C comm / @S stat / @A Cpus_allowed_list。
            // comm/stat 里的括号名可含任意字符, 分行输出避免分隔符被线程名污染。
            val cmd = "echo \"@T \$(head -n 1 /proc/stat 2>/dev/null)\"; " +
                "for t in /proc/$pid/task/[0-9]*; do " +
                "echo \"@I \$(basename \$t)\"; " +
                "echo \"@C \$(cat \$t/comm 2>/dev/null)\"; " +
                "echo \"@S \$(cat \$t/stat 2>/dev/null)\"; " +
                "echo \"@A \$(grep Cpus_allowed_list \$t/status 2>/dev/null)\"; " +
                "done"
            val out = RootShell.exec(cmd)
            if (out.isBlank()) return

            var total = -1L
            val snaps = ArrayList<Snap>()
            var cur: Snap? = null
            for (raw in out.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("@T ") -> {
                        // "@T cpu  123 456 ..." → 各时间片求和
                        total = line.removePrefix("@T ").trim()
                            .split(Regex("\\s+")).drop(1)
                            .mapNotNull { it.toLongOrNull() }
                            .takeIf { it.isNotEmpty() }?.sum() ?: -1L
                    }
                    line.startsWith("@I ") -> {
                        cur?.let { if (it.jiffies >= 0) snaps.add(it) }
                        cur = line.removePrefix("@I ").trim().toIntOrNull()?.let { Snap(it) }
                    }
                    line.startsWith("@C ") -> {
                        cur?.let { c ->
                            val n = line.removePrefix("@C ").trim()
                            if (n.isNotEmpty()) c.name = n.take(24)
                        }
                    }
                    line.startsWith("@S ") -> {
                        cur?.let { c -> runCatching { parseStat(line.removePrefix("@S "), c) } }
                    }
                    line.startsWith("@A ") -> {
                        cur?.let { c ->
                            // "Cpus_allowed_list:\t0-7" → "0-7"
                            c.affinity = line.removePrefix("@A ")
                                .substringAfter(':', "").trim()
                        }
                    }
                }
            }
            cur?.let { if (it.jiffies >= 0) snaps.add(it) }
            if (snaps.isEmpty()) return

            val prevTotal = lastTotalJiffies
            lastTotalJiffies = total
            val canDiff = prevTotal > 0 && total > prevTotal
            val dTotal = (total - prevTotal).toDouble()

            for (s in snaps) {
                val acc = accs.getOrPut(s.tid) { Acc(s.name.ifEmpty { "${s.tid}" }) }
                if (s.name.isNotEmpty()) acc.name = s.name
                // 亲和变化(首次观测只记基线)
                if (s.affinity.isNotEmpty()) {
                    val last = acc.lastAffinity
                    if (last != null && last != s.affinity) acc.affinityChanges++
                    acc.lastAffinity = s.affinity
                }
                // 负载差分: 该线程 jiffies 增量 / 总 jiffies 增量 × 核数 × 100 → 单核口径 %
                val prev = lastThreadJiffies[s.tid]
                if (canDiff && prev != null && s.jiffies >= prev) {
                    val pct = ((s.jiffies - prev) / dTotal * ncpu * 100.0)
                        .toFloat().coerceIn(0f, 100f)
                    // 防止单会话内存无限增长(4h 上限 7200 点/线程, 封顶即停止追加)
                    if (acc.loadPoints.size < MAX_RAW_POINTS) {
                        acc.loadPoints.add(pct)
                        acc.corePoints.add(s.core)
                    }
                }
                lastThreadJiffies[s.tid] = s.jiffies
            }
        }
    }

    /**
     * 解析 /proc/<tid>/stat: comm 在括号里(可含空格/括号), 取最后一个 ')' 之后的字段;
     * 1-based 第 14 utime + 第 15 stime → jiffies; 第 39 processor → 当前核心。
     * 去掉 pid 与 comm 后 0-based: utime=11, stime=12, processor=36。
     */
    private fun parseStat(stat: String, out: Snap) {
        val tail = stat.substringAfterLast(')', "").trim()
        if (tail.isEmpty()) return
        val f = tail.split(Regex("\\s+"))
        val utime = f.getOrNull(11)?.toLongOrNull() ?: return
        val stime = f.getOrNull(12)?.toLongOrNull() ?: return
        out.jiffies = utime + stime
        out.core = f.getOrNull(36)?.toIntOrNull() ?: -1
        if (out.name.isEmpty()) {
            out.name = stat.substringAfter('(', "")
                .substringBeforeLast(')', "").take(24)
        }
    }

    /**
     * 会话收尾聚合: 每线程 avg/max、主核众数与占比、迁移次数、亲和变更次数;
     * 序列限长(均匀抽稀到 ≤[STORE_POINTS] 点); 按 avgLoad 降序取前 [TOP_N] 条。
     */
    fun aggregate(): List<ThreadLoad> = runCatching {
        accs.mapNotNull { (tid, acc) ->
            val loads = acc.loadPoints
            if (loads.isEmpty()) return@mapNotNull null
            val avg = loads.average().toFloat()
            val max = loads.max()
            // 主核众数(剔除 -1 未知)
            val knownCores = acc.corePoints.filter { it >= 0 }
            val dominant = knownCores.groupingBy { it }.eachCount().maxByOrNull { it.value }
            val domCore = dominant?.key ?: -1
            val domPct = if (dominant != null && knownCores.isNotEmpty())
                (dominant.value * 100.0 / knownCores.size).roundToInt() else 0
            // 迁移次数: 相邻两个已知核心不同
            var migration = 0
            var prevCore = -1
            for (c in acc.corePoints) {
                if (c < 0) continue
                if (prevCore >= 0 && c != prevCore) migration++
                prevCore = c
            }
            ThreadLoad(
                tid = tid,
                name = acc.name,
                avgLoad = if (avg.isFinite()) avg else 0f,
                maxLoad = if (max.isFinite()) max else 0f,
                dominantCore = domCore,
                dominantCorePct = domPct,
                migrationCount = migration,
                affinityChanges = acc.affinityChanges,
                loadPoints = thin(loads),
                corePoints = thinInt(acc.corePoints),
            )
        }.sortedByDescending { it.avgLoad }.take(TOP_N)
    }.getOrDefault(emptyList())

    /** 均匀抽稀到 ≤ STORE_POINTS 点(保持时间顺序)。 */
    private fun thin(src: List<Float>): List<Float> {
        if (src.size <= STORE_POINTS) return ArrayList(src)
        val step = src.size.toDouble() / STORE_POINTS
        return List(STORE_POINTS) { i -> src[(i * step).toInt().coerceIn(0, src.size - 1)] }
    }

    private fun thinInt(src: List<Int>): List<Int> {
        if (src.size <= STORE_POINTS) return ArrayList(src)
        val step = src.size.toDouble() / STORE_POINTS
        return List(STORE_POINTS) { i -> src[(i * step).toInt().coerceIn(0, src.size - 1)] }
    }

    companion object {
        private const val STORE_POINTS = 120   // 每线程落盘序列上限
        private const val TOP_N = 20           // 只保留 avgLoad 前 20 条
        private const val MAX_RAW_POINTS = 7200 // 录制期内存上限(2s×7200=4h)

        /**
         * 包名 → 主进程 pid(root pidof, 取第一个)。失败/无 root 返回 -1。
         * 包名字符白名单校验, 防 shell 注入。
         */
        fun resolvePid(pkg: String): Int {
            if (pkg.isBlank() ||
                !pkg.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == ':' }
            ) return -1
            return runCatching {
                if (!RootShell.isRooted()) return -1
                RootShell.exec("pidof $pkg 2>/dev/null").trim()
                    .split(Regex("\\s+")).firstOrNull()?.toIntOrNull() ?: -1
            }.getOrDefault(-1)
        }
    }
}
