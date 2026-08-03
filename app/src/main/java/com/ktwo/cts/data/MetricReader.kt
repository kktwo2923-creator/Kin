package com.ktwo.cts.data

import com.ktwo.cts.nativebridge.NativeSampler
import com.ktwo.cts.root.RootShell
import java.io.File

/**
 * 统一实时性能快照(仿 Metric 悬浮监控的数据源)。
 * 尽量走免 root 的 sysfs/proc File 读; 读不到再 root 兜底。
 *  - CPU: 每簇当前频率(MHz) + 整体占用率(/proc/stat 差分)
 *  - GPU: 当前频率(MHz) + 占用率(kgsl busy%)
 *  - 温度: 电池温度与若干 CPU/SoC 热区取最高
 *  - 电量 / 功耗(W) / RAM 占用 / 屏幕实测帧率(FPS)
 *
 * 单例持有 /proc/stat 上次采样, 用于计算 CPU 占用率的差分, 因此按固定节奏调用 snapshot()。
 */
object MetricReader {

    // cpus 为该簇覆盖的核编号(related_cpus), 读不到为空表 —— 带默认值, 老调用零改动。
    data class Cluster(val name: String, val mhz: Int, val cpus: List<Int> = emptyList())

    data class Snapshot(
        val clusters: List<Cluster>,   // 每簇: 名(小核/中核/大核) + 当前 MHz
        val cpuLoadPct: Int,           // 整体 CPU 占用 %(-1 未知)
        val gpuMhz: Int,               // GPU 当前 MHz(-1)
        val gpuLoadPct: Int,           // GPU 占用 %(-1)
        val tempC: Int,                // 最高温度 ℃(-999 未知)
        val battPct: Int,              // 电量 %(-1)
        val watt: Double,              // 整机功耗 W
        val ramUsedPct: Int,           // 内存占用 %(-1)
        val ramUsedGb: Double,         // 已用内存 GB
        val ramTotalGb: Double,        // 总内存 GB
        val fps: Int,                  // 屏幕实测帧率(-1 未知)
        // ── Metric 仪表盘追加(带默认值, 旧调用零改动)──
        val swapUsedPct: Int = -1,     // 交换分区占用 %(-1 未知/无 swap)
        val swapUsedGb: Double = 0.0,  // 已用 swap GB
        val swapTotalGb: Double = 0.0, // 总 swap GB
        val swapCachedMb: Int = -1,    // SwapCached MB(-1 未知)
        // ── 悬浮窗展开态追加(全部带默认值, 老调用零改动; 取不到即默认)──
        val coreLoadPct: List<Int> = emptyList(),  // 每核占用 %(下标=核号, -1 未知)
        val coreMhz: List<Int> = emptyList(),      // 每核当前 MHz(下标=核号, -1 未知)
        val ramCombinedPct: Int = -1,  // (物理已用+swap 已用)/物理总量 %(可 >100, -1 未知)
        val ddrMhz: Int = -1,          // DDR/内存总线当前频率 MHz(-1 未知)
        val ddrTempC: Double = -999.0, // DDR 温度 ℃(-999 未知)
        val gpuTempC: Double = -999.0, // GPU 温度 ℃(-999 未知)
        val cpuTempC: Double = -999.0, // CPU 温度 ℃(专读 cpu 热区, 非全局最高; -999 未知)
    )

    // ── CPU 占用率: /proc/stat 差分 ──
    // P2-18: CpuLoadDiffer 持上次 idle/total jiffies, 只有 total 增长才计算差分负载,
    // 否则返回上次结果(防止两次采样间隔太短 / 同一批数据被重复处理, 导致负载出现负值或 100%+)。
    private class CpuLoadDiffer {
        private var lastIdle = -1L
        private var lastTotal = -1L
        private var lastRatio = 0f   // 上次算出的负载比(0..1);total 未增长时直接返回它, 避免异常值

