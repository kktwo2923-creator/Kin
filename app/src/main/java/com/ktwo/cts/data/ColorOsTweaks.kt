// ColorOS/realmeUI(OnePlus/OPPO/realme)专属调控封装:
//  - 解除游戏 CPU 限频: /proc/game_opt/disable_cpufreq_limit (1=满血, 0=正常限频)
//  - 游戏触控优化:     /proc/touchpanel/game_switch_enable (值可能是 "1,0" 逗号格式, 取第一段)
//  - 临时关闭温控:     stop/start 温控服务(thermal-engine / oplus_thermald / horae), 重启自动恢复
// 全部经 RootShell 执行并 runCatching 容错: 无 root / 节点缺失 / 写失败一律静默返回默认值, 不抛出。
package com.ktwo.cts.data

import com.ktwo.cts.root.RootShell

object ColorOsTweaks {

    private const val CPU_LIMIT_NODE = "/proc/game_opt/disable_cpufreq_limit"
    private const val TOUCH_GAME_NODE = "/proc/touchpanel/game_switch_enable"

    // 不同机型温控服务名不同, 可能存在其一或多个; 探测/操作时逐个尝试。
    private val THERMAL_SERVICES = listOf("thermal-engine", "oplus_thermald", "horae")

    // ── 设备识别 ──────────────────────────────────────────────────────────────

    /** 是否 ColorOS / realmeUI(OnePlus / OPPO / realme)设备。 */
    fun isColorOs(): Boolean = runCatching {
        val ui = RootShell.exec("getprop ro.product.brand.ui").trim().lowercase()
        if (ui == "coloros" || ui == "realmeui") return true
        val brand = RootShell.exec("getprop ro.product.brand").trim().lowercase()
            .ifEmpty { android.os.Build.BRAND.orEmpty().lowercase() }
        brand == "oneplus" || brand == "oppo" || brand == "realme"
    }.getOrDefault(false)

    // ── 通用探测 ──────────────────────────────────────────────────────────────

    private fun nodeExists(path: String): Boolean =
        runCatching { RootShell.execSilent("[ -e $path ]") }.getOrDefault(false)

    private fun readNode(path: String): String =
        runCatching { RootShell.exec("cat $path 2>/dev/null").trim() }.getOrDefault("")

    // ── 解除 CPU 限频(满血) ─────────────────────────────────────────────────

    fun cpuLimitAvailable(): Boolean = nodeExists(CPU_LIMIT_NODE)

    /** 当前是否已解除限频(节点值 1=满血)。 */
    fun isCpuLimitRemoved(): Boolean =
        readNode(CPU_LIMIT_NODE).substringBefore(',').trim() == "1"

    /** remove=true 解除限频(写 1), false 恢复正常限频(写 0); 写后恢复只读权限。 */
    fun setCpuLimitRemoved(remove: Boolean): Boolean = runCatching {
        val v = if (remove) 1 else 0
        RootShell.execSilent(
            "chmod 666 $CPU_LIMIT_NODE; echo $v > $CPU_LIMIT_NODE; chmod 444 $CPU_LIMIT_NODE"
        )
    }.getOrDefault(false)

    // ── 游戏触控优化 ──────────────────────────────────────────────────────────

    fun touchGameAvailable(): Boolean = nodeExists(TOUCH_GAME_NODE)

    /** 节点值可能是 "1,0" 逗号分段格式, 只看第一段。 */
    fun isTouchGameOn(): Boolean = runCatching {
        RootShell.exec("cat $TOUCH_GAME_NODE 2>/dev/null | cut -f1 -d ','").trim() == "1"
    }.getOrDefault(false)

    fun setTouchGame(on: Boolean): Boolean = runCatching {
        RootShell.execSilent("echo ${if (on) 1 else 0} > $TOUCH_GAME_NODE")
    }.getOrDefault(false)

    // ── 临时关闭温控(可逆; 停服务, 重启自动恢复) ────────────────────────────

    private fun serviceExists(svc: String): Boolean = runCatching {
        RootShell.exec("getprop init.svc.$svc").trim().isNotEmpty() ||
            RootShell.exec("pidof $svc 2>/dev/null").trim().isNotEmpty()
    }.getOrDefault(false)

    private fun serviceRunning(svc: String): Boolean = runCatching {
        RootShell.exec("getprop init.svc.$svc").trim() == "running" ||
            RootShell.exec("pidof $svc 2>/dev/null").trim().isNotEmpty()
    }.getOrDefault(false)

    /** 任一已知温控服务存在即可用。 */
    fun thermalAvailable(): Boolean = THERMAL_SERVICES.any { serviceExists(it) }

    /** 任一温控服务在运行 → 温控开启。 */
    fun isThermalOn(): Boolean = THERMAL_SERVICES.any { serviceRunning(it) }

    /**
     * on=true 恢复温控(start), false 临时停用(stop)。一条 root 命令对全部候选服务逐个尝试,
     * 存在才动; start/stop 不可用时兜底 setprop ctl.start / ctl.stop。重启后 init 会自动拉起。
     */
    fun setThermal(on: Boolean): Boolean = runCatching {
        val verb = if (on) "start" else "stop"
        val ctl = if (on) "ctl.start" else "ctl.stop"
        val cmd = THERMAL_SERVICES.joinToString("; ") { svc ->
            "if [ -n \"\$(getprop init.svc.$svc)\" ] || pidof $svc >/dev/null 2>&1; " +
                "then $verb $svc 2>/dev/null || setprop $ctl $svc; fi"
        }
        RootShell.execSilent(cmd)
    }.getOrDefault(false)
}
