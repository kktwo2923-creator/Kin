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

    // alpha 不设界(与 daemon 一致): 可负(比均衡更激进)/可超 1000(比省电包络更省),
    // 超出两端按外插换算, 结果由下方硬件边界兜底。
    private fun blend(bal: Float, bat: Float, alpha: Int): Float =
        bal + (bat - bal) * alpha / 1000f

    /**
     * 某状态的内置 CPU 频率上限(kHz)。
     * hwMaxKhz<=0(读不到本机 cpuinfo_max_freq)时返回参考机型原始值, 由调用方标注"参考"。
     */
    fun maxCapKhz(stateIdx: Int, alpha: Int, little: Boolean, hwMaxKhz: Long): Long {
        val t = LEVEL.getOrElse(stateIdx) { 1f }
        val floor = if (little) blend(BAL_F_MAXL, BAT_F_MAXL, alpha) else blend(BAL_F_MAXB, BAT_F_MAXB, alpha)
        val ceil = if (little) blend(BAL_C_MAXL, BAT_C_MAXL, alpha) else blend(BAL_C_MAXB, BAT_C_MAXB, alpha)
        val k = floor + (ceil - floor) * t
        val ref = if (little) REF_HWMAX_LITTLE else REF_HWMAX_BIG
        // 外插可能越过 [0, hwmax]: daemon 侧钳到 [本机最低频, hwmax], 这里展示层钳到 [0, hwmax];
        // 读不到本机 hwmax 的回退分支同样钳到参考机型 hwmax, 不显示超物理上限的参考值。
        return if (hwMaxKhz > 0) (k * hwMaxKhz / ref).toLong().coerceIn(0L, hwMaxKhz)
        else k.toLong().coerceIn(0L, ref.toLong())
    }

    /** 智能曲线上限换算成百分比(问卷/自定义开关的"当前生效值"基准); 读不到本机最高频时给 100。 */
    fun curvePct(stateIdx: Int, alpha: Int, little: Boolean, hwMaxKhz: Long): Int {
        if (hwMaxKhz <= 0) return 100
        val khz = maxCapKhz(stateIdx, alpha, little, hwMaxKhz)
        return (khz * 100 / hwMaxKhz).toInt().coerceIn(10, 100)
    }
}
