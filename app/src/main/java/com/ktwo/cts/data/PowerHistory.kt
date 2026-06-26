package com.ktwo.cts.data

/** 守护 power_log.txt 一条原始电池采样。 */
data class PwrSample(
    val ts: Long, val level: Int, val curMa: Int,
    val voltMv: Int, val tempDc: Int, val chg: Boolean,
)

/** 由日志计算出的"当前记录"(放电/充电会话)+ 曲线。 */
data class PowerSession(
    val valid: Boolean,
    val charging: Boolean,
    val startTs: Long,
    val durationSec: Long,
    val level: Int,
    val voltageV: Double,
    val tempC: Double,
    val curWatt: Double,
    val avgWatt: Double,
    val minWatt: Double,
    val maxWatt: Double,
    val curve: List<Double>,      // 本会话每点功率(W)
    val fullCurve: List<Double>,  // 整段日志功率(W),供详情页
) {
    companion object {
        val EMPTY = PowerSession(false, false, 0, 0, -1, 0.0, -999.0, 0.0, 0.0, 0.0, 0.0, emptyList(), emptyList())
    }
}

object PowerHistory {
    /** 功率(W) = 电流mA × 电压mV / 1e6 × 双芯倍率(cellCount≥2 且电压<6V 才 ×2)。 */
    fun watt(curMa: Int, voltMv: Int, cellCount: Int): Double {
        if (curMa <= 0 || voltMv <= 0) return 0.0
        val mul = if (cellCount >= 2 && voltMv < 6000) 2.0 else 1.0
        return curMa.toDouble() * voltMv / 1_000_000.0 * mul
    }

    fun parse(raw: String, cellCount: Int): PowerSession {
        if (raw.isBlank()) return PowerSession.EMPTY
        val samples = raw.lineSequence().mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 6) return@mapNotNull null
            runCatching {
                PwrSample(p[0].trim().toLong(), p[1].trim().toInt(), p[2].trim().toInt(),
                    p[3].trim().toInt(), p[4].trim().toInt(), p[5].trim() == "1")
            }.getOrNull()
        }.toList()
        if (samples.isEmpty()) return PowerSession.EMPTY

        val last = samples.last()
        // 当前会话 = 末尾与最新采样同充放电状态的连续段
        var i = samples.size - 1
        while (i > 0 && samples[i - 1].chg == last.chg) i--
        val sess = samples.subList(i, samples.size)

        val curve = sess.map { watt(it.curMa, it.voltMv, cellCount) }.filter { it > 0.0 }
        val fullCurve = samples.map { watt(it.curMa, it.voltMv, cellCount) }
        val avg = if (curve.isNotEmpty()) curve.average() else 0.0
        return PowerSession(
            valid = true,
            charging = last.chg,
            startTs = sess.first().ts,
            durationSec = last.ts - sess.first().ts,
            level = last.level,
            voltageV = last.voltMv / 1000.0,
            tempC = last.tempDc / 10.0,
            curWatt = watt(last.curMa, last.voltMv, cellCount),
            avgWatt = avg,
            minWatt = curve.minOrNull() ?: 0.0,
            maxWatt = curve.maxOrNull() ?: 0.0,
            curve = curve,
            fullCurve = fullCurve,
        )
    }
}
