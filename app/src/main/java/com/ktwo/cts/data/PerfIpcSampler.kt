// 每核 IPC 采样(对齐 Metric 负载窗展开面板的 IPC 列)。
// Metric 由 Rust 守护进程读 perf 计数器得到 IPC; 这里用等价方案:
// 独立 root 会话常驻一个 `simpleperf stat -a --per-core -e cpu-cycles,instructions --interval 1000`
// 周期统计进程, 流式解析每核计数, IPC = Δinstructions / Δcpu-cycles。
//  - 不同版本 simpleperf 的 --interval 输出有的是"自启动累计值"有的是"本区间增量":
//    默认按累计值做相邻块差分, 一旦观察到数值回落(说明本身就是增量流)自动切换直读模式;
//  - simpleperf 绝不能占用 libsu 主 shell(全 App 共享串行队列, 会堵死其他 root 读数),
//    因此使用专用 Shell 会话; start/stop 用锁+代际号串行化, 快速开关不再漏杀/漏启。
// 无 root / 无 simpleperf / 计数器不可用时保持空值, UI 按 Metric 空闲核惯例显示 "IPC--"。
package com.ktwo.cts.data

import com.ktwo.cts.root.RootShell
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell

object PerfIpcSampler {

    @Volatile private var ipc: DoubleArray = DoubleArray(0)
    private var running = false          // guarded by this
    private var gen = 0                  // 代际号: stop/重启使旧会话作废(guarded by this)
    @Volatile private var wanted = false
    @Volatile private var launchedAt = 0L
    @Volatile private var perfShell: Shell? = null

    /** 核号 → 最近一秒 IPC; 未知(未采到/不支持)返回 NaN。 */
    fun ipcOf(core: Int): Double = if (core in ipc.indices) ipc[core] else Double.NaN

    /** 开始采样(幂等)。必须在后台线程调用(libsu 根 shell 初始化不能在主线程)。 */
    fun start() {
        val myGen: Int
        synchronized(this) {
            wanted = true
            if (running) return
            running = true
            myGen = ++gen
        }
        // 环境检查在锁外(可能阻塞百毫秒级)
        val ok = RootShell.isRooted() &&
            RootShell.execSilent("command -v simpleperf >/dev/null 2>&1")
        synchronized(this) {
            if (!ok || gen != myGen || !wanted) { running = false; return }
        }
        launch(myGen)
    }

    /** 停止采样并清空数据。后台线程调用。stop 后立即可再次 start(不依赖回调复位)。 */
    fun stop() {
        val sh: Shell?
        synchronized(this) {
            wanted = false
            gen++              // 作废在跑/在启的会话
            sh = perfShell
            perfShell = null
            running = false    // 立即可重启; 旧回调按代际号自弃
        }
        ipc = DoubleArray(0)
        runCatching { sh?.close() }   // 关掉专用会话, simpleperf 随会话终止
        runCatching { RootShell.execSilent("pkill -f 'simpleperf stat -a --per-core' 2>/dev/null") }
    }

    private fun launch(myGen: Int) {
        launchedAt = System.currentTimeMillis()
        // 本区间原始读数 + 上一块读数(差分基线)
        val cycles = HashMap<Int, Long>()
        val instrs = HashMap<Int, Long>()
        val prevCyc = HashMap<Int, Long>()
        val prevIns = HashMap<Int, Long>()
        var rawMode = false   // false=按累计值差分; 观察到回落自动切增量直读

        fun publish() {
            val maxCpu = maxOf(cycles.keys.maxOrNull() ?: -1, instrs.keys.maxOrNull() ?: -1)
            if (maxCpu < 0) return
            // 回落检测: 任一核当前值 < 上一块值 → 输出本身就是每区间增量
            if (!rawMode) {
                for ((c, v) in cycles) {
                    val pv = prevCyc[c] ?: continue
                    if (v < pv) { rawMode = true; break }
                }
            }
            val arr = DoubleArray(maxCpu + 1) { Double.NaN }
            for (c in 0..maxCpu) {
                val cy = cycles[c] ?: 0L
                val ins = instrs[c] ?: 0L
                if (cy <= 0 || ins <= 0) continue
                if (rawMode) {
                    arr[c] = ins.toDouble() / cy.toDouble()
                } else {
                    val pc = prevCyc[c]; val pi = prevIns[c]
                    if (pc != null && pi != null && cy > pc && ins > pi) {
                        arr[c] = (ins - pi).toDouble() / (cy - pc).toDouble()
                    }   // 首块无基线 → 保持 NaN, 下一块起有值
                }
            }
            prevCyc.putAll(cycles)
            prevIns.putAll(instrs)
            ipc = arr
            cycles.clear(); instrs.clear()
        }

        val sink = object : CallbackList<String>() {
            override fun onAddElement(line: String) {
                runCatching {
                    // 新一轮报告块开始/上一块收尾 → 结算(不同版本块界行不同, 两种都认)
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
                synchronized(this) { if (gen == myGen) running = false }
                return
            }
            synchronized(this) {
                if (gen != myGen || !wanted) {   // 启动期间被 stop → 立即收掉
                    running = false
                    runCatching { sh.close() }
                    return
                }
                perfShell = sh
            }
            sh.newJob().add(cmd).to(sink).submit {
                runCatching { sh.close() }
                val relaunchGen: Int
                synchronized(this) {
                    if (perfShell === sh) perfShell = null
                    if (gen != myGen) return@submit   // 已被 stop/新会话接管, 不动状态
                    // 只有跑满一分钟以上(排除启动即失败的机器)且仍被需要才自续
                    if (wanted && System.currentTimeMillis() - launchedAt > 60_000) {
                        relaunchGen = ++gen
                    } else {
                        running = false
                        ipc = DoubleArray(0)
                        return@submit
                    }
                }
                launch(relaunchGen)
            }
        }.onFailure {
            synchronized(this) { if (gen == myGen) running = false }
        }
    }
}
