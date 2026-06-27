package com.ktwo.cts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ktwo.cts.data.EtaPair
import com.ktwo.cts.data.PowerHistory
import com.ktwo.cts.data.RecordsData
import com.ktwo.cts.data.SessionRow
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.SysfsReader
import com.ktwo.cts.data.TypeSummary
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RecordsScreen() {
    val ctx = LocalContext.current
    val cellCount by Prefs.cellCountFlow(ctx).collectAsState(initial = 1)
    // initial=true 避免首帧(flow 未发射)就闪一下矫正框; 真值到达后若为 false 才弹。
    val calibrated by Prefs.cellCalibratedFlow(ctx).collectAsState(initial = true)
    var data by remember { mutableStateOf(RecordsData.EMPTY) }
    var showDetail by remember { mutableStateOf(false) }
    // null=不显示;true=充电逐条;false=放电逐条
    var listType by remember { mutableStateOf<Boolean?>(null) }
    // 逐条列表里被点开查看详细的那条记录(null=未点开)
    var detailRow by remember { mutableStateOf<SessionRow?>(null) }
    // 清空记录后自增, 触发立即重载(不必等下一次轮询)
    var reloadKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cellCount, reloadKey) {
        while (isActive) {
            // 整段读取/聚合包在 runCatching 内: 任何 sysfs/解析异常都退回上次数据,
            // 不让协程未捕获异常把「记录」页直接崩掉。
            data = withContext(Dispatchers.IO) {
                runCatching {
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
                }.getOrDefault(data)
            }
            delay(6000)
        }
    }

    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)   // 标题下移, 不再压系统状态栏
            .verticalScroll(rememberScrollState())
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

            Row(
                Modifier.height(IntrinsicSize.Min),   // 两卡片等高: 取较高者的高度
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SummaryCard("充电总结", data.charge, Modifier.weight(1f).fillMaxHeight()) { listType = true }
                SummaryCard("放电总结", data.discharge, Modifier.weight(1f).fillMaxHeight()) { listType = false }
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
    listType?.let { charge ->
        val rows = if (charge) data.chargeRows else data.dischargeRows
        SessionListDialog(
            title = if (charge) "充电记录" else "放电记录",
            charging = charge,
            rows = rows,
            onRowClick = { detailRow = it },
            onClear = {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { RootShell.clearPowerSessions() } }
                    listType = null
                    reloadKey++   // 立即重载, 清空生效
                }
            },
            onDismiss = { listType = null },
        )
    }
    detailRow?.let { row ->
        SessionDetailDialog(row) { detailRow = null }
    }

    // 首次进入「记录」且未矫正 → 弹一次电芯矫正(强制选择, 选后置 cell_calibrated=true)。
    // 选择后 flow 自动回流为 true, 对话框收起。
    if (!calibrated) {
        CellCalibrationDialog(cancelable = false, onDone = { /* flow 回流为 true 自动收起 */ })
    }
}

@Composable
private fun SummaryCard(title: String, s: TypeSummary, modifier: Modifier, onClick: () -> Unit) {
    TonalCard(modifier.clickable(onClick = onClick)) {
        Column(Modifier.fillMaxHeight().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("逐条 ›", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            statRow("记录数", "${s.count} 次")
            statRow("平均功率", if (s.avgW > 0) "%.2f W".format(s.avgW) else "—")
        }
    }
}

/** 逐条充/放电记录(对齐 BatteryRecorder: 每回都能点开看 + 可清空)。 */
@Composable
private fun SessionListDialog(
    title: String,
    charging: Boolean,
    rows: List<SessionRow>,
    onRowClick: (SessionRow) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TonalCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("共 ${rows.size} 次", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (rows.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text("暂无记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        Modifier.heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(rows) { r -> SessionRowItem(r, charging) { onRowClick(r) } }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (rows.isNotEmpty()) {
                        if (confirmClear) {
                            TextButton(onClick = onClear,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error)) { Text("确认清空") }
                            TextButton(onClick = { confirmClear = false }) { Text("取消") }
                        } else {
                            TextButton(onClick = { confirmClear = true },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error)) { Text("清空记录") }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun SessionRowItem(r: SessionRow, charging: Boolean, onClick: () -> Unit) {
    TonalCard(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${fmtDay(r.startTs)} ${fmtHm(r.startTs)} → ${fmtHm(r.endTs)}",
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(if (r.avgW > 0) "%.2f W".format(r.avgW) else "—",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val lvl = if (charging) "电量 ${r.startLv}% → ${r.endLv}% (+${(r.deltaLv).coerceAtLeast(0)})"
                          else "电量 ${r.startLv}% → ${r.endLv}% (${r.deltaLv})"
                Text(lvl, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("时长 ${fmtDur(r.durationSec)} ›", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 单条充/放电记录详细(点开列表里某一回看)。历史会话只存汇总, 故展示起止时间/时长/电量/平均功率。 */
@Composable
private fun SessionDetailDialog(r: SessionRow, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        TonalCard(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (r.charging) "充电记录详细" else "放电记录详细",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    statRow("日期", fmtDate(r.startTs))
                    statRow("时间段", "${fmtHm(r.startTs)} → ${fmtHm(r.endTs)}")
                    statRow("时长", fmtDur(r.durationSec))
                    statRow("起始电量", "${r.startLv}%")
                    statRow("结束电量", "${r.endLv}%")
                    statRow("电量变化", if (r.charging) "+${r.deltaLv.coerceAtLeast(0)}%" else "${r.deltaLv}%")
                    statRow("平均功率", if (r.avgW > 0) "%.2f W".format(r.avgW) else "—")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

private fun fmtHm(ts: Long): String = runCatching {
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts * 1000))
}.getOrDefault("—")

private fun fmtDay(ts: Long): String = runCatching {
    java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(java.util.Date(ts * 1000))
}.getOrDefault("")

private fun fmtDate(ts: Long): String = runCatching {
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(ts * 1000))
}.getOrDefault("—")

private fun fmtDur(sec: Long): String = when {
    sec < 60 -> "${sec}s"
    sec < 3600 -> "${sec / 60}m"
    else -> "${sec / 3600}h${(sec % 3600) / 60}m"
}

@Composable
private fun statRow(k: String, v: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            k,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
        Spacer(Modifier.weight(1f))
        Text(
            v,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun etaRow(k: String, e: EtaPair) {
    fun f(m: Int) = if (m >= 60) "${m / 60}h${(m % 60).toString().padStart(2, '0')}m" else "${m}m"
    statRow(k, "约 ${f(e.remainMin)} / ${f(e.fullMin)}")
}
