package com.ktwo.cts.data

/** 守护 power_log.txt 一条原始电池采样。 */
data class PwrSample(
    val ts: Long, val level: Int, val curMa: Int,
    val voltMv: Int, val tempDc: Int, val chg: Boolean, val screen: Boolean,
)

/** 当前记录(放电/充电会话)+ 曲线 + 原始样本(供详细多线图)。 */
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
    val curve: List<Double>,
    val samples: List<PwrSample>,   // 本会话原始样本(画多线图)
) {
    companion object {
        val EMPTY = PowerSession(false, false, 0, 0, -1, 0.0, -999.0, 0.0, 0.0, 0.0, 0.0, emptyList(), emptyList())
    }
}

data class TypeSummary(val count: Int, val avgW: Double)
data class EtaPair(val remainMin: Int, val fullMin: Int)   // 当前电量剩余 / 满电(0→100)

/** 一条充电/放电会话(供"充电/放电总结"点开后逐条查看, 对齐 BatteryRecorder)。 */
data class SessionRow(
    val charging: Boolean,
    val startTs: Long, val endTs: Long,
    val startLv: Int, val endLv: Int,
    val avgW: Double,
) {
    val durationSec: Long get() = (endTs - startTs).coerceAtLeast(0)
    val deltaLv: Int get() = endLv - startLv   // 充电为正, 放电为负
}

/** "记录"页所需的完整聚合数据。 */
data class RecordsData(
    val valid: Boolean,
    val session: PowerSession,
    val charge: TypeSummary,
    val discharge: TypeSummary,
    val sceneOnW: Double,    // 亮屏放电平均功率
    val sceneOffW: Double,   // 息屏放电平均功率
    val etaOff: EtaPair?,    // 息屏续航
    val etaOn: EtaPair?,     // 亮屏续航
    val chargeRows: List<SessionRow>,     // 每次充电(新→旧)
    val dischargeRows: List<SessionRow>,  // 每次放电(新→旧)
) {
    companion object {
        val EMPTY = RecordsData(false, PowerSession.EMPTY, TypeSummary(0, 0.0), TypeSummary(0, 0.0),
            0.0, 0.0, null, null, emptyList(), emptyList())
    }
}

object PowerHistory {
    fun watt(curMa: Int, voltMv: Int, cellCount: Int): Double {
        if (curMa <= 0 || voltMv <= 0) return 0.0
        val mul = if (cellCount >= 2 && voltMv < 6000) 2.0 else 1.0
        return curMa.toDouble() * voltMv / 1_000_000.0 * mul
    }

    fun parseSamples(raw: String): List<PwrSample> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 6) return@mapNotNull null
            runCatching {
                PwrSample(
                    p[0].trim().toLong(), p[1].trim().toInt(), p[2].trim().toInt(),
                    p[3].trim().toInt(), p[4].trim().toInt(), p[5].trim() == "1",
                    if (p.size >= 7) p[6].trim() == "1" else true,
                )
            }.getOrNull()
        }.toList()
    }

    fun parse(raw: String, cellCount: Int): PowerSession =
        sessionOf(parseSamples(raw), cellCount)

    private fun sessionOf(samples: List<PwrSample>, cellCount: Int): PowerSession {
        if (samples.isEmpty()) return PowerSession.EMPTY
        val last = samples.last()
        var i = samples.size - 1
        while (i > 0 && samples[i - 1].chg == last.chg) i--
        val sess = samples.subList(i, samples.size)
        val curve = sess.map { watt(it.curMa, it.voltMv, cellCount) }.filter { it > 0.0 }
        val avg = if (curve.isNotEmpty()) curve.average() else 0.0
        return PowerSession(
            valid = true, charging = last.chg,
            startTs = sess.first().ts, durationSec = last.ts - sess.first().ts,
            level = last.level, voltageV = last.voltMv / 1000.0, tempC = last.tempDc / 10.0,
            curWatt = watt(last.curMa, last.voltMv, cellCount), avgWatt = avg,
            minWatt = curve.minOrNull() ?: 0.0, maxWatt = curve.maxOrNull() ?: 0.0,
            curve = curve, samples = sess.toList(),
        )
    }

    /** 完整聚合: 当前会话 + 充放电总结(来自 sessions 文件)+ 场景统计/续航预测(来自 log)。 */
    fun records(logRaw: String, sessionsRaw: String, cellCount: Int, capacityMah: Double): RecordsData {
        val samples = parseSamples(logRaw)
        if (samples.isEmpty()) return RecordsData.EMPTY
        val session = sessionOf(samples, cellCount)

        // 场景统计: 放电样本按亮/息屏分组求平均功率
        val dis = samples.filter { !it.chg }
        fun avgW(list: List<PwrSample>): Double {
            val w = list.map { watt(it.curMa, it.voltMv, cellCount) }.filter { it > 0.0 }
            return if (w.isNotEmpty()) w.average() else 0.0
        }
        val onW = avgW(dis.filter { it.screen })
        val offW = avgW(dis.filter { !it.screen })

        // 续航预测: 电池能量 Wh = 容量mAh × 标称3.85V / 1000; 剩余=电量%×能量; eta=能量/功率
        val level = session.level.coerceIn(0, 100)
        val energyWh = if (capacityMah > 0) capacityMah * 3.85 / 1000.0 else 0.0
        fun eta(w: Double): EtaPair? {
            if (energyWh <= 0 || w <= 0.05) return null
            val fullH = energyWh / w
            return EtaPair((fullH * level / 100.0 * 60).toInt(), (fullH * 60).toInt())
        }

        // 充放电总结 + 逐条记录(来自 sessions 文件): type,startTs,endTs,startLv,endLv,avgMw,avgVolt
        val chargeRows = ArrayList<SessionRow>(); val dischargeRows = ArrayList<SessionRow>()
        sessionsRaw.lineSequence().forEach { line ->
            val p = line.split(','); if (p.size < 7) return@forEach
            runCatching {
                val type = p[0].trim().toInt()
                val sTs = p[1].trim().toLong(); val eTs = p[2].trim().toLong()
                val sLv = p[3].trim().toInt(); val eLv = p[4].trim().toInt()
                val avgMw = p[5].trim().toInt(); val avgVolt = p[6].trim().toInt()
                val mul = if (cellCount >= 2 && avgVolt in 1 until 6000) 2.0 else 1.0
                val w = avgMw / 1000.0 * mul
                val row = SessionRow(type == 1, sTs, eTs, sLv, eLv, w)
                if (type == 1) chargeRows.add(row) else dischargeRows.add(row)
            }
        }
        chargeRows.reverse(); dischargeRows.reverse()   // 新→旧
        fun avgOf(rows: List<SessionRow>): Double {
            val w = rows.map { it.avgW }.filter { it > 0.0 }
            return if (w.isNotEmpty()) w.average() else 0.0
        }
        return RecordsData(
            valid = true, session = session,
            charge = TypeSummary(chargeRows.size, avgOf(chargeRows)),
            discharge = TypeSummary(dischargeRows.size, avgOf(dischargeRows)),
            sceneOnW = onW, sceneOffW = offW,
            etaOff = eta(offW), etaOn = eta(onW),
            chargeRows = chargeRows, dischargeRows = dischargeRows,
        )
    }
}
