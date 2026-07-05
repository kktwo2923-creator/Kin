package com.ktwo.cts.data

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

    data class Cluster(val name: String, val mhz: Int)

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
    )

    // ── CPU 占用率: /proc/stat 差分 ──
    @Volatile private var lastIdle = -1L
    @Volatile private var lastTotal = -1L

    private fun readProcStatCpuLoad(): Int {
        val line = runCatching {
            File("/proc/stat").bufferedReader().use { it.readLine() }
        }.getOrNull() ?: return -1
        if (!line.startsWith("cpu ")) return -1
        val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 5) return -1
        val idle = parts[3] + (parts.getOrElse(4) { 0L })      // idle + iowait
        val total = parts.sum()
        val prevIdle = lastIdle
        val prevTotal = lastTotal
        lastIdle = idle
        lastTotal = total
        if (prevIdle < 0 || total <= prevTotal) return -1
        val dTotal = (total - prevTotal).toDouble()
        val dIdle = (idle - prevIdle).toDouble()
        if (dTotal <= 0) return -1
        return (((dTotal - dIdle) / dTotal) * 100.0).toInt().coerceIn(0, 100)
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

    private fun readClusters(): List<Cluster> {
        val policies = cachedPolicies ?: SysfsReader.discoverPolicies().also { cachedPolicies = it }
        if (policies.isEmpty()) return emptyList()
        return policies.mapIndexed { i, p ->
            Cluster(clusterLabel(i, policies.size), SysfsReader.curFreqMhz(p))
        }
    }

    // ── GPU 占用率 ──
    private val GPU_BUSY_PATHS = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
    )

    private fun readGpuLoad(): Int {
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

    // ── RAM: /proc/meminfo ──
    private fun readRam(): Triple<Int, Double, Double> {
        val txt = runCatching { File("/proc/meminfo").readText() }.getOrNull() ?: return Triple(-1, 0.0, 0.0)
        fun kb(key: String): Long {
            val m = Regex("$key:\\s+(\\d+)\\s+kB").find(txt) ?: return -1
            return m.groupValues[1].toLongOrNull() ?: -1
        }
        val total = kb("MemTotal")
        val avail = kb("MemAvailable")
        if (total <= 0 || avail < 0) return Triple(-1, 0.0, 0.0)
        val usedGb = (total - avail) / 1024.0 / 1024.0
        val totalGb = total / 1024.0 / 1024.0
        val pct = (((total - avail).toDouble() / total) * 100.0).toInt().coerceIn(0, 100)
        return Triple(pct, usedGb, totalGb)
    }

    // ── FPS: 屏幕实测帧率(高通/一加常见节点) ──
    private val FPS_PATHS = listOf(
        "/sys/class/drm/sde-crtc-0/measured_fps",
        "/sys/devices/platform/soc/soc:qcom,dsi-display-primary/measured_fps",
        "/sys/kernel/oplus_display/measured_fps",
    )

    private fun readFps(): Int {
        for (p in FPS_PATHS) {
            val raw = readOrRoot(p) ?: continue
            // 可能是 "fps: 60" / "60.0" / 一串采样, 取第一个整数
            val n = Regex("(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (n in 1..480) return n
        }
        return -1
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
    fun snapshot(cellCount: Int): Snapshot {
        val (ramPct, ramUsed, ramTotal) = readRam()
        return Snapshot(
            clusters = readClusters(),
            cpuLoadPct = readProcStatCpuLoad(),
            gpuMhz = SysfsReader.readGpuMhz(),
            gpuLoadPct = readGpuLoad(),
            tempC = readMaxTemp(),
            battPct = SysfsReader.battPct(),
            watt = SysfsReader.computeWatt(cellCount),
            ramUsedPct = ramPct,
            ramUsedGb = ramUsed,
            ramTotalGb = ramTotal,
            fps = readFps(),
        )
    }
}
