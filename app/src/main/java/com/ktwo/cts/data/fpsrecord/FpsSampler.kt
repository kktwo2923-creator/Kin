// 帧率录制的秒级采样器: fps 复用 MetricReader 稳健链路; 帧时间/卡顿走 root
// SurfaceFlinger --latency(best-effort, 取不到一律填 0, 详情页显示"帧时间不可用", 不造假)。
package com.ktwo.cts.data.fpsrecord

import com.ktwo.cts.data.MetricReader
import com.ktwo.cts.root.RootShell
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 每秒调用一次 [sample] 得到一个 [FpsPoint]; 会话结束用 [buildSession] 汇总统计。
 * 非线程安全: 只在录制服务的单一协程里使用。
 */
class FpsSampler {

    data class FrameStat(val frameMaxMs: Int, val jank: Int, val bigJank: Int)

    // 帧率录制【专属】的 SurfaceFlinger 翻页差分状态(与悬浮窗刷新链路隔离, 避免互相把
    // prev 覆盖成 dt<0.2 → -1 → 假平线)。仅本采样器持有。
    private val fpsFlipState = MetricReader.FpsFlipState()

    // ── SurfaceFlinger layer 解析缓存 ──
    private var layerPkg = ""            // layerName 对应的包名(换应用后重新解析)
    private var layerName: String? = null
    private var layerFailCount = 0       // 连续解析失败次数, 过多则本会话放弃(省 root 开销)
    private var lastPresentNs = 0L       // 上一秒最后一帧的 actualPresent, 用于跨秒去重/差分
    private val recentFrameMs = ArrayDeque<Double>()  // 最近几帧耗时, 卡顿判定基线

    /** 采一个秒级点。所有子读取各自 runCatching, 永不抛出。 */
    fun sample(cellCount: Int, pkg: String): FpsPoint {
        // 用录制专属 flip 状态 + 禁用 fallback(帧率不可测时存 -1, 不存假刷新率)
        val snap = runCatching {
            MetricReader.snapshot(cellCount, fpsFlipState, fpsAllowFallback = false)
        }.getOrNull()
        val fs = runCatching { frameStats(pkg) }.getOrDefault(FrameStat(0, 0, 0))
        return FpsPoint(
            t = System.currentTimeMillis(),
            fps = snap?.fps ?: -1,
            cpuLoad = snap?.cpuLoadPct ?: -1,
            gpuLoad = snap?.gpuLoadPct ?: -1,
            gpuMhz = snap?.gpuMhz ?: -1,
            tempC = snap?.tempC ?: -999,
            battPct = snap?.battPct ?: -1,
            watt = snap?.watt?.takeIf { it.isFinite() } ?: 0.0,
            frameMaxMs = fs.frameMaxMs,
            jank = fs.jank,
            bigJank = fs.bigJank,
        )
    }

    // ── 帧时间/卡顿: dumpsys SurfaceFlinger --latency ──
    // 输出格式: 首行 = 刷新周期(ns); 其后每行三列时间戳(ns): desiredPresent / actualPresent / frameReady。
    // 用第 2 列 actualPresent 差分得到每帧实际耗时。挂起中的帧值为 INT64_MAX, 需剔除。
    // 不同 ROM 可能裁剪该命令/输出为空/图层命名不同 → 全部失败路径返回 0, 不影响主流程。

