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

    // affinity/core 带默认值("", -1), 老调用处零改动:
    //  - affinity: /proc/[tid]/status 的 Cpus_allowed_list(如 "0-7"), 取不到为 ""
    //  - core: /proc/[tid]/stat 第 39 字段 processor(当前所在核), 取不到为 -1
    data class Thr(
        val tid: Int,
        val name: String,
        val cpuPct: Float,
        val state: Char,
        val affinity: String = "",
        val core: Int = -1,
    )

    @Volatile private var topUsable = true

    // ── /proc 差分缓存(回退用)──
    private val diffLock = Any()
    private var lastTotalJiffies = -1L
    private val lastProcJiffies = HashMap<Int, Long>()

    fun reset() {
        synchronized(diffLock) {
            lastTotalJiffies = -1L
            lastProcJiffies.clear()
        }
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

    // 进程窗: 列出 CPU 占用最高的进程(系统进程 + 应用都在)。系统/内核/HAL 等无对应应用的进程,
    // 在悬浮窗里用企鹅(Tux)图标标示; 应用用各自图标。
    fun topProcesses(limit: Int = 48): List<Proc> {
        if (!RootShell.isRooted()) return emptyList()
        if (topUsable) {
            // 覆盖全部进程(不再只取前 40): 否则前 40 名多被系统/原生进程占满, 过滤后应用寥寥, 显示不全
            val out = runCatching {
                RootShell.exec("top -b -n 1 -o PID,%CPU,RES,CMDLINE -s 2 2>/dev/null | head -n 400")
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

    // 线程监视(对齐 Met 的单进程线程表): 锁定【前台 App 自身进程】(pid 由无障碍拿到前台包名后
    // 解析, 固定不变), 列出该进程内部的所有 TID 子线程, 按 CPU% 排序取前 N。
    // 用 top -H -p <pid> 直采该进程线程; 再按 PID==pid 二次过滤(不依赖各 ROM 的 -p 支持是否精确),
    // 保证只出这一个进程的线程, 不混入系统进程。
    fun topThreads(limit: Int = 24, pid: Int = -1): List<Thr> {
        if (!RootShell.isRooted() || pid <= 0) return emptyList()
        val out = runCatching {
            RootShell.exec("top -b -H -p $pid -n 1 -o PID,TID,%CPU,S,CMDLINE -s 3 2>/dev/null | head -n 400")
        }.getOrDefault("")
        val rows = ArrayList<Thr>()
        out.lineSequence().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 5)
            if (parts.size < 5) return@forEach
            val p = parts[0].toIntOrNull() ?: return@forEach
            if (p != pid) return@forEach                      // 只保留该前台进程的线程
            val tid = parts[1].toIntOrNull() ?: return@forEach
            val cpu = parts[2].toFloatOrNull() ?: return@forEach
            val st = parts[3].firstOrNull() ?: 'S'
            rows.add(Thr(tid, baseName(parts[4]), cpu, st))
        }
        val top = rows.sortedByDescending { it.cpuPct }.take(limit)
        return enrichThreads(top)
    }

    /**
     * 给 TOP 线程补齐 亲和(Cpus_allowed_list)与当前核(stat processor 字段)。
     * 单条 root shell 批量读 /proc/[tid]/stat 与 /proc/[tid]/status(线程目录可直接寻址);
     * 任一失败保持默认("", -1), 不影响主列表。
     */
    private fun enrichThreads(rows: List<Thr>): List<Thr> {
        if (rows.isEmpty()) return rows
        return runCatching {
            val cmd = StringBuilder()
            for (r in rows) {
                cmd.append("echo \"@I ${r.tid}\"; ")
                cmd.append("echo \"@S \$(cat /proc/${r.tid}/stat 2>/dev/null)\"; ")
                cmd.append("echo \"@A \$(grep Cpus_allowed_list /proc/${r.tid}/status 2>/dev/null)\"; ")
            }
            val out = RootShell.execTimed(5, cmd.toString())
            val cores = HashMap<Int, Int>()
            val affs = HashMap<Int, String>()
            val comms = HashMap<Int, String>()   // 每线程自身的 comm(Met COMM 列显示它, 非进程名)
            var cur = -1
            for (raw in out.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith("@I ") ->
                        cur = line.removePrefix("@I ").trim().toIntOrNull() ?: -1
                    line.startsWith("@S ") && cur > 0 -> {
                        val body = line.removePrefix("@S ")
                        // 线程名 = stat 第 2 字段(第一个 '(' 与最后一个 ')' 之间, 可含空格)
                        val lp = body.indexOf('('); val rp = body.lastIndexOf(')')
                        if (lp in 0 until rp) comms[cur] = body.substring(lp + 1, rp).take(20)
                        // comm 后 0-based 36=processor
                        val tail = body.substringAfterLast(')', "").trim()
                        val f = tail.split(Regex("\\s+"))
                        f.getOrNull(36)?.toIntOrNull()?.let { if (it >= 0) cores[cur] = it }
                    }
                    line.startsWith("@A ") && cur > 0 -> {
                        // "Cpus_allowed_list:\t0-7" → "0-7"
                        val a = line.removePrefix("@A ").substringAfter(':', "").trim()
                        if (a.isNotEmpty()) affs[cur] = a.take(15)
                    }
                }
            }
            rows.map {
                it.copy(
                    name = comms[it.tid] ?: it.name,   // 用线程 comm 覆盖进程名(对齐 Met COMM 列)
                    affinity = affs[it.tid] ?: "",
                    core = cores[it.tid] ?: -1,
                )
            }
        }.getOrDefault(rows)
    }

    // ── /proc 差分回退(仅进程)──
    private fun totalJiffies(): Long {
        val line = runCatching { RootShell.execTimed(5, "cat /proc/stat 2>/dev/null | head -n 1") }.getOrDefault("")
        if (!line.startsWith("cpu ")) return -1
        return line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }.sum()
    }

    private fun procFallback(limit: Int): List<Proc> {
        val ncpu = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        // 一次性 dump 所有 pid 的 stat + comm
        val dump = runCatching {
            RootShell.execTimed(5, "for p in /proc/[0-9]*; do echo \"@@\$(basename \$p) \$(cat \$p/stat 2>/dev/null)\"; done")
        }.getOrDefault("")
        val total = totalJiffies()
        // 差分基线读写均加锁, 防并发采样错乱
        val prevTotal: Long
        synchronized(diffLock) {
            prevTotal = lastTotalJiffies
            lastTotalJiffies = total
        }
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
        // 快照上一轮进程基线, 循环内不再持锁
        val prevProc = synchronized(diffLock) { HashMap(lastProcJiffies) }
        val result = ArrayList<Proc>()
        if (prevTotal > 0 && total > prevTotal) {
            val dTotal = (total - prevTotal).toDouble()
            for (r in rows) {
                val prev = prevProc[r.pid] ?: continue
                val dj = (r.j - prev).toDouble()
                if (dj <= 0) continue
                val pct = (dj / dTotal * 100.0 * ncpu).toFloat().coerceIn(0f, 100f * ncpu)
                if (pct > 0.1f) result.add(Proc(r.pid, r.name, pct, -1))
            }
        }
        synchronized(diffLock) {
            lastProcJiffies.clear()
            lastProcJiffies.putAll(curJiffies)
        }
        return result.sortedByDescending { it.cpuPct }.take(limit)
    }
}
