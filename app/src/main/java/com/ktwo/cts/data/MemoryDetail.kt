// 内存详情(M6): 读取 meminfo 全量、ZRAM 状态、VM 内核参数与 Swap IO 速率, 供内存详情页展示与调参。
package com.ktwo.cts.data

import com.ktwo.cts.root.RootShell
import java.io.File

/**
 * 内存详情读取器。
 * 数据来源(全部只读, 免 root 优先, 读不到 root 兜底):
 *  - /proc/meminfo         内存全量统计(kB)
 *  - /sys/block/zram0      ZRAM 压缩交换状态
 *  - /proc/sys/vm          VM 内核参数(swappiness 等)
 *  - /proc/vmstat          pswpin/pswpout 差分算 Swap 换入换出速率
 * 写操作(切 ZRAM 算法 / 改 VM 参数)必须 root。
 */
object MemoryDetail {

    private const val ZRAM_DIR = "/sys/block/zram0"
    private const val ZRAM_DEV = "/dev/block/zram0"
    private const val VM_DIR = "/proc/sys/vm"

    // meminfo 里关心的常用项(展示顺序即此顺序); 其余项(如 Active(anon) 细分)不展示。
    private val MEMINFO_KEYS = listOf(
        "MemTotal", "MemFree", "MemAvailable", "Buffers", "Cached", "SwapCached",
        "Active", "Inactive", "Dirty", "Writeback", "AnonPages", "Mapped", "Shmem",
        "Slab", "SReclaimable", "SUnreclaim", "KernelStack", "PageTables",
        "SwapTotal", "SwapFree",
    )

    /** 支持编辑的 VM 参数(白名单, 防止误写其它节点)。 */
    val VM_KEYS = listOf(
        "swappiness", "vfs_cache_pressure", "dirty_ratio",
        "dirty_background_ratio", "min_free_kbytes", "watermark_scale_factor",
    )

    // ── 数据结构 ──

    /** ZRAM 状态。available=false 表示本机没有 zram0 节点。 */
    data class ZramInfo(
        val available: Boolean = false,
        val algorithm: String = "",                  // 当前压缩算法(comp_algorithm 方括号里的那个)
        val candidates: List<String> = emptyList(),  // 全部候选算法(含当前)
        val diskSizeBytes: Long = 0,
        val memUsedBytes: Long = 0,
        val origDataBytes: Long = 0,
        val comprDataBytes: Long = 0,
    ) {
        /** 压缩比 = 原始大小 ÷ 压缩后大小; 数据不足时为 0。 */
        val ratio: Double
            get() = if (comprDataBytes > 0) origDataBytes.toDouble() / comprDataBytes else 0.0
    }

    /** 一次内存快照。meminfo 单位 kB; swapInKbps/swapOutKbps 单位 KB每秒。 */
    data class Snapshot(
        val meminfo: List<Pair<String, Long>> = emptyList(),
        val zram: ZramInfo = ZramInfo(),
        val vmParams: List<Pair<String, String>> = emptyList(),
        val swapInKbps: Double = 0.0,
        val swapOutKbps: Double = 0.0,
    ) {
        private fun kb(key: String): Long = meminfo.firstOrNull { it.first == key }?.second ?: 0
        val memTotalKb: Long get() = kb("MemTotal")
        val memAvailableKb: Long get() = kb("MemAvailable")
        val swapTotalKb: Long get() = kb("SwapTotal")
        val swapFreeKb: Long get() = kb("SwapFree")
    }

    // ── 读取 helper(与 SysfsReader.readFileOrRoot 同款: 免 root 直读优先, root 兜底)──

    private fun readFileOrRoot(path: String): String? {
        runCatching {
            val f = File(path)
            if (f.canRead()) {
                val s = f.readText().trim()
                if (s.isNotEmpty()) return s
            }
        }
        if (RootShell.isRooted()) {
            val s = runCatching { RootShell.exec("cat '$path' 2>/dev/null").trim() }.getOrNull()
            if (!s.isNullOrEmpty()) return s
        }
        return null
    }

    // ── Swap IO 差分基线(pswpin/pswpout 是累计页数, 两次采样差分换算速率)──
    @Volatile private var lastSwapIn = -1L
    @Volatile private var lastSwapOut = -1L
    @Volatile private var lastSampleNanos = 0L

    /** 采一次全量快照。任何一段读不到都静默降级, 绝不抛出。 */
    fun snapshot(): Snapshot = runCatching {
        val (swapIn, swapOut) = swapRates()
        Snapshot(
            meminfo = readMeminfo(),
            zram = readZram(),
            vmParams = readVmParams(),
            swapInKbps = swapIn,
            swapOutKbps = swapOut,
        )
    }.getOrDefault(Snapshot())

