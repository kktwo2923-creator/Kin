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
import com.ktwo.cts.ui.metric.HomeDashboard
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

    // 实时大屏改用 HomeDashboard(内部自采样), 这里只需喂一次屏幕刷新率兜底给 FPS 采样。
    LaunchedEffect(Unit) {
        @Suppress("DEPRECATION")
        runCatching {
            MetricReader.fallbackHz =
                (ctx.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay.refreshRate.toInt()
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
            // ── 实时大屏: Metric 同款仪表盘(内存环/GPU环/进程+CPU直方图/每簇网格) ──
            // 复用主页的 HomeDashboard, 内部自采样, 与 Metric 主页布局一致。
            SectionTitle("实时状态")
            HomeDashboard(Modifier.fillMaxWidth())

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
