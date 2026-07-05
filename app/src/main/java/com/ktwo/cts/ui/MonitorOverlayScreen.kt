// 性能监控页(仿 Metric): 顶部实时大屏 + 悬浮监控窗设置(开关/显示项/透明度/字号)。
package com.ktwo.cts.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.sp
import com.ktwo.cts.data.MetricReader
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.service.MonitorOverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ALL_ITEMS = listOf(
    "fps" to "帧率", "cpu" to "CPU频率", "cpuload" to "CPU占用",
    "gpu" to "GPU频率", "gpuload" to "GPU占用", "temp" to "温度",
    "batt" to "电量", "power" to "功耗", "ram" to "内存",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MonitorOverlayScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by Prefs.monEnabledFlow(ctx).collectAsState(initial = false)
    val itemsCsv by Prefs.monItemsFlow(ctx).collectAsState(initial = Prefs.MON_ITEMS_DEFAULT)
    val opacity by Prefs.monOpacityFlow(ctx).collectAsState(initial = 85)
    val textSize by Prefs.monTextSizeFlow(ctx).collectAsState(initial = 12)
    val selected = remember(itemsCsv) { itemsCsv.split(",").filter { it.isNotBlank() }.toSet() }

    var hasOverlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    val cellCount by Prefs.cellCountFlow(ctx).collectAsState(initial = 1)

    // 实时快照(应用内大屏)
    var snap by remember { mutableStateOf<MetricReader.Snapshot?>(null) }
    LaunchedEffect(cellCount) {
        while (true) {
            snap = withContext(Dispatchers.IO) { runCatching { MetricReader.snapshot(cellCount) }.getOrNull() }
            delay(1000)
        }
    }

    val overlayPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlay = Settings.canDrawOverlays(ctx)
        // 授权返回后若已获权, 直接开启(用户点开关的本意就是要开)
        if (hasOverlay) scope.launch {
            Prefs.setMonEnabled(ctx, true)
            MonitorOverlayService.start(ctx)
        }
    }

    fun restartIfOn() {
        scope.launch {
            if (Prefs.monEnabled(ctx)) {
                MonitorOverlayService.stop(ctx)
                delay(120)
                MonitorOverlayService.start(ctx)
            }
        }
    }

    fun toggleOverlay(on: Boolean) {
        if (on && !Settings.canDrawOverlays(ctx)) {
            overlayPermLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            )
            return
        }
        scope.launch {
            Prefs.setMonEnabled(ctx, on)
            if (on) MonitorOverlayService.start(ctx) else MonitorOverlayService.stop(ctx)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("性能监控") },
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 实时大屏 ──
            SectionTitle("实时状态")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                val s = snap
                FlowRow(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatTile("帧率", if (s == null || s.fps < 0) "--" else "${s.fps}", "FPS")
                    StatTile("CPU", if (s == null) "--" else (s.clusters.maxOfOrNull { it.mhz }?.takeIf { it > 0 }?.toString() ?: "--"), "MHz")
                    StatTile("CPU占用", if (s == null || s.cpuLoadPct < 0) "--" else "${s.cpuLoadPct}", "%")
                    StatTile("GPU", if (s == null || s.gpuMhz < 0) "--" else "${s.gpuMhz}", "MHz")
                    StatTile("GPU占用", if (s == null || s.gpuLoadPct < 0) "--" else "${s.gpuLoadPct}", "%")
                    StatTile("温度", if (s == null || s.tempC <= -100) "--" else "${s.tempC}", "℃")
                    StatTile("电量", if (s == null || s.battPct < 0) "--" else "${s.battPct}", "%")
                    StatTile("功耗", if (s == null) "--" else String.format("%.1f", s.watt), "W")
                    StatTile("内存", if (s == null || s.ramTotalGb <= 0) "--" else String.format("%.1f", s.ramUsedGb), "GB")
                }
            }
            // 每簇频率
            snap?.clusters?.takeIf { it.isNotEmpty() }?.let { cs ->
                TonalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("CPU 各簇频率", fontWeight = FontWeight.Medium)
                        cs.forEach { c ->
                            Row(Modifier.fillMaxWidth()) {
                                Text(c.name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (c.mhz > 0) "${c.mhz} MHz" else "--",
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }

            // ── 悬浮窗设置 ──
            SectionTitle("悬浮监控窗")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("悬浮性能窗", fontWeight = FontWeight.Bold)
                            Text(
                                if (!hasOverlay) "需要「显示在其他应用上层」权限, 点开关去授权"
                                else if (enabled) "已开启 · 可直接拖动浮窗到任意位置"
                                else "在任意应用上层实时显示所选指标(可拖动)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = enabled && hasOverlay, onCheckedChange = { toggleOverlay(it) })
                    }

                    HorizontalDivider()
                    Text("显示项", fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ALL_ITEMS.forEach { (key, label) ->
                            val on = key in selected
                            FilterChip(
                                selected = on,
                                onClick = {
                                    val next = selected.toMutableSet().apply { if (on) remove(key) else add(key) }
                                    // 至少保留一项, 按固定顺序落盘
                                    val csv = ALL_ITEMS.map { it.first }.filter { it in next }.joinToString(",")
                                        .ifBlank { "fps" }
                                    scope.launch { Prefs.setMonItems(ctx, csv); restartIfOn() }
                                },
                                label = { Text(label) },
                                shape = RoundedCornerShape(12.dp),
                            )
                        }
                    }

                    HorizontalDivider()
                    Text("透明度: ${opacity}%", fontWeight = FontWeight.Medium)
                    Slider(
                        value = opacity.toFloat(),
                        onValueChange = { v -> scope.launch { Prefs.setMonOpacity(ctx, v.toInt()) } },
                        onValueChangeFinished = { restartIfOn() },
                        valueRange = 20f..100f,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    HorizontalDivider()
                    Text("字号: ${textSize}sp", fontWeight = FontWeight.Medium)
                    Slider(
                        value = textSize.toFloat(),
                        onValueChange = { v -> scope.launch { Prefs.setMonTextSize(ctx, v.toInt()) } },
                        onValueChangeFinished = { restartIfOn() },
                        valueRange = 9f..22f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, unit: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
        modifier = Modifier.widthIn(min = 96.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}