        /** 返回 0..1 的负载比;total 未增长(采样过短 / 重复数据)时返回上次结果, 不计算差分。 */
        @Synchronized
        fun diff(idle: Long, total: Long): Float {
            val prevIdle = lastIdle
            val prevTotal = lastTotal
            lastIdle = idle
            lastTotal = total
            // 首帧(无上次值)或 total 未增长(采样间隔太短 / 同一批数据被重复处理)→ 不计算差分, 返回上次结果
            if (prevIdle < 0 || total <= prevTotal) return lastRatio
            val dTotal = (total - prevTotal).toDouble()
            val dIdle = (idle - prevIdle).toDouble()
            if (dTotal <= 0) return lastRatio
            val ratio = ((dTotal - dIdle) / dTotal).toFloat().coerceIn(0f, 1f)
            lastRatio = ratio
            return ratio
        }
    }

    private val cpuLoadDiffer = CpuLoadDiffer()

    // Android 10+ 普通应用读全局 /proc/stat 被 SELinux 挡(只能读到自己), 故 File 读不到时用 root 兜底。
    private fun procStatCpuLine(): String? {
        runCatching {
            File("/proc/stat").bufferedReader().use { br ->
                val l = br.readLine()
                if (l != null && l.startsWith("cpu ")) return l
            }
        }
        if (RootShell.isRooted()) {
            runCatching { RootShell.exec("cat /proc/stat 2>/dev/null") }.getOrNull()
                ?.lineSequence()?.firstOrNull { it.startsWith("cpu ") }?.let { return it }
        }
        return null
    }

