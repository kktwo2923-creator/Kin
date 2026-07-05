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

    /** 已知会自行接管线程/调度的第三方框架进程: 进程名(pidof 精确匹配) -> 展示名。 */
    private val CONFLICT_PROCS = linkedMapOf(
        "oiface" to "oiface(厂商游戏加速)",
        "uperf" to "uperf",
        "migt" to "migt(MI 调度)",
        "fpsgo" to "fpsgo",
    )
    /** 需要模糊匹配命令行的框架(App 形态): 匹配串 -> 展示名。 */
    private val CONFLICT_CMDLINES = linkedMapOf(
        "com.omarea.vtools" to "Scene",
        "com.omarea.gamepad" to "Scene 游戏助手",
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
        val listing = RootShell.exec(
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

    /**
     * 检测可能与线程分配打架的调度/加速框架, 返回展示名列表(空 = 未发现)。
     * 只读检测, 不做任何处置。
     */
    fun conflicts(): List<String> = runCatching {
        val found = ArrayList<String>()
        CONFLICT_PROCS.forEach { (proc, label) ->
            val out = runCatching { RootShell.exec("pidof '$proc' 2>/dev/null").trim() }.getOrDefault("")
            if (out.isNotEmpty()) found += label
        }
        CONFLICT_CMDLINES.forEach { (needle, label) ->
            val out = runCatching { RootShell.exec("pgrep -f '$needle' 2>/dev/null | head -1").trim() }.getOrDefault("")
            if (out.isNotEmpty()) found += label
        }
        found.distinct()
    }.getOrDefault(emptyList())
}
