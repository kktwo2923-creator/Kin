// 帧率记录详情页: 会话信息 + 多张「标题+统计行+折线图」卡片(FPS/帧时间/CPU/GPU/温度/功耗/电量)。
package com.ktwo.cts.ui.fpsrecord

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ktwo.cts.data.fpsrecord.FpsRecordStore
import com.ktwo.cts.data.fpsrecord.FpsSession
import com.ktwo.cts.ui.TonalCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpsRecordDetailScreen(
    id: String,
    onBack: () -> Unit,
    onOpenThreads: (String) -> Unit = {},   // M3: 打开「线程负载」二级页(带默认值, 旧调用兼容)
) {
    val ctx = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<FpsSession?>(null) }

    LaunchedEffect(id) {
        session = withContext(Dispatchers.IO) {
            runCatching { FpsRecordStore.load(ctx, id) }.getOrNull()
        }
        loaded = true
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("记录详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { inner ->
        val s = session
        when {
            !loaded -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            s == null -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text(
                    "记录不存在或已删除",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> DetailContent(s, onOpenThreads, Modifier.fillMaxSize().padding(inner))
        }
    }
}

@Composable
private fun DetailContent(
    s: FpsSession,
    onOpenThreads: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pts = s.points
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 会话信息 ──
        item {
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(
                        s.appName.ifEmpty { s.pkg.ifEmpty { "未知应用" } },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    if (s.pkg.isNotEmpty() && s.pkg != s.appName) {
                        Text(
                            s.pkg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    InfoLine("开始时间", fmtFullDate(s.startMs))
                    InfoLine("时长", fmtDurationText(s.durationMs))
                    InfoLine("停止原因", s.stopReason.ifEmpty { "--" })
                    InfoLine("备注", "--")
                }
            }
        }

        // ── 1) FPS ──
        item {
            val values = pts.map { it.fps.coerceAtLeast(0).toFloat() }
            ChartCard(
                title = "FPS",
                color = MaterialTheme.colorScheme.primary,
                stats = listOf(
                    "MAX" to fmtInt(s.maxFps),
                    "MIN" to fmtInt(s.minFps),
                    "AVG" to if (s.avgFps > 0) "%.1f".format(s.avgFps) else "--",
                    "1% Low" to fmtInt(s.low1Fps),
                    "5% Low" to fmtInt(s.low5Fps),
                ),
                values = values,
                minY = 0f,
                footer = "JANK ${s.jankTotal} · BIG JANK ${s.bigJankTotal}" +
                    if (pts.none { it.frameMaxMs > 0 }) "(帧时间不可用, 未统计)" else "",
            )
        }

        // ── 2) 帧时间 ──
        item {
            val frames = pts.map { it.frameMaxMs.toFloat() }
            val available = pts.any { it.frameMaxMs > 0 }
            ChartCard(
                title = "帧时间 ms",
                color = MaterialTheme.colorScheme.tertiary,
                stats = if (available)
                    listOf("MAX" to fmtInt(frames.max().toInt()))
                else emptyList(),
                values = if (available) frames else emptyList(),
                minY = 0f,
                emptyHint = if (available) null else "帧时间不可用",
            )
        }

        // ── 3) CPU 占用 ──
        item {
            MetricChartCard(
                title = "CPU 占用 %",
                raw = pts.map { it.cpuLoad },
                color = MaterialTheme.colorScheme.primary,
                percent = true,
            )
        }

        // ── 4) GPU 占用 ──
        item {
            MetricChartCard(
                title = "GPU 占用 %",
                raw = pts.map { it.gpuLoad },
                color = MaterialTheme.colorScheme.secondary,
                percent = true,
            )
        }

        // ── 5) 温度 ──
        item {
            MetricChartCard(
                title = "温度 ℃",
                raw = pts.map { it.tempC },
                color = MaterialTheme.colorScheme.error,
                percent = false,
                invalidBelow = -100,
            )
        }

        // ── 6) 功耗 ──
        item {
            val watts = pts.map { it.watt }
            val valid = watts.filter { it > 0.0 && it.isFinite() }
            ChartCard(
                title = "功耗 W",
                color = MaterialTheme.colorScheme.tertiary,
                stats = if (valid.isNotEmpty()) listOf(
                    "MAX" to "%.1f".format(valid.max()),
                    "MIN" to "%.1f".format(valid.min()),
                    "AVG" to "%.1f".format(valid.average()),
                ) else emptyList(),
                values = if (valid.isNotEmpty())
                    watts.map { if (it.isFinite()) it.toFloat().coerceAtLeast(0f) else 0f }
                else emptyList(),
                minY = 0f,
                emptyHint = if (valid.isEmpty()) "功耗数据不可用" else null,
            )
        }

        // ── 7) 电量 ──
        item {
            MetricChartCard(
                title = "电量 %",
                raw = pts.map { it.battPct },
                color = MaterialTheme.colorScheme.secondary,
                percent = true,
            )
        }

        // ── 8) 线程负载入口(M3): 有数据可点进二级页; 无数据置灰提示 ──
        item {
            val hasThreads = s.threads.isNotEmpty()
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (hasThreads) Modifier.clickable { onOpenThreads(s.id) }
                            else Modifier
                        )
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "线程负载",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (hasThreads) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (hasThreads) "共 ${s.threads.size} 条线程 · 负载/主核/迁移/亲和"
                            else "本次未采集线程数据(需要 Root)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (hasThreads) {
                        Text(
                            "→",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(96.dp)) }
    }
}