    private fun readProcStatCpuLoad(): Int {
        // 优先 Rust native 采样(NativeSampler 差分基线由 Rust 侧自持)。
        // 成功即直接 return, 不再跑下面的 Kotlin 差分, 避免两套 prev 状态互相污染;
        // 失败(-1: .so 缺失 / SELinux 挡 /proc/stat / 首帧)则完整走原有 Kotlin 路径。
        if (NativeSampler.available) {
            val n = runCatching { NativeSampler.cpuLoadPercent() }.getOrDefault(-1)
            if (n in 0..100) return n
        }
        val line = procStatCpuLine() ?: return -1
        if (!line.startsWith("cpu ")) return -1
        val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 5) return -1
        val idle = parts[3] + (parts.getOrElse(4) { 0L })      // idle + iowait
        val total = parts.sum()
        // P2-18: 用 CpuLoadDiffer 差分(diff 内部 @Synchronized 自锁, total 未增长时返回上次结果, 防止负值或 100%+)
        val ratio = cpuLoadDiffer.diff(idle, total)
        return (ratio * 100f).toInt().coerceIn(0, 100)
    }

    // ── 簇命名: 按核数与频率顺序给「小核/中核/大核/超大核」──
    private fun clusterLabel(index: Int, count: Int): String = when {
        count <= 1 -> "CPU"
        count == 2 -> if (index == 0) "小核" else "大核"
        count == 3 -> listOf("小核", "中核", "大核")[index]
        else -> when (index) {
            0 -> "小核"
            count - 1 -> "超大核"
            else -> "中核"
        }
    }

    @Volatile private var cachedPolicies: List<Int>? = null
    @Volatile private var cachedRelatedCpus: Map<Int, List<Int>>? = null

    private fun readClusters(): List<Cluster> {
        val policies = cachedPolicies ?: SysfsReader.discoverPolicies().also { cachedPolicies = it }
        if (policies.isEmpty()) return emptyList()
        val related = cachedRelatedCpus ?: policies.associateWith { p ->
            runCatching { SysfsReader.relatedCpus(p) }.getOrDefault(emptyList())
        }.also { cachedRelatedCpus = it }
        return policies.mapIndexed { i, p ->
            Cluster(clusterLabel(i, policies.size), SysfsReader.curFreqMhz(p), related[p] ?: emptyList())
        }
    }

    // ── 每核占用率: /proc/stat 的 cpu0..cpuN 行差分(与整体 CPU 同法, 按核各自持有一个 CpuLoadDiffer)──
    private val coreLoadDiffer = HashMap<Int, CpuLoadDiffer>()

    // 整读 /proc/stat 文本(免 root 优先, 读不到 root 兜底); null=均不可用。
    private fun procStatText(): String? {
        runCatching {
            val txt = File("/proc/stat").readText()
            if (txt.startsWith("cpu")) return txt
        }
        if (RootShell.isRooted()) {
            runCatching { RootShell.exec("cat /proc/stat 2>/dev/null") }.getOrNull()
                ?.takeIf { it.startsWith("cpu") }?.let { return it }
        }
        return null
    }

    /**
     * 每核占用 %。返回下标=核号的列表(长度=核数), 未知核为 -1;
     * 首帧(无上次值)与离线核(/proc/stat 里没有该行)均为 -1。整体不可读返回空表。
     */
    private fun readPerCoreLoads(): List<Int> {
        val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val txt = procStatText() ?: return emptyList()
        val out = MutableList(n) { -1 }
        // snapshot() 可能被多个采样方并发调用, 差分缓存加锁防 HashMap 并发写坏
        synchronized(coreLoadDiffer) {
            for (line in txt.lineSequence()) {
                if (!line.startsWith("cpu")) continue
                val id = line.substring(3).takeWhile { it.isDigit() }.toIntOrNull() ?: continue
                if (id !in 0 until n) continue
                val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
                if (parts.size < 5) continue
                val idle = parts[3] + parts[4]
                val total = parts.sum()
                // P2-18: 用 CpuLoadDiffer 差分, total 未增长时返回上次结果, 防止负值或 100%+
                val differ = coreLoadDiffer.getOrPut(id) { CpuLoadDiffer() }
                out[id] = (differ.diff(idle, total) * 100f).toInt().coerceIn(0, 100)
            }
        }
        return out
    }

    /**
     * 每核当前频率 MHz(下标=核号, -1 未知)。免 root 逐核直读 scaling_cur_freq;
     * 一个都读不到且有 root 时用单条 shell 批量兜底(避免每核一次 root exec)。
     */
    private fun readPerCoreMhz(): List<Int> {
        val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val out = MutableList(n) { -1 }
        var anyDirect = false
        for (i in 0 until n) {
            runCatching {
                val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
                if (f.canRead()) {
                    val k = f.readText().trim().toLongOrNull()
                    if (k != null && k > 0) { out[i] = (k / 1000).toInt(); anyDirect = true }
                }
            }
        }
        if (!anyDirect && RootShell.isRooted()) {
            val txt = runCatching {
                RootShell.exec(
                    "for c in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq; do " +
                        "echo \"\$c \$(cat \$c 2>/dev/null)\"; done"
                )
            }.getOrDefault("")
            val re = Regex("cpu(\\d+)/cpufreq/scaling_cur_freq\\s+(\\d+)")
            for (line in txt.lineSequence()) {
                val m = re.find(line) ?: continue
                val id = m.groupValues[1].toIntOrNull() ?: continue
                val k = m.groupValues[2].toLongOrNull() ?: continue
                if (id in 0 until n && k > 0) out[id] = (k / 1000).toInt()
            }
        }
        return out
    }

    // ── DDR 频率: 枚举 /sys/class/devfreq 里名字含 ddr/dvfsrc/bimc 的节点, 读 cur_freq ──
    @Volatile private var ddrPathResolved = false
    @Volatile private var ddrPath: String? = null

    private fun resolveDdrPath(): String? {
        if (ddrPathResolved) return ddrPath
        ddrPathResolved = true
        val names = runCatching {
            File("/sys/class/devfreq").list()?.toList() ?: emptyList()
        }.getOrDefault(emptyList()).ifEmpty {
            if (RootShell.isRooted()) runCatching {
                RootShell.exec("ls -1 /sys/class/devfreq 2>/dev/null")
                    .lines().map { it.trim() }.filter { it.isNotEmpty() }
            }.getOrDefault(emptyList()) else emptyList()
        }
        // 优先级: ddr > mtk dvfsrc(DDR 档位) > 高通 bimc/llcc/cpu-llcc > 通用 memlat/mincpubw
        for (hint in listOf("ddr", "dvfsrc", "bimc", "llcc", "memlat", "mincpubw", "cpubw", "gpubw")) {
            val hit = names.firstOrNull { it.lowercase().contains(hint) } ?: continue
            val p = "/sys/class/devfreq/$hit/cur_freq"
            if (readOrRoot(p) != null) { ddrPath = p; return p }
        }
        // 仍没命中: 取第一个能读到 cur_freq 的 devfreq 节点兜底
        for (nm in names) {
            val p = "/sys/class/devfreq/$nm/cur_freq"
            if (readOrRoot(p)?.toLongOrNull() != null) { ddrPath = p; return p }
        }
        return null
    }

    private fun readDdrMhz(): Int {
        val p = resolveDdrPath() ?: return -1
        val raw = readOrRoot(p)?.toLongOrNull() ?: return -1
        // 按量级判断单位: >10M=Hz, >10K=kHz, 否则已是 MHz
        val mhz = when {
            raw > 10_000_000 -> (raw / 1_000_000).toInt()
            raw > 10_000     -> (raw / 1000).toInt()
            else             -> raw.toInt()
        }
        return if (mhz > 0) mhz else -1
    }

    // ── 指定部件温度: thermal zone type 含关键词的取最高(路径首次枚举后缓存)──
    private val namedTempPaths = HashMap<String, List<String>>()

    private fun discoverNamedTempPaths(hint: String): List<String> {
        val zones = runCatching {
            File("/sys/class/thermal").list()?.filter { it.startsWith("thermal_zone") } ?: emptyList()
        }.getOrDefault(emptyList())
        val out = ArrayList<String>()
        for (z in zones.take(80)) {
            val type = readOrRoot("/sys/class/thermal/$z/type")?.lowercase() ?: continue
            if (type.contains(hint)) out.add("/sys/class/thermal/$z/temp")
        }
        return out
    }

    /** 读 type 含 [hint] 的热区最高温(℃); 取不到 -999.0。 */
    private fun readNamedTempC(hint: String): Double {
        val paths = synchronized(namedTempPaths) {
            namedTempPaths.getOrPut(hint) { discoverNamedTempPaths(hint) }
        }
        var best = -999.0
        for (p in paths.take(6)) {
            val raw = readOrRoot(p)?.toDoubleOrNull() ?: continue
            val c = if (kotlin.math.abs(raw) > 1000) raw / 1000.0 else raw
            if (c in 0.0..150.0) best = maxOf(best, c)
        }
        return best
    }

    // ── GPU 占用率 ──
    private val GPU_BUSY_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
    )

    private fun readGpuLoad(): Int {
        // Scene 式快路径: 守护经 socket 推来的 GPU 占用优先(3s 内), 无守护则回退直读 sysfs。
        KinDaemon.fresh()?.int("gpuload")?.let { return it.coerceIn(0, 100) }
        for (p in GPU_BUSY_PATHS) {
            val raw = readOrRoot(p) ?: continue
            // "45 %" 或 "45"
            val n = raw.trim().takeWhile { it.isDigit() }.toIntOrNull() ?: continue
            return n.coerceIn(0, 100)
        }
        return -1
    }

    // ── 温度: 电池温度 + 若干 thermal_zone 取最高(过滤离谱值) ──
    private val THERMAL_HINTS = listOf("cpu", "soc", "gpu", "tsens", "apc", "mode")
    // 首次枚举命中提示词的 thermal_zone temp 路径并缓存, 之后每帧只读这些(避免每秒枚举+读 type)
    @Volatile private var cachedTempPaths: List<String>? = null

    private fun discoverTempPaths(): List<String> {
        val zones = runCatching {
            File("/sys/class/thermal").list()?.filter { it.startsWith("thermal_zone") } ?: emptyList()
        }.getOrDefault(emptyList())
        val out = ArrayList<String>()
        for (z in zones.take(60)) {
            val type = readOrRoot("/sys/class/thermal/$z/type")?.lowercase() ?: continue
            if (THERMAL_HINTS.any { type.contains(it) }) out.add("/sys/class/thermal/$z/temp")
        }
        return out
    }

    // CPU 温度: 只取 type 含 "cpu" 的热区最高(对齐 Met #CPU 显示的 CPU 簇温度, 非全局热点最高温)
    private fun readCpuTempC(): Double {
        var c = readNamedTempC("cpu")
        // 个别 ROM 的 CPU 热区叫 apc/cluster, 兜底再取一次
        if (c <= -100) c = readNamedTempC("cluster")
        if (c <= -100) c = readNamedTempC("apc")
        return c
    }

    private fun readMaxTemp(): Int {
        var best = -999.0
        val bt = SysfsReader.batteryTempC()
        if (bt > -100 && bt < 150) best = maxOf(best, bt)
        val paths = cachedTempPaths ?: discoverTempPaths().also { cachedTempPaths = it }
        for (p in paths) {
            val raw = readOrRoot(p)?.toDoubleOrNull() ?: continue
            val c = if (kotlin.math.abs(raw) > 1000) raw / 1000.0 else raw   // milli-℃ → ℃
            if (c in 10.0..150.0) best = maxOf(best, c)
        }
        return if (best <= -100) -999 else best.toInt()
    }

    // ── RAM/SWAP: /proc/meminfo(整读一次, 物理内存与 swap 复用同一份文本)──
    private data class RamInfo(
        val pct: Int, val usedGb: Double, val totalGb: Double,
        val swapPct: Int, val swapUsedGb: Double, val swapTotalGb: Double, val swapCachedMb: Int,
        val combinedPct: Int = -1,   // (物理已用+swap 已用)/物理总量 %, 可 >100
    )

    private fun readRam(): RamInfo {
        val txt = runCatching { File("/proc/meminfo").readText() }.getOrNull()
            ?: return RamInfo(-1, 0.0, 0.0, -1, 0.0, 0.0, -1)
        fun kb(key: String): Long {
            val m = Regex("$key:\\s+(\\d+)\\s+kB").find(txt) ?: return -1
            return m.groupValues[1].toLongOrNull() ?: -1
        }
        val total = kb("MemTotal")
        val avail = kb("MemAvailable")
        var pct: Int
        val usedGb: Double
        val totalGb: Double
        if (total <= 0 || avail < 0) {
            pct = -1; usedGb = 0.0; totalGb = 0.0
        } else {
            usedGb = (total - avail) / 1024.0 / 1024.0
            totalGb = total / 1024.0 / 1024.0
            pct = (((total - avail).toDouble() / total) * 100.0).toInt().coerceIn(0, 100)
        }
        // Rust native 可用时优先用它算的内存占用 %(无差分状态, 覆盖无副作用);
        // 仅覆盖 pct 这一项, usedGb/totalGb/swap 仍用上面的 Kotlin 解析, 字段保持完整。
        if (NativeSampler.available) {
            val n = runCatching { NativeSampler.memUsedPercent() }.getOrDefault(-1)
            if (n in 0..100) pct = n
        }
        val swapTotal = kb("SwapTotal")
        val swapFree = kb("SwapFree")
        val swapCached = kb("SwapCached")
        val swapPct: Int
        val swapUsedGb: Double
        val swapTotalGb: Double
        if (swapTotal <= 0 || swapFree < 0) {
            swapPct = -1; swapUsedGb = 0.0; swapTotalGb = 0.0
        } else {
            val usedKb = (swapTotal - swapFree).coerceAtLeast(0)
            swapUsedGb = usedKb / 1024.0 / 1024.0
            swapTotalGb = swapTotal / 1024.0 / 1024.0
            swapPct = ((usedKb.toDouble() / swapTotal) * 100.0).toInt().coerceIn(0, 100)
        }
        val swapCachedMb = if (swapCached >= 0) (swapCached / 1024).toInt() else -1
        // 合计口径(仿 Metric "#RAM 81% 131%" 的第二个数): (物理已用+swap 已用)/物理总量
        val combinedPct = if (total > 0 && avail >= 0) {
            val usedKb = (total - avail).coerceAtLeast(0)
            val swapUsedKb = if (swapTotal > 0 && swapFree >= 0) (swapTotal - swapFree).coerceAtLeast(0) else 0L
            (((usedKb + swapUsedKb).toDouble() / total) * 100.0).toInt().coerceIn(0, 999)
        } else -1
        return RamInfo(pct, usedGb, totalGb, swapPct, swapUsedGb, swapTotalGb, swapCachedMb, combinedPct)
    }

    // ── FPS ──
    // 级联: ① 内核 measured_fps 节点(高通/一加, 路径按机型枚举发现) → ② SurfaceFlinger 翻页计数差分
    //       → ③ 屏幕刷新率兜底(UI 设置 fallbackHz)。保证有数, 不再恒 "--"。
    // fallbackHz: 由 UI(悬浮服务/大屏)写入当前屏幕刷新率, 取不到实测帧率时退回它。
    @Volatile var fallbackHz: Int = -1

    private val FPS_PATH_CANDIDATES = listOf(
        "/sys/class/drm/sde-crtc-0/measured_fps",
        "/sys/class/graphics/fb0/measured_fps",
        "/sys/devices/platform/soc/soc:qcom,dsi-display-primary/measured_fps",
        "/sys/kernel/oplus_display/measured_fps",
    )
    @Volatile private var fpsPathResolved = false
    @Volatile private var fpsPath: String? = null

    private fun resolveFpsPath(): String? {
        if (fpsPathResolved) return fpsPath
        fpsPathResolved = true
        for (p in FPS_PATH_CANDIDATES) if (readOrRoot(p) != null) { fpsPath = p; return p }
        // root glob 发现真实 measured_fps 节点(crtc 索引随机型不同)
        if (RootShell.isRooted()) {
            val found = runCatching {
                RootShell.exec("ls /sys/class/drm/*/measured_fps /sys/class/graphics/*/measured_fps 2>/dev/null")
            }.getOrNull()?.lineSequence()?.map { it.trim() }
                ?.firstOrNull { it.endsWith("measured_fps") && readOrRoot(it) != null }
            if (!found.isNullOrEmpty()) { fpsPath = found; return found }
        }
        return null
    }

    private fun measuredFps(): Int {
        val p = resolveFpsPath() ?: return -1
        val raw = readOrRoot(p) ?: return -1
        val n = Regex("(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return -1
        return if (n in 1..480) n else -1
    }

    // SurfaceFlinger 翻页计数(root): 两次采样差 ÷ 间隔 = 合成帧率(FrameX 等 FPS 表同款)。
    // ★ 翻页差分状态必须【每个采样链路各自持有】—— 悬浮窗刷新循环与帧率录制循环若共用一份
    //   prev(lastFlip/lastFlipNanos), 两个 ~1s 循环相位一接近, 后到的那次 dt<0.2 直接判 -1,
    //   录制就退化成假的平线(见修复)。故用 FpsFlipState 持有者, 调用方各传各的。
    class FpsFlipState {
        @Volatile var lastFlip = -1L
        @Volatile var lastFlipNanos = 0L
    }
    private val defaultFlipState = FpsFlipState()   // 悬浮窗刷新链路专用

    private fun surfaceFlingerFps(state: FpsFlipState): Int {
        if (!RootShell.isRooted()) return -1
        // 超时包裹: 与 FpsSampler 同理, SurfaceFlinger 事务卡死会拖垮共享 su 会话(悬浮窗+录制一起冻)
        val out = runCatching { RootShell.execTimed(3, "service call SurfaceFlinger 1013 2>/dev/null") }.getOrNull() ?: return -1
        // 解析 Parcel 内的 8 位十六进制词; 常见 "00000000 <count>" 或 "<count> ..."。
        val words = Regex("[0-9a-fA-F]{8}").findAll(out).map { it.value }.toList()
        if (words.isEmpty()) return -1
        val hex = if (words.size >= 2 && words[0] == "00000000") words[1] else words[0]
        val flip = hex.toLongOrNull(16) ?: return -1
        val now = System.nanoTime()
        val prev: Long; val prevT: Long
        synchronized(state) {
            prev = state.lastFlip; prevT = state.lastFlipNanos
            state.lastFlip = flip; state.lastFlipNanos = now
        }
        if (prev < 0 || prevT == 0L || flip < prev) return -1
        val dt = (now - prevT) / 1_000_000_000.0
        if (dt < 0.2) return -1
        val fps = ((flip - prev) / dt).toInt()
        return if (fps in 1..480) fps else -1
    }

    // allowFallback=false 时【不】把屏幕刷新率(fallbackHz)当作帧率返回 —— 帧率录制必须
    // 只采真实测量值, 否则自适应刷新(60/120)机型会存成一条与实际无关的假平线。
    private fun readFps(state: FpsFlipState, allowFallback: Boolean): Int {
        val m = measuredFps()
        if (m > 0) return m
        val s = surfaceFlingerFps(state)
        if (s > 0) return s
        return if (allowFallback && fallbackHz in 1..480) fallbackHz else -1
    }

    private fun readOrRoot(path: String): String? {
        runCatching {
            val f = File(path)
            if (f.canRead()) { val s = f.readText().trim(); if (s.isNotEmpty()) return s }
        }
        if (RootShell.isRooted()) {
            val s = runCatching { RootShell.exec("cat '$path' 2>/dev/null").trim() }.getOrNull()
            if (!s.isNullOrEmpty()) return s
        }
        return null
    }

    /** 采一帧。cellCount 用于功耗双芯倍率。请在后台线程调用。 */
    // fpsState: 采样链路各自的翻页差分状态(悬浮窗默认共用 defaultFlipState; 录制须传独立实例)。
    // fpsAllowFallback: false=帧率不可测时返回 -1(录制用, 不存假刷新率)。
    fun snapshot(
        cellCount: Int,
        fpsState: FpsFlipState = defaultFlipState,
        fpsAllowFallback: Boolean = true,
    ): Snapshot {
        val ram = readRam()
        return Snapshot(
            clusters = readClusters(),
            cpuLoadPct = readProcStatCpuLoad(),
            gpuMhz = SysfsReader.readGpuMhz(),
            gpuLoadPct = readGpuLoad(),
            tempC = readMaxTemp(),
            battPct = SysfsReader.battPct(),
            watt = SysfsReader.computeWatt(cellCount),
            ramUsedPct = ram.pct,
            ramUsedGb = ram.usedGb,
            ramTotalGb = ram.totalGb,
            fps = readFps(fpsState, fpsAllowFallback),
            swapUsedPct = ram.swapPct,
            swapUsedGb = ram.swapUsedGb,
            swapTotalGb = ram.swapTotalGb,
            swapCachedMb = ram.swapCachedMb,
            coreLoadPct = runCatching { readPerCoreLoads() }.getOrDefault(emptyList()),
            coreMhz = runCatching { readPerCoreMhz() }.getOrDefault(emptyList()),
            ramCombinedPct = ram.combinedPct,
            ddrMhz = runCatching { readDdrMhz() }.getOrDefault(-1),
            ddrTempC = runCatching { readNamedTempC("ddr") }.getOrDefault(-999.0),
            gpuTempC = runCatching { readNamedTempC("gpu") }.getOrDefault(-999.0),
            // CPU 温度专读 cpu 热区(而非全局最高): Met #CPU 显示的是 CPU 簇温度(~49℃), 不是热点最高温
            cpuTempC = runCatching { readCpuTempC() }.getOrDefault(-999.0),
        )
    }
}
