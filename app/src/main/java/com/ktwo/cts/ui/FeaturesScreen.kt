// 「功能」聚合入口: 把 监控 / 记录 / 应用 / 配置 / 智能调度高级设置 收进一个 tab。
package com.ktwo.cts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ktwo.cts.data.Prefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturesScreen(
    onOpenPerfMon: () -> Unit = {},
    onOpenMonitor: () -> Unit = {},
    onOpenRecords: () -> Unit = {},
    onOpenApps: () -> Unit = {},
    onOpenCpu: () -> Unit = {},
    onOpenAutoMode: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val proUnlocked by Prefs.proUnlockedFlow(ctx).collectAsState(initial = false)
    var showOverlaySheet by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = { Text("功能") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
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
            SectionTitle("实时")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    FeatureRow(
                        Icons.Outlined.Layers, "悬浮窗管理器",
                        "六个独立监视悬浮窗(负载/进程/线程/迷你/帧率/温度), 可同时开、各自拖动",
                        onClick = { showOverlaySheet = true },
                    )
                    HorizontalDivider()
                    FeatureRow(
                        Icons.Outlined.Speed, "性能监控 · 大屏",
                        "应用内实时仪表盘: CPU/GPU/温度/帧率/功耗/内存",
                        onClick = onOpenPerfMon,
                    )
                    HorizontalDivider()
                    FeatureRow(
                        Icons.Outlined.Insights, "智能监控",
                        "智能调度实时状态: FSM/学习/温度/负载/频率",
                        onClick = onOpenMonitor,
                    )
                    HorizontalDivider()
                    FeatureRow(
                        Icons.Outlined.History, "记录",
                        "充放电历史、功耗统计与续航估算",
                        onClick = onOpenRecords,
                    )
                }
            }

            SectionTitle("配置")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    FeatureRow(
                        Icons.Outlined.Apps, "应用",
                        "为每个应用单独指定调度档位",
                        onClick = onOpenApps,
                    )
                    HorizontalDivider()
                    FeatureRow(
                        Icons.Outlined.Memory, "配置",
                        "CPU 频率/调速器/GPU/核心 精细控制",
                        onClick = onOpenCpu,
                    )
                    HorizontalDivider()
                    FeatureRow(
                        Icons.Outlined.Tune, "智能调度高级设置",
                        if (proUnlocked) "自动模式开关 · 省电/夜间/温控/系统优化"
                        else "智能版功能 · 省电/夜间/温控/系统优化",
                        locked = !proUnlocked,
                        onClick = onOpenAutoMode,
                    )
                }
            }

            Spacer(Modifier.height(96.dp))
        }
    }

    if (showOverlaySheet) {
        OverlayManagerSheet(onDismiss = { showOverlaySheet = false })
    }
}

@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    desc: String,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium)
                if (locked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Outlined.Lock, contentDescription = "需解锁",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