    private fun resolveLayer(pkg: String): String? {
        if (pkg.isEmpty() || !RootShell.isRooted()) return null
        if (layerPkg == pkg && layerName != null) return layerName
        if (layerPkg == pkg && layerFailCount > 5) return null   // 本会话多次找不到, 放弃
        if (layerPkg != pkg) { layerPkg = pkg; layerName = null; layerFailCount = 0 }
        val out = runCatching {
            RootShell.exec("dumpsys SurfaceFlinger --list 2>/dev/null")
        }.getOrDefault("")
        val cand = out.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(pkg) }
            .toList()
        // 游戏/视频内容多画在 SurfaceView 图层; 否则取最后一个含包名的图层(通常是 Activity 本体)
        val hit = cand.firstOrNull { it.startsWith("SurfaceView") }
            ?: cand.lastOrNull()
        if (hit == null) { layerFailCount++; return null }
        layerFailCount = 0
        layerName = hit
        return hit
    }

    private fun frameStats(pkg: String): FrameStat {
        val layer = resolveLayer(pkg) ?: return FrameStat(0, 0, 0)
        val safe = layer.replace("\"", "").replace("$", "").replace("`", "")
        val out = runCatching {
            RootShell.exec("dumpsys SurfaceFlinger --latency \"$safe\" 2>/dev/null")
        }.getOrDefault("")
        val lines = out.lines()
        if (lines.size < 2) { layerName = null; layerFailCount++; return FrameStat(0, 0, 0) }

        // 收集本次新增的 actualPresent(升序、剔除 0 与 INT64_MAX 挂起帧、只要比上次新的)
        val presents = ArrayList<Long>()
        for (i in 1 until lines.size) {
            val parts = lines[i].trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val p = parts[1].toLongOrNull() ?: continue
            if (p <= 0L || p == Long.MAX_VALUE) continue
            if (p > lastPresentNs) presents.add(p)
        }
        if (presents.isEmpty()) return FrameStat(0, 0, 0)
        presents.sort()

        var prev = if (lastPresentNs > 0L) lastPresentNs else presents.first()
        lastPresentNs = presents.last()
        var maxMs = 0.0
        var jank = 0
        var big = 0
        for (p in presents) {
            if (p <= prev) continue
            val ms = (p - prev) / 1_000_000.0
            prev = p
            if (ms > 1000.0) {   // 超 1s 视为图层空闲间隙(切后台/静止画面), 不算一帧, 重置基线
                recentFrameMs.clear()
                continue
            }
            if (ms > maxMs) maxMs = ms
            // 卡顿判定(行业等效标准, 原创实现): 单帧 > 前若干帧均值×2 且超过绝对阈值。
            // >125ms 记 bigJank, 否则 >83ms 记 jank(两者互斥计数, 合计即总卡顿)。
            val base = if (recentFrameMs.isEmpty()) 0.0 else recentFrameMs.average()
            if (base > 0.0 && ms > base * 2 && ms > 83.0) {
                if (ms > 125.0) big++ else jank++
            }
            recentFrameMs.addLast(ms)
            while (recentFrameMs.size > 3) recentFrameMs.removeFirst()
        }
        return FrameStat(maxMs.roundToInt(), jank, big)
    }

    companion object {
        /**
         * 会话收尾统计:
         *  - avg/min/max/1%Low/5%Low 均剔除 fps<=0 的无效秒后计算;
         *  - 1%/5% Low = 升序取最低 1%/5%(至少 1 个)样本的均值;
         *  - avgWatt 剔除 <=0 的无效值; jank/bigJank 直接求和。
         */
        fun buildSession(
            id: String,
            pkg: String,
            appName: String,
            startMs: Long,
            endMs: Long,
            stopReason: String,
            points: List<FpsPoint>,
            threads: List<ThreadLoad> = emptyList(),   // M3: 线程负载汇总(带默认值, 旧调用不受影响)
        ): FpsSession {
            val valid = points.mapNotNull { p -> p.fps.takeIf { it > 0 } }.sorted()
            fun lowPct(f: Double): Int {
                if (valid.isEmpty()) return 0
                val n = max(1, ceil(valid.size * f).toInt())
                return valid.take(n).average().roundToInt()
            }
            val watts = points.map { it.watt }.filter { it > 0.0 && it.isFinite() }
            return FpsSession(
                id = id,
                pkg = pkg,
                appName = appName,
                startMs = startMs,
                endMs = endMs,
                durationMs = (endMs - startMs).coerceAtLeast(0L),
                avgFps = if (valid.isEmpty()) 0.0 else valid.average(),
                minFps = valid.firstOrNull() ?: 0,
                maxFps = valid.lastOrNull() ?: 0,
                low1Fps = lowPct(0.01),
                low5Fps = lowPct(0.05),
                avgWatt = if (watts.isEmpty()) 0.0 else watts.average(),
                jankTotal = points.sumOf { it.jank },
                bigJankTotal = points.sumOf { it.bigJank },
                stopReason = stopReason,
                points = points,
                threads = threads,
            )
        }
    }
}
