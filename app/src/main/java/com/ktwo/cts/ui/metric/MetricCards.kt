package com.ktwo.cts.ui.metric

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ktwo.cts.data.MetricReader
import com.ktwo.cts.data.ProcSampler
import com.ktwo.cts.ui.theme.MetricTokens
import java.util.Locale

/** Metric 主页四块卡片。全部只依赖传入数据, 采样在 HomeDashboard 里统一做。 */

private fun gb1(v: Double): String = String.format(Locale.US, "%.1fGB", v)

/** Metric 白底圆角卡容器(仅 METRIC 分支使用, 固定浅色观感)。 */
@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MetricTokens.CardShape,
        color = MetricTokens.CardBg,
        contentColor = MetricTokens.TextDark,
        shadowElevation = MetricTokens.CardElevation,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** 1) 内存卡: 左橙色环形(中心百分比+Memory), 右物理内存/交换分区两条进度, 底部灰字汇总。 */
@Composable
fun MemoryCard(snap: MetricReader.Snapshot?, modifier: Modifier = Modifier) {
    val ramPct = snap?.ramUsedPct ?: -1
    val swapPct = snap?.swapUsedPct ?: -1
    MetricCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(
                progress = if (ramPct >= 0) ramPct / 100f else 0f,
                diameter = 96.dp,
                color = MetricTokens.RingOrange,
                centerTop = if (ramPct >= 0) "$ramPct%" else "--",
                centerBottom = "Memory",
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledBar(
                    label = "物理内存",
                    pct = ramPct.coerceAtLeast(0),
                    valueText = if (ramPct >= 0) "$ramPct% (${gb1(snap!!.ramUsedGb)})" else "--",
                    color = MetricTokens.RingOrange,
                )
                LabeledBar(
                    label = "交换分区",
                    pct = swapPct.coerceAtLeast(0),
                    valueText = if (swapPct >= 0) "$swapPct% (${gb1(snap!!.swapUsedGb)})" else "--",
                    color = MetricTokens.BarBlue,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val usedSum = (if (ramPct >= 0) ramPct else 0) + (if (swapPct >= 0) swapPct else 0)
        val swapCached = snap?.swapCachedMb ?: -1
        Text(
            "Used $usedSum%   SwapCached ${if (swapCached >= 0) "${swapCached}MB" else "--"}",
            style = MaterialTheme.typography.bodySmall,
            color = MetricTokens.TextGrey,
        )
    }
}

private fun loadColor(pct: Int): Color = when {
    pct < 0 -> MetricTokens.RingGrey
    pct < 40 -> MetricTokens.Accent
    pct < 70 -> MetricTokens.RingOrange
    else -> Color(0xFFE5484D)
}

/** 2) GPU 卡: 左环形(进度=负载, 颜色随负载), 右大号频率 + 负载 + 三行静态灰字。 */
@Composable
fun GpuCard(
    snap: MetricReader.Snapshot?,
    infoLines: List<String>,
    modifier: Modifier = Modifier,
) {
    val load = snap?.gpuLoadPct ?: -1
    val mhz = snap?.gpuMhz ?: -1
    MetricCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingGauge(
                progress = if (load >= 0) load / 100f else 0f,
                diameter = 96.dp,
                color = loadColor(load),
                centerTop = if (load >= 0) "$load%" else "--",
                centerBottom = "GPU",
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (mhz >= 0) "${mhz}MHz" else "--MHz",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MetricTokens.TextDark,
                )
                Text(
                    "负载：${if (load >= 0) "$load%" else "--"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MetricTokens.TextDark,
                )
                Spacer(Modifier.height(6.dp))
                infoLines.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MetricTokens.TextGrey,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 3-左) 进程列表卡: TOP5 进程(圆点+进程名+右侧 CPU%)。无 root 显示提示。 */
@Composable
fun ProcessListCard(
    procs: List<ProcSampler.Proc>,
    rooted: Boolean,
    modifier: Modifier = Modifier,
) {
    val dotColors = listOf(
        MetricTokens.Accent, MetricTokens.RingOrange, Color(0xFF34C77B),
        Color(0xFFB067E8), MetricTokens.BarBlue,
    )
    MetricCard(modifier) {
        Text(
            "进程",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MetricTokens.TextDark,
        )
        Spacer(Modifier.height(10.dp))
        if (procs.isEmpty()) {
            Text(
                if (rooted) "采样中…" else "需要 Root",
                style = MaterialTheme.typography.bodySmall,
                color = MetricTokens.TextGrey,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                procs.take(5).forEachIndexed { i, p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(dotColors[i % dotColors.size], CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MetricTokens.TextDark,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            String.format(Locale.US, "%.0f%%", p.cpuPct),
                            style = MaterialTheme.typography.bodySmall,
                            color = MetricTokens.TextGrey,
                        )
                    }
                }
            }
        }
    }
}

