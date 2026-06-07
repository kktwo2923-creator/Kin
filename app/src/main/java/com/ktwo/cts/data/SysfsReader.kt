package com.ktwo.cts.data

import com.ktwo.cts.root.RootShell
import java.io.File
import kotlin.math.abs

/**
 * 直接读取 sysfs 的实时信息(频率 / 调速器 / 可用档位 / 功耗)。
 *
 * 设计要点(优化方案 §3):
 *  - 免 root 优先:cpufreq 的 scaling_cur_freq / scaling_governor /
 *    scaling_available_* 以及 power_supply 的 current_now / voltage_now 在绝大多数
 *    设备上是 0444 全局可读,普通 App 直接 File 读即可。读不到再走 root 兜底。
 *  - 不硬编码 policy 编号:discoverClusters() 动态枚举
 *    /sys/devices/system/cpu/cpufreq/policy* 并按 related_cpus 推断大小簇。
 *  - 功耗三重探测:单位(µA/mA)、符号(充放电正负)、双电芯倍率,全部运行时判定,
 *    绝不硬编码(见 computeWatt)。
 */
object SysfsReader {

    private const val CPUFREQ = "/sys/devices/system/cpu/cpufreq"
    private const val BATT = "/sys/class/power_supply/battery"

    // ---------- 通用读取:先普通读,失败再 root ----------

    private fun readFileOrRoot(path: String): String? {
        // 1) 普通读(免 root,sysfs 多为 0444)
        runCatching {
            val f = File(path)
            if (f.canRead()) {
                val s = f.readText().trim()
                if (s.isNotEmpty()) return s
            }
        }
        // 2) root 兜底
        if (RootShell.isRooted()) {
            val s = runCatching { RootShell.exec("cat '$path' 2>/dev/null").trim() }.getOrNull()
            if (!s.isNullOrEmpty()) return s
        }
        return null
    }

    private fun listDirOrRoot(path: String): List<String> {
        runCatching {
            val d = File(path)
            if (d.canRead()) {
                d.listFiles()?.let { return it.map { f -> f.name } }
            }
        }
        if (RootShell.isRooted()) {
            return runCatching {
                RootShell.exec("ls -1 '$path' 2>/dev/null").lines().map { it.trim() }.filter { it.isNotEmpty() }
            }.getOrDefault(emptyList())
        }
        return emptyList()
    }

    // ---------- 簇发现 ----------

    /** 动态枚举所有 cpufreq policy,返回 policy 编号升序 */
    fun discoverPolicies(): List<Int> =
        listDirOrRoot(CPUFREQ)
            .filter { it.startsWith("policy") }
            .mapNotNull { it.removePrefix("policy").toIntOrNull() }
            .sorted()

    /** 读取某 policy 的 related_cpus(该簇物理上含哪些核) */
    fun relatedCpus(policy: Int): List<Int> {
        val raw = readFileOrRoot("$CPUFREQ/policy$policy/related_cpus") ?: return emptyList()
        return raw.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
    }

    // ---------- 实时频率 / 调速器 ----------

    fun curFreqKhz(policy: Int): Long =
        readFileOrRoot("$CPUFREQ/policy$policy/scaling_cur_freq")?.toLongOrNull() ?: -1

    fun curFreqMhz(policy: Int): Int {
        val k = curFreqKhz(policy)
        return if (k > 0) (k / 1000).toInt() else -1
    }

    fun governor(policy: Int): String =
        readFileOrRoot("$CPUFREQ/policy$policy/scaling_governor") ?: ""

