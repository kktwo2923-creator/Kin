package com.ktwo.cts.data

import com.ktwo.cts.root.RootShell

/**
 * 进程 / 线程实时采样(供进程/线程监视悬浮窗)。需 Root。仅在后台线程调用。
 *  - 主路径: toybox `top -b -n 1`(一次 exec, 自带 %CPU)。
 *  - 回退: /proc/[pid]/stat 差分(top 不可用时)。
 * 无 root 一律返回空表(窗内提示「需要 Root」)。
 */
object ProcSampler {

    data class Proc(val pid: Int, val name: String, val cpuPct: Float, val rssMb: Int)
    data class Thr(val tid: Int, val name: String, val cpuPct: Float, val state: Char)

    @Volatile private var topUsable = true

    // ── /proc 差分缓存(回退用)──
    private var lastTotalJiffies = -1L
    private val lastProcJiffies = HashMap<Int, Long>()

    fun reset() {
        lastTotalJiffies = -1L
        lastProcJiffies.clear()
    }

    private fun baseName(cmd: String): String {
        val first = cmd.trim().substringBefore(' ')
        val b = first.substringAfterLast('/').ifBlank { cmd.trim() }
        return b.take(15)
    }

    private fun resToMb(res: String): Int {
        val r = res.trim()
        if (r.isEmpty()) return -1
        val num = r.dropLast(1).toFloatOrNull()
        return when (r.last()) {
            'G', 'g' -> ((num ?: return -1) * 1024).toInt()
            'M', 'm' -> (num ?: return -1).toInt()
            'K', 'k' -> ((num ?: return -1) / 1024).toInt()
            else -> r.toLongOrNull()?.let { (it / 1024).toInt() } ?: -1   // 纯数字视为 KB
        }
    }

    fun topProcesses(limit: Int = 8): List<Proc> {
        if (!RootShell.isRooted()) return emptyList()
        if (topUsable) {
            val out = runCatching {
                RootShell.exec("top -b -n 1 -o PID,%CPU,RES,CMDLINE -s 2 2>/dev/null | head -n 40")
            }.getOrDefault("")
            val rows = ArrayList<Proc>()
            out.lineSequence().forEach { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 4)
                if (parts.size < 4) return@forEach
                val pid = parts[0].toIntOrNull() ?: return@forEach
                val cpu = parts[1].toFloatOrNull() ?: return@forEach
                rows.add(Proc(pid, baseName(parts[3]), cpu, resToMb(parts[2])))
            }
            if (rows.size >= 3) {
                return rows.sortedByDescending { it.cpuPct }.take(limit)
            }
            topUsable = false   // top 输出不可用, 转 /proc 回退
        }
        return procFallback(limit)
    }

    fun topThreads(limit: Int = 8): List<Thr> {
        if (!RootShell.isRooted()) return emptyList()
        val out = runCatching {
            RootShell.exec("top -b -H -n 1 -o TID,%CPU,S,CMDLINE -s 2 2>/dev/null | head -n 40")
        }.getOrDefault("")
        val rows = ArrayList<Thr>()
        out.lineSequence().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 4)
            if (parts.size < 4) return@forEach
            val tid = parts[0].toIntOrNull() ?: return@forEach
            val cpu = parts[1].toFloatOrNull() ?: return@forEach
            val st = parts[2].firstOrNull() ?: 'S'
            rows.add(Thr(tid, baseName(parts[3]), cpu, st))
        }
        return rows.sortedByDescending { it.cpuPct }.take(limit)
    }

    // ── /proc 差分回退(仅进程)──
    private fun totalJiffies(): Long {
        val line = runCatching { RootShell.exec("cat /proc/stat 2>/dev/null | head -n 1") }.getOrDefault("")
        if (!line.startsWith("cpu ")) return -1
        return line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }.sum()
    }

    private fun procFallback(limit: Int): List<Proc> {
        val ncpu = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        // 一次性 dump 所有 pid 的 stat + comm
        val dump = runCatching {
            RootShell.exec("for p in /proc/[0-9]*; do echo \"@@\$(basename \$p) \$(cat \$p/stat 2>/dev/null)\"; done")
        }.getOrDefault("")
        val total = totalJiffies()
        val prevTotal = lastTotalJiffies
        lastTotalJiffies = total
        val curJiffies = HashMap<Int, Long>()
        data class Row(val pid: Int, val name: String, val j: Long)
        val rows = ArrayList<Row>()
        dump.lineSequence().forEach { raw ->
            if (!raw.startsWith("@@")) return@forEach
            val body = raw.removePrefix("@@")
            val pid = body.substringBefore(' ').toIntOrNull() ?: return@forEach
            val stat = body.substringAfter(' ', "")
            val name = stat.substringAfter('(', "").substringBeforeLast(')', "").ifBlank { pid.toString() }.take(15)
            val after = stat.substringAfterLast(')', "").trim().split(Regex("\\s+"))
            // stat 字段(去掉 pid 与 comm 后): index0=state ... utime=第13, stime=第14 (0-based after comm)
            val utime = after.getOrNull(11)?.toLongOrNull() ?: return@forEach
            val stime = after.getOrNull(12)?.toLongOrNull() ?: return@forEach
            val j = utime + stime
            curJiffies[pid] = j
            rows.add(Row(pid, name, j))
        }
        val result = ArrayList<Proc>()
        if (prevTotal > 0 && total > prevTotal) {
            val dTotal = (total - prevTotal).toDouble()
            for (r in rows) {
                val prev = lastProcJiffies[r.pid] ?: continue
                val dj = (r.j - prev).toDouble()
                if (dj <= 0) continue
                val pct = (dj / dTotal * 100.0 * ncpu).toFloat().coerceIn(0f, 100f * ncpu)
                if (pct > 0.1f) result.add(Proc(r.pid, r.name, pct, -1))
            }
        }
        lastProcJiffies.clear()
        lastProcJiffies.putAll(curJiffies)
        return result.sortedByDescending { it.cpuPct }.take(limit)
    }
}
