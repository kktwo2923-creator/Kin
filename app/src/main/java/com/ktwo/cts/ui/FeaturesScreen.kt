// 「功能」聚合入口, 对齐 Metric 功能页组织: 分组卡片(设备监测/设备调优),
// 条目统一为「图标 + 标题 + 一行说明 + 尾部指示」; Pro 项整行降透明度 + PRO 徽章。
package com.ktwo.cts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timeline
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
    onOpenFpsRecord: () -> Unit = {},
    onOpenProcess: () -> Unit = {},
    onOpenThreadAlloc: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenGovernor: () -> Unit = {},
    onOpenColorOs: () -> Unit = {},
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
            // —— 分组一: 设备监测(对齐 Metric feature_page_group_title) ——
            // 顺序按使用频率: 记录/监控类高频入口在前, 详情/历史类在后。
            FeatureGroup("设备监测") {
                // 帧率记录暂时下线(录制还没修好, 等以后再搞): 隐藏入口, 代码/路由保留。
                FeatureRow(
                    Icons.Outlined.Speed, "性能监控",
                    "应用内实时大屏仪表盘: CPU/GPU/温度/帧率/功耗/内存",
                    onClick = onOpenPerfMon,
                )
                RowDivider()
                FeatureRow(
                    Icons.Outlined.Layers, "悬浮窗管理器",
                    "五个独立监视悬浮窗(负载/进程/线程/迷你/温度), 展开选择",
                    expands = true, // 弹出底部面板而非跳页, 尾部用向下箭头提示
                    onClick = { showOverlaySheet = true },
                )
                RowDivider()
                FeatureRow(
                    Icons.Outlined.Insights, "智能监控",
                    "智能调度实时状态: FSM/学习/温度/负载/频率",
                    onClick = onOpenMonitor,
                )
                RowDivider()
                FeatureRow(
                    Icons.AutoMirrored.Outlined.ListAlt, "进程管理器",
                    "按应用分组查看进程 CPU/内存, 结束进程或强制停止应用",
                    onClick = onOpenProcess,
                )
                RowDivider()
                FeatureRow(
                    Icons.Outlined.Memory, "内存详情",
                    "物理内存/ZRAM/交换/VM 参数",
                    onClick = onOpenMemory,
                )
                RowDivider()
                FeatureRow(
                    Icons.Outlined.History, "电量记录",
                    "充放电历史、功耗统计与续航估算",
                    onClick = onOpenRecords,
                )
            }

            // —— 分组二: 设备调优(对齐 Metric feature_tuning_group_title) ——
            // 顺序: 面向应用的整体调度在前, 精细参数其次, Pro 项收尾。
            FeatureGroup("设备调优") {
                FeatureRow(
                    Icons.Outlined.Apps, "应用调度",
                    "为每个应用单独指定调度档位",
                    onClick = onOpenApps,
                )
                RowDivider()
                FeatureRow(
                    Icons.Outlined.DeveloperBoard, "性能配置",
                    "CPU 频率/调速器/GPU/核心 精细控制",
                    onClick = onOpenCpu,
                )
                RowDivider()
                FeatureRow(
                    Icons.Outlined.Tune, "调速器参数",
                    "每簇 governor 可调参数精细编辑",
                    onClick = onOpenGovernor,
                )
                RowDivider()
                FeatureRow(
                    Icons.Outlined.Bolt, "ColorOS 专属",
                    "OnePlus/OPPO 满血·游戏触控·温控",
                    onClick = onOpenColorOs,
                )
                RowDivider()
                FeatureRow(
                    Icons.Outlined.AccountTree, "线程分配",
                    if (proUnlocked) "把游戏/应用的渲染线程归拢到大核, 提升流畅度"
                    else "智能版功能 · 渲染线程归拢到大核",
                    locked = !proUnlocked,
                    onClick = onOpenThreadAlloc,
                )
                RowDivider()
                FeatureRow(
                    Icons.Outlined.AutoAwesome, "智能调度高级设置",
                    if (proUnlocked) "自动模式开关 · 省电/夜间/温控/系统优化"
                    else "智能版功能 · 省电/夜间/温控/系统优化",
                    locked = !proUnlocked,
                    onClick = onOpenAutoMode,
                )
            }

            Spacer(Modifier.height(96.dp))
        }
    }

    if (showOverlaySheet) {
        OverlayManagerSheet(onDismiss = { showOverlaySheet = false })
    }
}

/** 分组 = 组标题 + 一张卡片, 标题与卡片间 8dp, 组与组之间由父级 spacedBy(16dp) 控制。 */
@Composable
private fun FeatureGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        TonalCard(modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

/** 组内分隔线: 起点缩进到与标题文字对齐(16 边距 + 24 图标 + 12 间距)。 */
@Composable
private fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 52.dp, end = 16.dp))
}

/**
 * 功能入口行: 图标 + 标题 + 一行说明 + 尾部指示。
 * - expands = true 表示点击弹出面板(尾部向下箭头), 否则为跳页(右箭头)。
 * - locked = true 时整行降透明度并在标题后加 PRO 徽章(点击仍可进入, 由目标页处理解锁)。
 */
@Composable
private fun FeatureRow(
    icon: ImageVector,
    title: String,
    desc: String,
    locked: Boolean = false,
    expands: Boolean = false,
    onClick: () -> Unit,
) {
    val contentAlpha = if (locked) 0.6f else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                )
                if (locked) {
                    Spacer(Modifier.width(6.dp))
                    ProBadge()
                }
            }
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (expands) Icons.Outlined.KeyboardArrowDown
            else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 智能版专属徽章(不降透明度, 作为付费引导强调)。 */
@Composable
private fun ProBadge() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Text(
            "PRO",
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