    /** 可用频率档(kHz 升序);某些 governor 下该节点不存在,则回退 min/max 两端 */
    fun availableFreqsKhz(policy: Int): List<Long> {
        val raw = readFileOrRoot("$CPUFREQ/policy$policy/scaling_available_frequencies")
        if (!raw.isNullOrBlank()) {
            val list = raw.split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }.sorted()
            if (list.isNotEmpty()) return list
        }
        val mn = readFileOrRoot("$CPUFREQ/policy$policy/cpuinfo_min_freq")?.toLongOrNull()
        val mx = readFileOrRoot("$CPUFREQ/policy$policy/cpuinfo_max_freq")?.toLongOrNull()
        return listOfNotNull(mn, mx).distinct().sorted()
    }

    fun availableGovernors(policy: Int): List<String> {
        val raw = readFileOrRoot("$CPUFREQ/policy$policy/scaling_available_governors") ?: return emptyList()
        return raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    /** 单核在线状态 */
    fun coreOnline(cpu: Int): Boolean =
        // cpu0 通常没有 online 节点(恒在线),读不到当作在线
        readFileOrRoot("/sys/devices/system/cpu/cpu$cpu/online")?.trim() != "0"

    // ---------- 功耗(三重探测) ----------

    private fun rawCurrent(): Long? = readFileOrRoot("$BATT/current_now")?.toLongOrNull()
    private fun rawVoltage(): Long? = readFileOrRoot("$BATT/voltage_now")?.toLongOrNull()
    private fun battStatus(): String = readFileOrRoot("$BATT/status") ?: ""
    fun battPct(): Int = readFileOrRoot("$BATT/capacity")?.toIntOrNull() ?: -1
    fun isCharging(): Boolean = battStatus().equals("Charging", true) || battStatus().equals("Full", true)

    /**
     * 计算整机功耗(W)。
     * @param cellCount 电芯数(1 单芯 / 2 双芯串联)。来自设置项,默认见 PrefsCpu。
     *
     * 三重处理:
     *  - 单位:current/voltage 读数绝对值 >100000 视为 µA/µV,否则 mA/mV。
     *  - 符号:一律取绝对值(高通/一加放电常为负或符号反转,不可信)。
     *  - 双芯倍率:仅当 cellCount>=2 且电压读数是"单芯量级"(<6V)时才 ×2;
     *    若 voltage_now 已是整包电压(≥6V,如 8V)说明已含,绝不再 ×2(否则翻倍错)。
     */
    fun computeWatt(cellCount: Int): Double {
        val i = rawCurrent() ?: return 0.0
        val v = rawVoltage() ?: return 0.0
        val absI = abs(i)
        val absV = abs(v)
        val currentMa = if (absI > 100_000) absI / 1000.0 else absI.toDouble()
        val voltageMv = if (absV > 100_000) absV / 1000.0 else absV.toDouble()
        val packMultiplier = if (cellCount >= 2 && voltageMv < 6000.0) 2.0 else 1.0
        return currentMa * voltageMv / 1_000_000.0 * packMultiplier
    }

    /** 读一份完整实时状态(App 直读路径,不依赖 daemon 的 status.json) */
    fun snapshot(cellCount: Int): LiveStatus {
        val policies = discoverPolicies()
        val freq = LinkedHashMap<Int, Int>()
        val gov = LinkedHashMap<Int, String>()
        policies.forEach { p ->
            freq[p] = curFreqMhz(p)
            gov[p] = governor(p)
        }
        return LiveStatus(
            watt = computeWatt(cellCount),
            charging = isCharging(),
            battPct = battPct(),
            gpuMhz = readGpuMhz(),
            clusterFreqMhz = freq,
            clusterGov = gov,
            ts = System.currentTimeMillis(),
        )
    }

    // ---------- GPU 当前频率(多路径兜底) ----------

    private val GPU_CUR_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpuclk",                         // 部分高通,Hz
        "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",               // devfreq,Hz
        "/sys/kernel/gpu/gpu_clock",                               // 部分 ROM,MHz
    )

    fun readGpuMhz(): Int {
        for (p in GPU_CUR_PATHS) {
            val raw = readFileOrRoot(p)?.toLongOrNull() ?: continue
            // gpu_clock 多为 MHz;kgsl 多为 Hz。用量级判断。
            return when {
                raw > 10_000_000 -> (raw / 1_000_000).toInt()   // Hz -> MHz
                raw > 10_000     -> (raw / 1000).toInt()         // kHz -> MHz(少见)
                else             -> raw.toInt()                  // 已是 MHz
            }
        }
        return -1
    }

    // GPU 可用频率表(多路径兜底);返回 MHz 升序、去重
    private val GPU_AVAIL_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/freq_table_mhz",                 // 直接 MHz
        "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies",  // Hz
        "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies",      // Hz
        "/sys/class/devfreq/gpufreq/available_frequencies",        // 部分 Mali, Hz
    )

    fun availableGpuFreqsMhz(): List<Int> {
        for (p in GPU_AVAIL_PATHS) {
            val raw = readFileOrRoot(p) ?: continue
            val nums = raw.split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }.filter { it > 0 }
            if (nums.isEmpty()) continue
            val mhz = if (p.endsWith("freq_table_mhz")) {
                nums.map { it.toInt() }
            } else {
                nums.map { if (it > 100_000) (it / 1_000_000).toInt() else (it / 1000).toInt() }
            }
            val list = mhz.filter { it in 1..3000 }.distinct().sorted()
            if (list.isNotEmpty()) return list
        }
        return emptyList()
    }
}
