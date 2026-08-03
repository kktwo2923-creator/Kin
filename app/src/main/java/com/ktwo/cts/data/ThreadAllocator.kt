// 线程分配(M5)的实际执行层: 找大核簇 -> 找目标进程的渲染类线程 -> taskset 绑核。
// 设计原则(首版保守):
//  1. 全部 root 命令走 RootShell(内部已 runCatching, 永不抛出), 这里再包一层 runCatching 兜底;
//  2. 只做"应用一次"式绑核, 不做常驻守护、不写 walt/fpsgo/cpuctl 等高风险调度节点;
//  3. 实时调度/权重调整 只存偏好(见 ThreadAllocCfg), 待后续里程碑再落地。 TODO(M6+)
package com.ktwo.cts.data

import com.ktwo.cts.root.RootShell

object ThreadAllocator {

    /** 常见渲染/游戏引擎线程名(不区分大小写)。匹配不到时退回主线程。 */
    private val RENDER_THREAD_REGEX = Regex(
        "renderthread|unitymain|unitygfx|glthread|gl thread|mali-|vulkan|swappy|rhithread|frameworker",
        RegexOption.IGNORE_CASE,
    )

    /** 包名只允许安全字符, 防止拼进 shell 出问题。 */
    private fun safePkg(pkg: String): Boolean =
        pkg.isNotBlank() && pkg.all { it.isLetterOrDigit() || it == '.' || it == '_' }

    /**
     * 大核掩码: 取 cpuinfo_max_freq 最高的 cpufreq policy 的 related_cpus,
     * 组成位掩码(bit i = cpu i)。取不到(无 root/异常拓扑)返回 0。
     */
    fun bigCoreMask(): Int = runCatching {
        val policies = SysfsReader.discoverPolicies()
        if (policies.isEmpty()) return@runCatching 0
        val best = policies.maxByOrNull { SysfsReader.minMaxFreqMhz(it).second } ?: return@runCatching 0
        if (SysfsReader.minMaxFreqMhz(best).second <= 0) return@runCatching 0
        var mask = 0
        SysfsReader.relatedCpus(best).forEach { cpu ->
            if (cpu in 0..30) mask = mask or (1 shl cpu)
        }
        mask
    }.getOrDefault(0)

    /** 大核簇的 CPU 编号列表(展示用), 取不到返回空。 */
    fun bigCoreCpus(): List<Int> = runCatching {
        val policies = SysfsReader.discoverPolicies()
        val best = policies.maxByOrNull { SysfsReader.minMaxFreqMhz(it).second } ?: return@runCatching emptyList()
        SysfsReader.relatedCpus(best)
    }.getOrDefault(emptyList())

    /** 找包名对应的主进程 pid; 找不到返回 null。 */
    private fun findPid(pkg: String): Int? = runCatching {
        if (!safePkg(pkg)) return@runCatching null
        RootShell.exec("pidof '$pkg' 2>/dev/null").trim()
            .split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
            ?: RootShell.exec("pgrep -f '^$pkg' 2>/dev/null | head -1").trim().toIntOrNull()
    }.getOrNull()

    /** 公开入口: 找包名主进程 pid(供「自动识别」读取其实时线程)。未运行/无 root 返回 null。 */
    fun pidOf(pkg: String): Int? = findPid(pkg)

    /**
     * 对 pkg 的渲染类线程执行绑核(taskset -p <hex掩码> <tid>), 返回成功处理的线程数。
     * 匹配不到渲染线程时, 退回只绑主线程。任何一步失败都静默降级, 最终返回 0。
     */
    fun applyAffinity(pkg: String): Int = runCatching {
        if (!safePkg(pkg)) return@runCatching 0
        val mask = bigCoreMask()
        if (mask == 0) return@runCatching 0
        val pid = findPid(pkg) ?: return@runCatching 0

        // 一条命令列出 "tid 线程名", 减少 shell 往返
        // execTimed(P1-l): /proc 遍历在高负载时可能挂起, 加 5s 超时保护
        val listing = RootShell.execTimed(5,
            "for t in /proc/$pid/task/*; do " +
            "echo \"\${t##*/} \$(cat \$t/comm 2>/dev/null)\"; done"
        )
        val renderTids = listing.lines().mapNotNull { line ->
            val sp = line.trim().split(' ', limit = 2)
            val tid = sp.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val name = sp.getOrNull(1) ?: return@mapNotNull null
            if (RENDER_THREAD_REGEX.containsMatchIn(name)) tid else null
        }
        val targets = renderTids.ifEmpty { listOf(pid) }
        val hex = Integer.toHexString(mask)
        var ok = 0
        targets.forEach { tid ->
            if (runCatching { RootShell.execSilent("taskset -p $hex $tid") }.getOrDefault(false)) ok++
        }
        ok
    }.getOrDefault(0)

    // ── 手动分配: 列出目标进程全部线程 + 给指定 tid 绑核(供线程规则管理「实时线程」页使用) ──

    /** 手动分配单次列出的线程数上限, 防异常进程线程数爆炸拖垮 UI。 */
    private const val MAX_THREADS = 200

    /**
     * 单条线程信息(手动分配列表用)。
     * core = 当前运行核(stat 1-based 第 39 字段 processor, 取不到为 -1);
     * affinity = status 的 Cpus_allowed_list 原文(如 "0-7"、"0-3,7", 取不到为空串)。
     */
    data class ThreadInfo(
        val tid: Int,
        val name: String,
        val core: Int,
        val affinity: String,
    )

