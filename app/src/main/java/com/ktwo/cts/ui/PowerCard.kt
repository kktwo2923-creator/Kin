package com.ktwo.cts.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ktwo.cts.data.PowerSession

/** 平滑折线(Catmull-Rom→贝塞尔)功耗曲线。 */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier, color: Color) {
    if (values.size < 2) { Spacer(modifier); return }
    val mn = values.min()
    val mx = values.max()
    val range = (mx - mn).let { if (it > 1e-6) it else 1.0 }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val n = values.size
        val pad = h * 0.12f
        fun px(i: Int) = w * i / (n - 1)
        fun py(v: Double) = (h - pad) - ((v - mn) / range * (h - 2 * pad)).toFloat()
        val pts = List(n) { Offset(px(it), py(values[it])) }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 0 until n - 1) {
                val p0 = pts[if (i == 0) 0 else i - 1]
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val p3 = pts[if (i + 2 < n) i + 2 else n - 1]
                val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
                val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
                cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
            }
        }
        drawPath(path, color, style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun fmtClock(ts: Long): String {
    if (ts <= 0) return "—"
    return runCatching {
        val d = java.util.Date(ts * 1000)
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(d)
    }.getOrDefault("—")
}

private fun fmtDur(sec: Long): String = when {
    sec < 60 -> "${sec}s"
    sec < 3600 -> "${sec / 60}m"
    else -> "${sec / 3600}h${(sec % 3600) / 60}m"
}

@Composable
private fun kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

/** 紧凑"当前记录"卡片(左数据 + 右曲线/功耗),点击看详情。 */
@Composable
fun PowerCard(s: PowerSession, onClick: () -> Unit) {
    if (!s.valid) return
    val accent = MaterialTheme.colorScheme.primary
    TonalCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("当前记录 · ${if (s.charging) "充电" else "放电"}",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("详情 ›", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    kv("开始时间", fmtClock(s.startTs))
                    kv("当前电量", if (s.level >= 0) "${s.level}%" else "—")
                    kv("电压", "%.3f V".format(s.voltageV))
                    kv("温度", "%.1f℃".format(s.tempC))
                    kv("时长", fmtDur(s.durationSec))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Sparkline(s.curve, Modifier.fillMaxWidth().height(56.dp), accent)
                    Spacer(Modifier.height(8.dp))
                    Text("当前 %.2f W".format(s.curWatt), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("平均 %.2f W".format(s.avgWatt), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun PowerDetailDialog(s: PowerSession, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        TonalCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("功耗详情 · ${if (s.charging) "充电" else "放电"}",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Sparkline(s.fullCurve.ifEmpty { s.curve },
                    Modifier.fillMaxWidth().height(120.dp), MaterialTheme.colorScheme.primary)
                Text("近 ${fmtDur(s.durationSec)} · 共 ${s.fullCurve.size} 采样点(每 30s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    kv("开始时间", fmtClock(s.startTs))
                    kv("时长", fmtDur(s.durationSec))
                    kv("当前功耗", "%.2f W".format(s.curWatt))
                    kv("平均功耗", "%.2f W".format(s.avgWatt))
                    kv("最高功耗", "%.2f W".format(s.maxWatt))
                    kv("最低功耗", "%.2f W".format(s.minWatt))
                    kv("当前电量", if (s.level >= 0) "${s.level}%" else "—")
                    kv("电压", "%.3f V".format(s.voltageV))
                    kv("电池温度", "%.1f℃".format(s.tempC))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}
