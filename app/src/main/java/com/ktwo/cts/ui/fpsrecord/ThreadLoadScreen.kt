// M3 线程负载详情页: 展示某次帧率录制期间前台应用各线程的负载/主核/迁移/亲和统计,
// 每条线程一张玻璃卡(名称+tid、平均/峰值、主核占比、迁移与亲和变更、负载迷你折线图)。
package com.ktwo.cts.ui.fpsrecord

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
import com.ktwo.cts.data.fpsrecord.ThreadLoad
import com.ktwo.cts.ui.TonalCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadLoadScreen(
    id: String,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    var appName by remember { mutableStateOf("") }
    var threads by remember { mutableStateOf<List<ThreadLoad>>(emptyList()) }

    LaunchedEffect(id) {
        val s = withContext(Dispatchers.IO) {
            runCatching { FpsRecordStore.load(ctx, id) }.getOrNull()
        }
        appName = s?.appName.orEmpty()
        threads = s?.threads.orEmpty()
        loaded = true
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("线程负载") },
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
        when {
            !loaded -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            threads.isEmpty() -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "无线程负载数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "需要 Root 且录制时长足够(≥4 秒)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> ThreadList(appName, threads, Modifier.fillMaxSize().padding(inner))
        }
    }
}

@Composable
private fun ThreadList(appName: String, threads: List<ThreadLoad>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                buildString {
                    if (appName.isNotEmpty()) append(appName).append(" · ")
                    append("负载 TOP ").append(threads.size).append(" 线程(每 2 秒采样)")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        items(count = threads.size, key = { threads[it].tid }) { i ->
            ThreadCard(threads[i])
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ThreadCard(t: ThreadLoad) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            // 线程名 + tid
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.name.ifEmpty { "线程 ${t.tid}" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "tid ${t.tid}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            // 统计行: 平均/峰值/主核
            Row(Modifier.fillMaxWidth()) {
                ChartStat("平均", fmtPct(t.avgLoad), Modifier.weight(1f))
                ChartStat("峰值", fmtPct(t.maxLoad), Modifier.weight(1f))
                ChartStat(
                    "主核",
                    if (t.dominantCore >= 0) "CPU${t.dominantCore} ${t.dominantCorePct}%" else "--",
                    Modifier.weight(1.4f),
                )
            }
            Spacer(Modifier.height(10.dp))
            // 负载迷你折线图(单核口径 0..100%)
            if (t.loadPoints.size >= 2) {
                LineChart(
                    values = t.loadPoints,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    color = MaterialTheme.colorScheme.primary,
                    minY = 0f,
                    maxY = 100f,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "迁移 ${t.migrationCount} 次 · 亲和变更 ${t.affinityChanges} 次",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun fmtPct(v: Float): String =
    if (v.isFinite() && v >= 0f) "%.1f%%".format(v) else "--"
