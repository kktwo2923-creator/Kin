// 线程分配冲突检测(Metric 式): 其它调度器/线程优化工具在跑时, 双方会互相改同一批线程的
// 亲和性/优先级, 后写的赢 → 表现为"设置不生效/来回跳"。进页时检测一次并给出横幅提示,
// AppOpt/AsoulOpt 属于纯线程优化守护, 可一键结束(修复); horae/scene 是完整调度器, 只提示不代杀。
// 全部走 root, 无 root 时返回空列表(不打扰)。
package com.ktwo.cts.ui.threadalloc

import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一条冲突: 提示文案 + 是否红色错误级(否则黄色提醒级) + 可一键结束的进程名(null = 无修复按钮)。 */
data class TaConflict(
    val msg: String,
    val isError: Boolean,
    val killProc: String? = null,
)

object TaConflictDetect {

    /** 检测已知冲突源。root 不可用或全部未命中时返回空列表。 */
    suspend fun detect(): List<TaConflict> = withContext(Dispatchers.IO) {
        if (!runCatching { RootShell.isRooted() }.getOrDefault(false)) return@withContext emptyList()
        val out = mutableListOf<TaConflict>()
        if (svcRunning("horae")) {
            out += TaConflict("检测到系统调度服务 horae 运行, 可能与线程分配冲突", isError = true)
        }
        if (procRunning("scene-daemon")) {
            out += TaConflict("检测到 Scene 运行, 请确保其核心分配功能已关闭", isError = false)
        }
        if (procRunning("AppOpt")) {
            out += TaConflict("检测到 AppOpt 线程优化运行, 与线程分配冲突", isError = true, killProc = "AppOpt")
        }
        if (procRunning("AsoulOpt")) {
            out += TaConflict("检测到 AsoulOpt 运行, 与游戏线程分配冲突", isError = true, killProc = "AsoulOpt")
        }
        out
    }

    /** 修复: 结束指定进程的全部 pid。返回是否找到了并下发了 kill。 */
    suspend fun kill(proc: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val pids = RootShell.exec("pidof $proc 2>/dev/null").trim()
                .split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
            if (pids.isEmpty()) return@runCatching false
            RootShell.execSilent("kill -9 ${pids.joinToString(" ")}")
        }.getOrDefault(false)
    }

    private fun svcRunning(svc: String): Boolean = runCatching {
        RootShell.exec("getprop init.svc.$svc").trim() == "running"
    }.getOrDefault(false)

    private fun procRunning(proc: String): Boolean = runCatching {
        RootShell.exec("pidof $proc 2>/dev/null").trim().isNotEmpty()
    }.getOrDefault(false)
}
