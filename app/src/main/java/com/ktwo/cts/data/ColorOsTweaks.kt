// ColorOS/realmeUI(OnePlus/OPPO/realme)专属调控封装 —— 「系统服务」开关组(原创实现, 控制方式源自
// 对 ColorOS init 服务机制的分析):
//  - oiface / horae / GameOpt: ColorOS 的 init 服务, 用「持久属性 setprop persist.sys.*.enable +
//    init 服务 start/stop」组合控制; 状态读 getprop init.svc.<svc>(running/stopped), 兜底 pidof。
//  - CPU 频率限制: 节点 /proc/game_opt/disable_cpufreq_limit(0=限制生效, 1=已解除限制)。
// 全部经 RootShell 执行并 runCatching 容错: 无 root / 服务(节点)缺失 / 写失败一律静默返回默认值,
// 不抛出。UI 侧应先用 xxxAvailable() 探测存在性, 不存在的项灰置。
package com.ktwo.cts.data

import com.ktwo.cts.root.RootShell

object ColorOsTweaks {

    private const val CPU_LIMIT_NODE = "/proc/game_opt/disable_cpufreq_limit"

    // 服务名候选: 不同 ColorOS 版本/机型服务名可能不同, 探测到哪个就操作哪个。
    private val OIFACE_SVCS = listOf("oiface")
    private val HORAE_SVCS = listOf("horae")
    private val GAMEOPT_SVCS = listOf("oplus_gameopt", "gameopt")

    // ── 设备识别 ──────────────────────────────────────────────────────────────

    /** 是否 ColorOS / realmeUI(OnePlus / OPPO / realme)设备。 */
    fun isColorOs(): Boolean = runCatching {
        val ui = RootShell.exec("getprop ro.product.brand.ui").trim().lowercase()
        if (ui == "coloros" || ui == "realmeui") return true
        val brand = RootShell.exec("getprop ro.product.brand").trim().lowercase()
            .ifEmpty { android.os.Build.BRAND.orEmpty().lowercase() }
        brand == "oneplus" || brand == "oppo" || brand == "realme"
    }.getOrDefault(false)

    // ── 通用: init 服务探测与开关 helper ─────────────────────────────────────

    private fun propOf(key: String): String =
        runCatching { RootShell.exec("getprop $key").trim() }.getOrDefault("")

    private fun pidofAlive(svc: String): Boolean = runCatching {
        RootShell.exec("pidof $svc 2>/dev/null").trim().isNotEmpty()
    }.getOrDefault(false)

    /** 服务是否存在: init.svc.<svc> 有值(running/stopped 皆算) 或进程存活。 */
    private fun serviceExists(svc: String): Boolean =
        propOf("init.svc.$svc").isNotEmpty() || pidofAlive(svc)

    /** 服务是否在运行: init.svc.<svc>=="running" 或进程存活。 */
    private fun serviceRunning(svc: String): Boolean =
        propOf("init.svc.$svc") == "running" || pidofAlive(svc)

    /** 候选列表任一存在即视为该功能可用; persistProp 有值也算(说明系统认识这套开关)。 */
    private fun anyServiceAvailable(candidates: List<String>, persistProp: String): Boolean =
        runCatching {
            candidates.any { serviceExists(it) } || propOf(persistProp).isNotEmpty()
        }.getOrDefault(false)

    private fun anyServiceRunning(candidates: List<String>): Boolean =
        runCatching { candidates.any { serviceRunning(it) } }.getOrDefault(false)

    /**
     * 服务类开关: 一条 root 命令内完成「写持久属性 + 对存在的候选服务逐个 start/stop」。
     * 只对探测到存在的服务动手; start/stop 个别 ROM 不可用时兜底 setprop ctl.start/ctl.stop。
     * 持久属性负责跨重启记忆, start/stop 负责立即生效。
     */
    private fun setService(persistProp: String, candidates: List<String>, on: Boolean): Boolean =
        runCatching {
            val v = if (on) 1 else 0
            val verb = if (on) "start" else "stop"
            val ctl = if (on) "ctl.start" else "ctl.stop"
            val svcCmds = candidates.joinToString("; ") { svc ->
                "if [ -n \"\$(getprop init.svc.$svc)\" ] || pidof $svc >/dev/null 2>&1; " +
                    "then $verb $svc 2>/dev/null || setprop $ctl $svc; fi"
            }
            RootShell.execSilent("setprop $persistProp $v; $svcCmds")
        }.getOrDefault(false)

    // ── oiface: 与线程、风驰、触控相关的系统服务 ─────────────────────────────

    fun oifaceAvailable(): Boolean =
        anyServiceAvailable(OIFACE_SVCS, "persist.sys.oiface.enable")

    fun isOifaceOn(): Boolean = anyServiceRunning(OIFACE_SVCS)

    fun setOiface(on: Boolean): Boolean =
        setService("persist.sys.oiface.enable", OIFACE_SVCS, on)

    // ── horae: 与调度、温控相关的系统服务(命令已实机确认) ───────────────────

    fun horaeAvailable(): Boolean =
        anyServiceAvailable(HORAE_SVCS, "persist.sys.horae.enable")

    fun isHoraeOn(): Boolean = anyServiceRunning(HORAE_SVCS)

    fun setHorae(on: Boolean): Boolean =
        setService("persist.sys.horae.enable", HORAE_SVCS, on)

    // ── GameOpt: 游戏调度与游戏工具箱功能 ────────────────────────────────────

    /** 服务候选都探测不到时, 用 /proc/game_opt 目录存在与否做存在性兜底。 */
    fun gameOptAvailable(): Boolean = runCatching {
        anyServiceAvailable(GAMEOPT_SVCS, "persist.sys.gameopt.enable") ||
            RootShell.execSilent("[ -e /proc/game_opt ]")
    }.getOrDefault(false)

    fun isGameOptOn(): Boolean = anyServiceRunning(GAMEOPT_SVCS)

    fun setGameOpt(on: Boolean): Boolean =
        setService("persist.sys.gameopt.enable", GAMEOPT_SVCS, on)

    // ── CPU 频率限制: /proc/game_opt/disable_cpufreq_limit ──────────────────
    // 节点语义: 0=限制生效(默认), 1=已解除限制。开关语义与副文案「CPU 频率限制」一致:
    // 开=限制生效(写 0), 关=解除限制(写 1)。均可逆, 重启后恢复系统默认。

    fun cpuLimitAvailable(): Boolean = runCatching {
        RootShell.execSilent("[ -e $CPU_LIMIT_NODE ]")
    }.getOrDefault(false)

    /** 开=限制生效(节点值 0)。节点值可能是 "0,0" 逗号分段格式, 只看第一段。 */
    fun isCpuLimitOn(): Boolean = runCatching {
        RootShell.exec("cat $CPU_LIMIT_NODE 2>/dev/null")
            .trim().substringBefore(',').trim() == "0"
    }.getOrDefault(false)

    /** on=true 恢复限制(写 0), false 解除限制(写 1); 写后恢复只读权限。 */
    fun setCpuLimit(on: Boolean): Boolean = runCatching {
        val v = if (on) 0 else 1
        RootShell.execSilent(
            "chmod 666 $CPU_LIMIT_NODE; echo $v > $CPU_LIMIT_NODE; chmod 444 $CPU_LIMIT_NODE"
        )
    }.getOrDefault(false)
}
