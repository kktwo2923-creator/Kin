// 智能调度监控面板:仅在开启智能调度(auto)时作为一个 tab 出现,读 daemon 的 auto_status.txt
// 实时展示 FSM 六状态、AI 学习状态(省电倾向/置信度)与温度/电量/GPU/负载/频率上限。
package com.ktwo.cts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ktwo.cts.data.KinRuntimeRepository
import com.ktwo.cts.data.Mode
import com.ktwo.cts.data.RuntimeSnapshot


private val FSM_STATES = listOf(
    "DEEP_IDLE" to "空闲",
    "LIGHT_IDLE" to "低负载",
    "MODERATE" to "中负载",
    "HEAVY" to "重负载",
    "SUSTAINED" to "持续高负载",
    "GAMING" to "游戏",
)

@Composable
fun AutoMonitorScreen() {
    // 与首页/自动模式/诊断页共用同一份运行时快照: 不再自己每 1.5s 发两条 root 命令(共享 su 会话
    // 被 FPS 录制等长命令占住时会排队读空), 且读失败时由仓库保留上一份数据(stale 随时间变 true),
    // 不会把整页清成「等待数据…」。
    val runtime by KinRuntimeRepository.snapshots.collectAsStateWithLifecycle(RuntimeSnapshot())
    val st = runtime.auto
    val daemonAlive = runtime.daemonAlive   // null = 未取到, 保留上次判定

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)   // 标题下移, 不再压系统状态栏
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SectionTitle("智能调度监控")

        val isFsm = st.fsm.isNotBlank()
        val stateLabel = when {
            daemonAlive == false -> "守护未运行"
            !st.valid -> "等待数据…"
            st.stale -> "状态已过期"
            isFsm -> FSM_STATES.firstOrNull { it.first.equals(st.fsm, ignoreCase = true) }?.second ?: st.fsm
            st.resolved.isNotBlank() -> "整档 · " + Mode.fromKey(st.resolved).label
            else -> "等待数据…"
        }
        // 顶部状态主卡
        TonalCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (isFsm) "实时状态(主动调频)" else "当前",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stateLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (daemonAlive != false && st.valid && !st.stale) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                )
                if (daemonAlive != false && (st.cpuMaxBig > 0 || st.cpuMaxLittle > 0)) {
                    Text(
                        "频率上限 小核 ${st.cpuMaxLittle / 1000}MHz · 大核 ${st.cpuMaxBig / 1000}MHz",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // FSM 六状态
        if (isFsm) {
            SectionTitle("状态")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FSM_STATES.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (key, label) ->
                                val active = key == st.fsm
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (active) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        label,
                                        Modifier.padding(vertical = 10.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // AI 学习状态
        SectionTitle("AI 学习")
        TonalCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (st.daypart in 0..5) {
                    val weekend = st.bucket >= 0 && (st.bucket % 2 == 1)
                    MonRow("学习时段", "${periodName(st.daypart)} · ${if (weekend) "周末" else "工作日"}")
                }
                // alpha 不设界(可负/可超 1000): 有状态就显示原值, 条按 0..1000 区间截取展示。
                MonBar("省电倾向", st.alpha, 1000, "越高越省电", hasValue = st.valid)
                MonBar("学习程度", st.conf, 1000, "学得越久越准 · 当前时段", hasValue = st.valid && !st.stale)
                MonRow("睡眠时段", when {
                    st.nightSleep >= 0 && st.nightWake >= 0 && st.nightSamples >= 3 ->
                        "${hhmm(st.nightSleep)}–${hhmm(st.nightWake)} · 已自学"
                    st.nightSamples in 1..2 -> "学习中 (${st.nightSamples}/3 晚)"
                    else -> "学习中 · 暂用固定时段"
                })
            }
        }

        // 负载闭环调频（P0-2/P0-3）
        // 判读要点：perf 是闭环自己算出的上限，cpu_max_* 是最终落盘值。
        // 两者不等 = 闭环意图被 FAS/类别/热/PolicyResolver 又收紧了，这是正常且应该能看到的。
        if (st.loadGovEnable) {
            SectionTitle("闭环调频")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MonRow("小核占用", if (st.loadGovBusyLittle >= 0) "${st.loadGovBusyLittle}%" else "—")
                    MonBar("小核上限", st.loadGovPerfLittle, 1000, "闭环算出的性能比 · 落盘 ${st.cpuMaxLittle / 1000}MHz",
                           hasValue = st.loadGovPerfLittle in 0..1000)
                    MonRow("大核占用", if (st.loadGovBusyBig >= 0) "${st.loadGovBusyBig}%" else "—")
                    MonBar("大核上限", st.loadGovPerfBig, 1000, "闭环算出的性能比 · 落盘 ${st.cpuMaxBig / 1000}MHz",
                           hasValue = st.loadGovPerfBig in 0..1000)
                    val perfL = st.loadGovPerfLittle
                    val perfB = st.loadGovPerfBig
                    if (perfL in 1..999 || perfB in 1..999) {
                        MonRow("状态", "闭环收紧中（未到硬件最高频）")
                    } else if (perfL >= 1000 || perfB >= 1000) {
                        MonRow("状态", "已放开至硬件上限")
                    }
                }
            }
        }

        // FAS 帧级分析（P1-1）
        if (st.fasFrameAnalysis) {
            SectionTitle("帧级分析")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MonRow("帧源", when (st.fasFrameSource) {
                        "per_frame" -> "逐帧（SurfaceFlinger）"
                        "window"    -> "窗口均值（measured_fps）"
                        else        -> "—"
                    })
                    MonRow("场景", when {
                        st.fasLoading   -> "读条 / 过图（已限频）"
                        st.fasPostGuard > 0 -> "加载后保护（剩余 ${st.fasPostGuard} 帧）"
                        st.fasState == "engaged" -> "游戏中"
                        st.fasState == "blocked" -> "风驰接管中"
                        else -> "未接管"
                    })
                    if (st.fasGearDwell > 0) {
                        MonRow("升档驻留", "${st.fasGearDwell} 帧")
                    }
                }
            }
        }

        // 实时指标
        SectionTitle("实时指标")
        TonalCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MonRow("温度", if (st.temp >= 0) "${st.temp}℃" else "—")
                MonRow("电量", if (st.batt >= 0) "${st.batt}%${if (st.charging) " (充电)" else ""}" else "—")
                MonRow("GPU 占用", if (st.gpu >= 0) "${st.gpu}%" else "—")
                MonRow("CPU 占用", if (st.cpuBusy >= 0) "${st.cpuBusy}%" else "—")
                MonRow("系统负载", if (st.load1 >= 0) String.format("%.2f", st.load1) else "—")
                MonRow("电流", when {
                    st.curMa < 0 -> "—"
                    st.charging  -> "充电 ${st.curMa} mA"
                    else         -> "放电 ${st.curMa} mA"
                })
                MonRow("屏幕", if (st.screen == 0) "息屏" else "亮屏")
                MonRow("功耗", if (st.powerW >= 0) "${st.powerW} W" else "—")
                if (st.lowbat) MonRow("低电省电", "已触发")
                if (st.thermalState.isNotBlank()) MonRow("热状态", thermalLabel(st.thermalState))
                if (st.batteryState.isNotBlank()) MonRow("电池策略", batteryLabel(st.batteryState))
                if (st.hardClamp) MonRow("厂商压频", "硬钳${if (st.headroomValid) " · 余量 ${st.headroom}%" else ""}")
                else if (st.softClamp) MonRow("厂商压频", "软钳")
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun MonBar(
    title: String, value: Int, max: Int, desc: String,
    hasValue: Boolean = value >= 0,   // 默认按符号判缺失; alpha 这类可负指标由调用方显式给
) {
    val v = value.coerceIn(0, max)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (hasValue) "$value / $max" else "—",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { if (hasValue) v.toFloat() / max else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// 学习时段(原 bucket 的 daypart):一天分 6 段,配合工作日/周末构成学习分组。
// thermal_state 的 token 必须与 Kin-Daemon 的 ThermalStatus.hpp 逐字对应
// （hard_clamp / critical / hot / warm_skin / vendor_limited / unknown / normal）——
// 写错只会默默落到 else 分支显示原始 token，不会报错，所以这里列全。
private fun thermalLabel(token: String): String = when (token) {
    "normal"         -> "正常"
    "warm_skin"      -> "外壳偏热"
    "vendor_limited" -> "厂商限频"
    "hot"            -> "偏热"
    "critical"       -> "过热降频"
    "hard_clamp"     -> "硬钳频"
    "unknown"        -> "—"
    else             -> token
}

private fun batteryLabel(token: String): String = when (token) {
    "disabled"         -> "未启用"
    "charging"         -> "充电中"
    "normal"           -> "正常"
    "low"              -> "低电"
    "critical"         -> "极低电"
    "low_held_unknown" -> "低电（保持）"
    "unknown"          -> "—"
    else               -> token
}
private fun hhmm(min: Int): String =
    if (min in 0..1439) "%02d:%02d".format(min / 60, min % 60) else "—"

private fun periodName(daypart: Int): String = when (daypart) {
    0 -> "凌晨"   // 0–5 点
    1 -> "清晨"   // 6–8 点
    2 -> "上午"   // 9–11 点
    3 -> "下午"   // 12–16 点
    4 -> "傍晚"   // 17–20 点
    5 -> "夜间"   // 21–23 点
    else -> "—"
}

@Composable
private fun MonRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
