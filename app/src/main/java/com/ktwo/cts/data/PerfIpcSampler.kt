// 每核 IPC 采样(对齐 Metric 负载窗展开面板的 IPC 列)。
// Metric 由 Rust 守护进程读 perf 计数器得到 IPC; 这里用等价方案:
// root 下常驻一个 `simpleperf stat -a --per-core -e cpu-cycles,instructions --interval 1000`
// 周期统计进程, 流式解析每秒每核的指令数/周期数, IPC = instructions / cpu-cycles。
// 无 root / 无 simpleperf / 计数器不可用时保持空值, UI 按 Metric 空闲核惯例显示 "IPC--"。
package com.ktwo.cts.data

import com.ktwo.cts.root.RootShell
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import java.util.concurrent.atomic.AtomicBoolean

object PerfIpcSampler {

    @Volatile private var ipc: DoubleArray = DoubleArray(0)
    private val running = AtomicBoolean(false)
    @Volatile private var wanted = false
    @Volatile private var launchedAt = 0L
    // 专用 root 会话: simpleperf 是常驻长命令, 绝不能占用 libsu 主 shell ——
    // 主 shell 是全 App 共享的串行队列, 被占住会堵死帧率/进程/线程等所有 root 读数
    @Volatile private var perfShell: Shell? = null

    /** 核号 → 最近一秒 IPC; 未知(未采到/不支持)返回 NaN。 */
    fun ipcOf(core: Int): Double = if (core in ipc.indices) ipc[core] else Double.NaN

    /** 开始采样(幂等)。必须在后台线程调用(libsu 根 shell 初始化不能在主线程)。 */
    fun start() {
        wanted = true
        if (!running.compareAndSet(false, true)) return
        if (!RootShell.isRooted() ||
            !RootShell.execSilent("command -v simpleperf >/dev/null 2>&1")) {
            running.set(false)
            return
        }
        launch()
    }

    /** 停止采样并清空数据。后台线程调用。 */
    fun stop() {
        wanted = false
        ipc = DoubleArray(0)
        runCatching { perfShell?.close() }   // 关掉专用会话, simpleperf 随会话终止
        perfShell = null
        runCatching { RootShell.execSilent("pkill -f 'simpleperf stat -a --per-core' 2>/dev/null") }
    }

    private fun launch() {
        launchedAt = System.currentTimeMillis()
        // 每个 interval 报告块累计的每核计数; 块头出现时结算上一块
        val cycles = HashMap<Int, Long>()
        val instrs = HashMap<Int, Long>()

        fun publish() {
            val maxCpu = maxOf(cycles.keys.maxOrNull() ?: -1, instrs.keys.maxOrNull() ?: -1)
            if (maxCpu < 0) return
            val arr = DoubleArray(maxCpu + 1) { Double.NaN }
            for (c in 0..maxCpu) {
                val cy = cycles[c] ?: 0L
                val ins = instrs[c] ?: 0L
                if (cy > 0 && ins > 0) arr[c] = ins.toDouble() / cy.toDouble()
            }
            ipc = arr
            cycles.clear(); instrs.clear()
        }

        val sink = object : CallbackList<String>() {
            override fun onAddElement(line: String) {
                runCatching {
                    // 新一轮报告块开始/上一块收尾 → 结算(不同版本 simpleperf 块界行不同, 两种都认)
                    if (line.contains("Performance counter statistics") ||
                        line.startsWith("Total test time")) {
                        publish()
                        return@runCatching
                    }
                    // 数据行(--per-core): "  0  691,159,068  cpu-cycles  # ..."
                    val tok = line.trim().split(Regex("\\s+"))
                    if (tok.size < 3) return@runCatching
                    val evIdx = tok.indexOfFirst {
                        it.startsWith("cpu-cycles") || it.startsWith("instructions")
                    }
                    if (evIdx < 2) return@runCatching
                    val cpu = tok[0].toIntOrNull() ?: return@runCatching
                    if (cpu !in 0..63) return@runCatching
                    val count = tok[evIdx - 1].replace(",", "").toLongOrNull() ?: return@runCatching
                    if (tok[evIdx].startsWith("cpu-cycles")) cycles[cpu] = count
                    else instrs[cpu] = count
                }
            }
        }

        // --duration 3600: 一小时自续(见 submit 回调); stderr 丢弃避免污染解析
        val cmd = "simpleperf stat -a --per-core -e cpu-cycles,instructions " +
            "--interval 1000 --duration 3600 2>/dev/null"
        runCatching {
            // 独立 root 会话跑长命令(不能用 Shell.cmd —— 那是共享主 shell 的串行队列)
            val sh = Shell.Builder.create().build("su")
            if (!sh.isRoot) {
                runCatching { sh.close() }
                running.set(false)
                return
            }
            perfShell = sh
            sh.newJob().add(cmd).to(sink).submit {
                runCatching { sh.close() }
                if (perfShell === sh) perfShell = null
                running.set(false)
                ipc = DoubleArray(0)
                // 只有跑满一分钟以上(排除启动即失败的机器)且仍被需要才自续
                if (wanted && System.currentTimeMillis() - launchedAt > 60_000 &&
                    running.compareAndSet(false, true)
                ) launch()
            }
        }.onFailure { running.set(false) }
    }
}