/** 3-右) CPU 卡: 右上角温度 + 中间负载直方图 + 芯片名 + 负载。 */
@Composable
fun CpuCard(
    snap: MetricReader.Snapshot?,
    history: List<Float>,
    chipLine: String,
    modifier: Modifier = Modifier,
) {
    val temp = snap?.tempC ?: -999
    val load = snap?.cpuLoadPct ?: -1
    MetricCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "CPU",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MetricTokens.TextDark,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (temp > -100) "$temp℃" else "--℃",
                style = MaterialTheme.typography.bodySmall,
                color = MetricTokens.TextGrey,
            )
        }
        Spacer(Modifier.height(10.dp))
        BarHistogram(
            values = history,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            barColor = MetricTokens.Accent,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            chipLine,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MetricTokens.TextDark,
            maxLines = 1,
        )
        Text(
            "负载：${if (load >= 0) "$load%" else "--"}",
            style = MaterialTheme.typography.bodySmall,
            color = MetricTokens.TextGrey,
        )
    }
}

/** 4) 每核(簇)网格卡: 2 列, 每格 百分比 + 迷你柱状 + 当前 MHz + min~max。 */
@Composable
fun ClusterGridCard(
    snap: MetricReader.Snapshot?,
    minMax: List<Pair<Int, Int>>,
    histories: Map<Int, List<Float>>,
    modifier: Modifier = Modifier,
) {
    val clusters = snap?.clusters.orEmpty()
    MetricCard(modifier) {
        if (clusters.isEmpty()) {
            Text(
                "读取 CPU 频率中…",
                style = MaterialTheme.typography.bodySmall,
                color = MetricTokens.TextGrey,
            )
            return@MetricCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            clusters.indices.chunked(2).forEach { rowIdx ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowIdx.forEach { i ->
                        ClusterCell(
                            cluster = clusters[i],
                            range = minMax.getOrNull(i) ?: (0 to 0),
                            history = histories[i].orEmpty(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowIdx.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ClusterCell(
    cluster: MetricReader.Cluster,
    range: Pair<Int, Int>,
    history: List<Float>,
    modifier: Modifier = Modifier,
) {
    val (mn, mx) = range
    val pct = if (mx > 0 && cluster.mhz > 0) (cluster.mhz * 100 / mx).coerceIn(0, 100) else -1
    Column(
        modifier
            .background(MetricTokens.Bg, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (pct >= 0) "$pct%" else "--",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MetricTokens.TextDark,
                modifier = Modifier.weight(1f),
            )
            BarHistogram(
                values = history,
                modifier = Modifier.width(56.dp).height(20.dp),
                barColor = MetricTokens.Accent,
                maxBars = 14,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (cluster.mhz > 0) "${cluster.mhz}MHz" else "--MHz",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MetricTokens.TextDark,
            maxLines = 1,
        )
        Text(
            if (mx > 0) "$mn~${mx}MHz" else cluster.name,
            style = MaterialTheme.typography.bodySmall,
            color = MetricTokens.TextGrey,
            maxLines = 1,
        )
    }
}
