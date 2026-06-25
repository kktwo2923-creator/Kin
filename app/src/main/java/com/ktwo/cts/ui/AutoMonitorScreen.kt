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
import com.ktwo.cts.data.Mode
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class AutoStatus(
    val resolved: String = "",
    val fsm: String = "",
    val alpha: Int = -1,
    val conf: Int = -1,
    val temp: Int = -1,
    val batt: Int = -1,
    val gpu: Int = -1,
    val load1: Float = -1f,
    val cpuMaxLittle: Int = 0,
    val cpuMaxBig: Int = 0,
    val charging: Boolean = false,
    val screen: Int = 1,
    val lowbat: Boolean = false,
    val valid: Boolean = false,
) {
    companion object {
        fun parse(raw: String): AutoStatus {
            if (raw.isBlank()) return AutoStatus()
            val m = HashMap<String, String>()
            raw.lineSequence().forEach {
                val i = it.indexOf('=')
                if (i > 0) m[it.substring(0, i).trim()] = it.substring(i + 1).trim()
            }
            fun gi(k: String, d: Int = -1) = m[k]?.toIntOrNull() ?: d
            fun gf(k: String, d: Float = -1f) = m[k]?.toFloatOrNull() ?: d
            return AutoStatus(
                resolved = m["resolved"] ?: "",
                fsm = m["fsm"] ?: "",
                alpha = gi("alpha"), conf = gi("conf"), temp = gi("temp"), batt = gi("batt"),
                gpu = gi("gpu"), load1 = gf("load1"),
                cpuMaxLittle = gi("cpu_max_little", 0), cpuMaxBig = gi("cpu_max_big", 0),
                charging = gi("charging", 0) == 1, screen = gi("screen", 1),
                lowbat = gi("lowbat", 0) == 1, valid = true,
            )
        }
    }
}

private val FSM_STATES = listOf(
    "DEEP_IDLE" to "深度空闲",
    "LIGHT_IDLE" to "轻度空闲",
    "MODERATE" to "中负载",
    "HEAVY" to "重负载",
    "SUSTAINED" to "持续高负载",
    "GAMING" to "游戏",
)

@Composable
fun AutoMonitorScreen() {
    var st by remember { mutableStateOf(AutoStatus()) }
    var alive by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            val raw = withContext(Dispatchers.IO) { RootShell.readAutoStatus() }
            st = AutoStatus.parse(raw)
            alive = withContext(Dispatchers.IO) { RootShell.daemonAlive() }
            delay(1500)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SectionTitle("智能调度监控")

        val isFsm = st.fsm.isNotBlank()
        val stateLabel = when {
            !alive -> "守护未运行"
            isFsm -> FSM_STATES.firstOrNull { it.first == st.fsm }?.second ?: st.fsm
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
                    color = if (alive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                )
                if (alive && (st.cpuMaxBig > 0 || st.cpuMaxLittle > 0)) {
                    Text(
                        "频率上限 小核 ${st.cpuMaxLittle / 1000}MHz · 大核 ${st.cpuMaxBig / 1000}MHz",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // FSM 六状态机
        if (isFsm) {
            SectionTitle("状态机")
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
                MonBar("省电倾向", st.alpha, 1000, "越高越省电")
                MonBar("学习置信度", st.conf, 1000, "学得越久越准")
            }
        }

        // 实时指标
        SectionTitle("实时指标")
        TonalCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MonRow("温度", if (st.temp >= 0) "${st.temp}℃" else "—")
                MonRow("电量", if (st.batt >= 0) "${st.batt}%${if (st.charging) " (充电)" else ""}" else "—")
                MonRow("GPU 占用", if (st.gpu >= 0) "${st.gpu}%" else "—")
                MonRow("系统负载", if (st.load1 >= 0) String.format("%.2f", st.load1) else "—")
                MonRow("屏幕", if (st.screen == 0) "息屏" else "亮屏")
                if (st.lowbat) MonRow("低电省电", "已触发")
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun MonBar(title: String, value: Int, max: Int, desc: String) {
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
                if (value >= 0) "$value / $max" else "—",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { if (value >= 0) v.toFloat() / max else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MonRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