    // 解析 /proc/meminfo: 每行形如 "MemTotal:  12345678 kB", 只保留白名单项且按白名单顺序输出。
    private fun readMeminfo(): List<Pair<String, Long>> {
        val text = readFileOrRoot("/proc/meminfo") ?: return emptyList()
        val map = HashMap<String, Long>()
        for (line in text.lineSequence()) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            if (key !in MEMINFO_KEYS) continue
            val num = line.substring(idx + 1).trim()
                .split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: continue
            map[key] = num
        }
        return MEMINFO_KEYS.mapNotNull { k -> map[k]?.let { k to it } }
    }

    // 读 zram0 状态。新内核把 orig_data_size 等并进 mm_stat(前三列: 原始/压缩/占用), 做兜底。
    private fun readZram(): ZramInfo {
        val diskRaw = readFileOrRoot("$ZRAM_DIR/disksize")
        val exists = diskRaw != null || runCatching { File(ZRAM_DIR).exists() }.getOrDefault(false)
        if (!exists) return ZramInfo(available = false)

        // comp_algorithm 形如 "lzo lz4 [zstd] lz4hc", 方括号内为当前算法
        val algoRaw = readFileOrRoot("$ZRAM_DIR/comp_algorithm").orEmpty()
        val tokens = algoRaw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val current = tokens.firstOrNull { it.startsWith("[") }?.trim('[', ']').orEmpty()
        val candidates = tokens.map { it.trim('[', ']') }.filter { it.isNotEmpty() }

        var orig = readFileOrRoot("$ZRAM_DIR/orig_data_size")?.toLongOrNull() ?: -1
        var compr = readFileOrRoot("$ZRAM_DIR/compr_data_size")?.toLongOrNull() ?: -1
        var used = readFileOrRoot("$ZRAM_DIR/mem_used_total")?.toLongOrNull() ?: -1
        if (orig < 0 || compr < 0 || used < 0) {
            val mm = readFileOrRoot("$ZRAM_DIR/mm_stat")
                ?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }
            if (mm != null && mm.size >= 3) {
                if (orig < 0) orig = mm[0]
                if (compr < 0) compr = mm[1]
                if (used < 0) used = mm[2]
            }
        }
        return ZramInfo(
            available = true,
            algorithm = current,
            candidates = candidates,
            diskSizeBytes = diskRaw?.toLongOrNull() ?: 0,
            memUsedBytes = used.coerceAtLeast(0),
            origDataBytes = orig.coerceAtLeast(0),
            comprDataBytes = compr.coerceAtLeast(0),
        )
    }

    private fun readVmParams(): List<Pair<String, String>> =
        VM_KEYS.mapNotNull { k -> readFileOrRoot("$VM_DIR/$k")?.let { k to it } }

    // pswpin/pswpout 差分 → KB每秒(一页按 4KB)。首次调用只建基线返回 0。
    private fun swapRates(): Pair<Double, Double> {
        val text = readFileOrRoot("/proc/vmstat") ?: return 0.0 to 0.0
        var pin = -1L
        var pout = -1L
        for (line in text.lineSequence()) {
            when {
                line.startsWith("pswpin ") -> pin = line.removePrefix("pswpin ").trim().toLongOrNull() ?: -1
                line.startsWith("pswpout ") -> pout = line.removePrefix("pswpout ").trim().toLongOrNull() ?: -1
            }
            if (pin >= 0 && pout >= 0) break
        }
        if (pin < 0 || pout < 0) return 0.0 to 0.0
        val now = System.nanoTime()
        val prevIn = lastSwapIn
        val prevOut = lastSwapOut
        val prevT = lastSampleNanos
        lastSwapIn = pin; lastSwapOut = pout; lastSampleNanos = now
        if (prevIn < 0 || prevOut < 0 || prevT == 0L) return 0.0 to 0.0
        val dtSec = (now - prevT) / 1e9
        if (dtSec < 0.2) return 0.0 to 0.0
        val inKbps = (pin - prevIn).coerceAtLeast(0) * 4.0 / dtSec
        val outKbps = (pout - prevOut).coerceAtLeast(0) * 4.0 / dtSec
        return inKbps to outKbps
    }

    // ── 写操作(root)──

    /**
     * 切换 ZRAM 压缩算法: 标准重建流程(swapoff → reset → 写算法 → 恢复 disksize → mkswap → swapon)。
     * 一条 root 命令串行执行; 期间 zram 里已压缩的页会被换回内存, 内存紧张时可能有短暂卡顿。
     */
    fun setZramAlgorithm(algo: String, diskSizeBytes: Long): Boolean {
        // 算法名只可能是 lzo/lz4/zstd 这类安全字符, 校验兜底防注入
        if (algo.isEmpty() || !algo.all { it.isLetterOrDigit() || it == '_' || it == '-' }) return false
        if (diskSizeBytes <= 0) return false
        if (!runCatching { RootShell.isRooted() }.getOrDefault(false)) return false
        val cmd = "swapoff $ZRAM_DEV 2>/dev/null; " +
            "echo 1 > $ZRAM_DIR/reset && " +
            "echo $algo > $ZRAM_DIR/comp_algorithm && " +
            "echo $diskSizeBytes > $ZRAM_DIR/disksize && " +
            "mkswap $ZRAM_DEV && swapon $ZRAM_DEV"
        return runCatching { RootShell.execSilent(cmd) }.getOrDefault(false)
    }

    /**
     * 写一个 VM 参数(白名单内, 值必须是非负整数), 返回写入后读回的新值; 失败返回 null。
     * 立即生效, 重启后失效(不做持久化)。
     */
    fun writeVmParam(key: String, value: String): String? {
        if (key !in VM_KEYS) return null
        val v = value.trim()
        if (v.isEmpty() || v.length > 12 || !v.all { it.isDigit() }) return null
        if (!runCatching { RootShell.isRooted() }.getOrDefault(false)) return null
        val ok = runCatching { RootShell.execSilent("echo $v > $VM_DIR/$key") }.getOrDefault(false)
        if (!ok) return null
        return readFileOrRoot("$VM_DIR/$key")
    }
}