    /**
     * 列出 pid 的全部线程: 一条 root shell 遍历 /proc/[pid]/task 下各 tid,
     * 每条线程分 4 行输出("@I tid"、"@C comm"、"@S stat"、"@A Cpus_allowed_list"),
     * 分行是为了避免线程名里的空格/括号污染分隔(与 ThreadSampler 同一套读法)。
     * 无 root / 进程不存在 / 解析失败一律静默返回空列表。按 tid 升序, 上限 MAX_THREADS 条。
     */
    fun listThreads(pid: Int): List<ThreadInfo> = runCatching {
        if (pid <= 0 || !RootShell.isRooted()) return@runCatching emptyList()
        val cmd = "for t in /proc/$pid/task/[0-9]*; do " +
            "echo \"@I \$(basename \$t)\"; " +
            "echo \"@C \$(cat \$t/comm 2>/dev/null)\"; " +
            "echo \"@S \$(cat \$t/stat 2>/dev/null)\"; " +
            "echo \"@A \$(grep Cpus_allowed_list \$t/status 2>/dev/null)\"; " +
            "done"
        // execTimed(P1-l): 线程数多的进程遍历 /proc 可能耗时, 加 5s 超时保护
        val out = RootShell.execTimed(5, cmd)
        if (out.isBlank()) return@runCatching emptyList()

        val result = ArrayList<ThreadInfo>()
        var tid = -1
        var name = ""
        var core = -1
        var affinity = ""
        fun flush() {
            if (tid > 0) result.add(ThreadInfo(tid, name.ifEmpty { "$tid" }, core, affinity))
            tid = -1; name = ""; core = -1; affinity = ""
        }
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("@I ") -> {
                    flush()
                    tid = line.removePrefix("@I ").trim().toIntOrNull() ?: -1
                }
                line.startsWith("@C ") -> {
                    val n = line.removePrefix("@C ").trim()
                    if (n.isNotEmpty()) name = n.take(32)
                }
                line.startsWith("@S ") -> {
                    // stat 的 comm 在括号里(可含空格/括号), 取最后一个 ')' 之后的字段;
                    // 去掉 pid 与 comm 后 0-based 第 36 个即 processor(1-based 第 39 字段)。
                    val tail = line.removePrefix("@S ").substringAfterLast(')', "").trim()
                    if (tail.isNotEmpty()) {
                        core = tail.split(Regex("\\s+")).getOrNull(36)?.toIntOrNull() ?: -1
                    }
                }
                line.startsWith("@A ") -> {
                    // "Cpus_allowed_list:\t0-7" → "0-7"
                    affinity = line.removePrefix("@A ").substringAfter(':', "").trim()
                }
            }
        }
        flush()
        result.sortedBy { it.tid }.take(MAX_THREADS)
    }.getOrDefault(emptyList())

    /**
     * CPU 簇分组(簇名 → 核编号列表), 供手动分配 UI 按 小核/中核/大核 分组展示复选框。
     * 展示顺序按 policy 升序(即 CPU 编号顺序); 命名按 cpuinfo_max_freq 从低到高的名次
     * (与 MetricReader.clusterLabel 同一套口径)。sysfs 读不到时退回单组「全部核心」。
     */
    fun coreClusters(): List<Pair<String, List<Int>>> = runCatching {
        val policies = SysfsReader.discoverPolicies()
        val groups = policies.mapNotNull { p ->
            val cpus = SysfsReader.relatedCpus(p).filter { it in 0..30 }.sorted()
            if (cpus.isEmpty()) null
            else Triple(p, cpus, SysfsReader.minMaxFreqMhz(p).second)
        }
        if (groups.isEmpty()) {
            val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            return@runCatching listOf("全部核心" to (0 until n).toList())
        }
        // 名次表: 按最大频率升序排出每个 policy 的名次, 命名用; 展示顺序仍按 CPU 编号。
        val rank = groups.sortedBy { it.third }
            .mapIndexed { i, g -> g.first to i }.toMap()
        groups.map { g -> clusterName(rank[g.first] ?: 0, groups.size) to g.second }
    }.getOrDefault(emptyList())

    /** 簇命名: index = 按频率升序的名次(0 最低)。与 MetricReader.clusterLabel 同思路。 */
    private fun clusterName(index: Int, count: Int): String = when {
        count <= 1 -> "全部核心"
        count == 2 -> if (index == 0) "小核" else "大核"
        count == 3 -> listOf("小核", "中核", "大核").getOrElse(index) { "簇$index" }
        else -> when (index) {
            0 -> "小核"
            count - 1 -> "超大核"
            count - 2 -> "大核"
            else -> "中核"
        }
    }

    /** 解析 Cpus_allowed_list 风格的核列表(如 "0-3,7") → 升序核编号; 非法输入返回空。 */
    fun parseCpuList(s: String): List<Int> = runCatching {
        val out = sortedSetOf<Int>()
        s.split(',').forEach { seg ->
            val t = seg.trim()
            if (t.isEmpty()) return@forEach
            val dash = t.indexOf('-')
            if (dash > 0) {
                val a = t.substring(0, dash).trim().toIntOrNull()
                val b = t.substring(dash + 1).trim().toIntOrNull()
                if (a != null && b != null && a in 0..127 && b in a..127) out.addAll(a..b)
            } else {
                t.toIntOrNull()?.let { if (it in 0..127) out.add(it) }
            }
        }
        out.toList()
    }.getOrDefault(emptyList())

    /**
     * 把指定 tid 绑到 mask 对应的核心集合: taskset -p <十六进制掩码> <tid>。
     * tid<=0 或 mask==0 直接拒绝; 无 root / 命令失败返回 false, 永不抛出。
     */
    fun applyAffinityToTid(tid: Int, mask: Int): Boolean = runCatching {
        if (tid <= 0 || mask == 0) return@runCatching false
        RootShell.execSilent("taskset -p ${Integer.toHexString(mask)} $tid")
    }.getOrDefault(false)

}