/** Int 序列型指标卡(CPU/GPU/温度/电量共用): 自动统计 MAX/MIN/AVG, 全无效则显示提示。 */
@Composable
private fun MetricChartCard(
    title: String,
    raw: List<Int>,
    color: Color,
    percent: Boolean,
    invalidBelow: Int = 0,   // 小于该值视为无效(百分比类 -1 无效; 温度传 -100)
) {
    val valid = raw.filter { it >= invalidBelow }
    val available = valid.isNotEmpty()
    ChartCard(
        title = title,
        color = color,
        stats = if (available) listOf(
            "MAX" to "${valid.max()}",
            "MIN" to "${valid.min()}",
            "AVG" to "%.1f".format(valid.average()),
        ) else emptyList(),
        values = if (available) raw.map { it.coerceAtLeast(invalidBelow.coerceAtLeast(0)).toFloat() }
                 else emptyList(),
        minY = if (percent) 0f else null,
        maxY = if (percent) 100f else null,
        emptyHint = if (available) null else "数据不可用",
    )
}

/** 图表卡骨架: 标题 + 统计行 + 折线图 / 空态提示。 */
@Composable
private fun ChartCard(
    title: String,
    color: Color,
    stats: List<Pair<String, String>>,
    values: List<Float>,
    minY: Float? = null,
    maxY: Float? = null,
    footer: String? = null,
    emptyHint: String? = null,
) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (stats.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    stats.forEach { (label, value) ->
                        ChartStat(label, value, Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (emptyHint != null || values.size < 2) {
                Box(
                    Modifier.fillMaxWidth().height(72.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        emptyHint ?: "数据点不足",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LineChart(
                    values = values,
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    color = color,
                    minY = minY,
                    maxY = maxY,
                )
            }
            if (footer != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun fmtInt(v: Int): String = if (v > 0) "$v" else "--"

private fun fmtFullDate(ms: Long): String =
    runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    }.getOrDefault("--")

private fun fmtDurationText(ms: Long): String {
    val sec = (ms / 1000).toInt()
    return when {
        sec >= 3600 -> "${sec / 3600}时${sec % 3600 / 60}分${sec % 60}秒"
        sec >= 60 -> "${sec / 60}分${sec % 60}秒"
        else -> "${sec}秒"
    }
}
