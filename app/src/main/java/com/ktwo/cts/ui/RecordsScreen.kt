package com.ktwo.cts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ktwo.cts.data.EtaPair
import com.ktwo.cts.data.PowerHistory
import com.ktwo.cts.data.RecordsData
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.SysfsReader
import com.ktwo.cts.data.TypeSummary
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun RecordsScreen() {
    val ctx = LocalContext.current
    val cellCount by Prefs.cellCountFlow(ctx).collectAsState(initial = 2)
    var data by remember { mutableStateOf(RecordsData.EMPTY) }
    var showDetail by remember { mutableStateOf(false) }

    LaunchedEffect(cellCount) {
        while (isActive) {
            data = withContext(Dispatchers.IO) {
                val log = RootShell.readPowerLog()
                val sess = RootShell.readPowerSessions()
                val cap = SysfsReader.capacityMah()
                var d = PowerHistory.records(log, sess, cellCount, cap)
                if (d.valid) {
                    val live = SysfsReader.powerInfo(cellCount)
                    d = d.copy(session = d.session.copy(
                        curWatt = live.watt, level = live.battPct,
                        voltageV = live.voltageV, tempC = live.battTempC))
                }
                d
            }
            delay(6000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionTitle("功耗记录")
        if (!data.valid) {
            TonalCard(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text("正在记录… 攒够采样点后显示(约 1 分钟)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            PowerCard(data.session) { showDetail = true }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SummaryCard("充电总结", data.charge, Modifier.weight(1f))
                SummaryCard("放电总结", data.discharge, Modifier.weight(1f))
            }

            if (data.etaOff != null || data.etaOn != null) {
                TonalCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("续航预测", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        data.etaOff?.let { etaRow("息屏", it) }
                        data.etaOn?.let { etaRow("亮屏日常", it) }
                        Text("估值 · 按当前场景平均功耗(剩余 / 满电)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            TonalCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("场景统计", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    statRow("息屏平均", if (data.sceneOffW > 0) "%.2f W".format(data.sceneOffW) else "—")
                    statRow("亮屏日常", if (data.sceneOnW > 0) "%.2f W".format(data.sceneOnW) else "—")
                }
            }
        }
        Spacer(Modifier.height(96.dp))
    }

    if (showDetail && data.session.valid) {
        PowerDetailDialog(data.session, cellCount) { showDetail = false }
    }
}

@Composable
private fun SummaryCard(title: String, s: TypeSummary, modifier: Modifier) {
    TonalCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            statRow("记录数", "${s.count} 次")
            statRow("平均功率", if (s.avgW > 0) "%.2f W".format(s.avgW) else "—")
        }
    }
}

@Composable
private fun statRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun etaRow(k: String, e: EtaPair) {
    fun f(m: Int) = if (m >= 60) "${m / 60}h${(m % 60).toString().padStart(2, '0')}m" else "${m}m"
    statRow(k, "约 ${f(e.remainMin)} / ${f(e.fullMin)}")
}
