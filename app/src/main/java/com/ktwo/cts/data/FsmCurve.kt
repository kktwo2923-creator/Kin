package com.ktwo.cts.data

/**
 * 内置 FSM 调频曲线(镜像 daemon AutoFsm.hpp:204,211-214 的常量), 只用于向用户展示
 * "未覆盖时智能曲线大概把上限压到多少"; 真正的调频始终由 daemon 计算。
 */
object FsmCurve {
    // 六状态负载级别(State 枚举顺序 0..5)
    private val LEVEL = floatArrayOf(0.0f, 0.15f, 0.45f, 0.72f, 0.62f, 1.0f)
    // battery/balanced 两端的上限区间(参考机型 kHz), alpha 在两端之间插值。
    private const val BAT_F_MAXL = 921_600f
    private const val BAT_C_MAXL = 1_670_400f
    private const val BAT_F_MAXB = 921_600f
    private const val BAT_C_MAXB = 1_632_000f
    private const val BAL_F_MAXL = 1_190_400f
    private const val BAL_C_MAXL = 2_611_200f
    private const val BAL_F_MAXB = 1_881_600f
    private const val BAL_C_MAXB = 3_974_400f
    // 参考机型硬件最高频(kHz), 换算到本机时按 hwmax/REF 缩放。
    private const val REF_HWMAX_LITTLE = 2_035_200f
    private const val REF_HWMAX_BIG = 3_302_400f

    /** 内置 GPU 上限百分比(镜像 AutoFsm::gpuPctForState)。 */
    val GPU_BUILTIN = intArrayOf(40, 45, 60, 85, 80, 100)

    private fun blend(bal: Float, bat: Float, alpha: Int): Float =
        bal + (bat - bal) * alpha.coerceIn(0, 1000) / 1000f

    /**
     * 某状态的内置 CPU 频率上限(kHz)。
     * hwMaxKhz<=0(读不到本机 cpuinfo_max_freq)时返回参考机型原始值, 由调用方标注"参考"。
     */
    fun maxCapKhz(stateIdx: Int, alpha: Int, little: Boolean, hwMaxKhz: Long): Long {
        val t = LEVEL.getOrElse(stateIdx) { 1f }
        val a = alpha.coerceAtLeast(0)   // 未知 alpha 按 0(均衡端)展示
        val floor = if (little) blend(BAL_F_MAXL, BAT_F_MAXL, a) else blend(BAL_F_MAXB, BAT_F_MAXB, a)
        val ceil = if (little) blend(BAL_C_MAXL, BAT_C_MAXL, a) else blend(BAL_C_MAXB, BAT_C_MAXB, a)
        val k = floor + (ceil - floor) * t
        val ref = if (little) REF_HWMAX_LITTLE else REF_HWMAX_BIG
        return if (hwMaxKhz > 0) (k * hwMaxKhz / ref).toLong().coerceAtMost(hwMaxKhz)
        else k.toLong()
    }

    /** 智能曲线上限换算成百分比(问卷/自定义开关的"当前生效值"基准); 读不到本机最高频时给 100。 */
    fun curvePct(stateIdx: Int, alpha: Int, little: Boolean, hwMaxKhz: Long): Int {
        if (hwMaxKhz <= 0) return 100
        val khz = maxCapKhz(stateIdx, alpha, little, hwMaxKhz)
        return (khz * 100 / hwMaxKhz).toInt().coerceIn(10, 100)
    }
}
