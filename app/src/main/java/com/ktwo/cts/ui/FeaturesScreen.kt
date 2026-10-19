// 「功能」聚合入口: 双列网格瓦片(智能调度/监测/调优)。
// 常规/电池/采样/权限等偏好开关已迁至关于 tab 的设置子页。
package com.ktwo.cts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Layers
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ktwo.cts.data.Prefs

/** 网格瓦片的数据描述: 一个功能入口。 */
private data class FeatureItem(
    val icon: ImageVector,
    val title: String,
    val desc: String,
    val locked: Boolean = false,
    val expands: Boolean = false,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturesScreen(
    onOpenPerfMon: () -> Unit = {},
    onOpenMonitor: () -> Unit = {},
    onOpenRecords: () -> Unit = {},
    onOpenApps: () -> Unit = {},
    onOpenCpu: () -> Unit = {},
    onOpenAutoMode: () -> Unit = {},
    onOpenSmartPolicy: () -> Unit = {},
    onOpenFpsRecord: () -> Unit = {},
    onOpenThreadAlloc: () -> Unit = {},
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
            // —— 分组一: 智能调度(含其设置), 置顶 ——
            FeatureGrid(
                "智能调度",
                listOf(
                    FeatureItem(
                        Icons.Outlined.AutoAwesome, "智能调度设置",
                        if (proUnlocked) "自动模式开关 · 省电/夜间/温控/系统优化"
                        else "智能版功能 · 省电/夜间/温控/系统优化",
                        locked = !proUnlocked,
                        onClick = onOpenAutoMode,
                    ),
                    FeatureItem(
                        Icons.Outlined.Tune, "智能策略",
                        if (proUnlocked) "问答调优 · 六状态频率上限一目了然"
                        else "智能版功能 · 问答调优/状态频率策略",
                        locked = !proUnlocked,
                        onClick = onOpenSmartPolicy,
                    ),
                    FeatureItem(
                        Icons.Outlined.Insights, "智能监控",
                        "智能调度实时状态: FSM/学习/温度/负载/频率",
                        onClick = onOpenMonitor,
                    ),
                ),
            )

            // —— 分组二: 设备监测 ——
            // 帧率记录暂时下线(录制还没修好, 等以后再搞): 隐藏入口, 代码/路由保留。
            // 进程管理器/内存详情入口按需求下线, 页面与路由保留。
            FeatureGrid(
                "设备监测",
                listOf(
                    FeatureItem(
                        Icons.Outlined.Speed, "性能监控",
                        "应用内实时大屏仪表盘: CPU/GPU/温度/帧率/功耗/内存",
                        onClick = onOpenPerfMon,
                    ),
                    FeatureItem(
                        Icons.Outlined.Layers, "悬浮窗管理器",
                        "五个独立监视悬浮窗, 展开选择",
                        expands = true, // 弹出底部面板而非跳页, 尾部用向下箭头提示
                        onClick = { showOverlaySheet = true },
                    ),
                    FeatureItem(
                        Icons.Outlined.History, "电量记录",
                        "充放电历史、功耗统计与续航估算",
                        onClick = onOpenRecords,
                    ),
                ),
            )

            // —— 分组三: 设备调优 ——
            // 调速器参数入口整合进「性能配置」页内, 不再单列。
            FeatureGrid(
                "设备调优",
                listOf(
                    FeatureItem(
                        Icons.Outlined.Apps, "应用策略",
                        "为每个应用单独指定调度档位",
                        onClick = onOpenApps,
                    ),
                    FeatureItem(
                        Icons.Outlined.DeveloperBoard, "性能配置",
                        "CPU 频率/调速器/GPU/核心, 含调速器参数编辑",
                        onClick = onOpenCpu,
                    ),
                    FeatureItem(
                        Icons.Outlined.Bolt, "ColorOS 专属",
                        "OnePlus/OPPO 满血·游戏触控·温控",
                        onClick = onOpenColorOs,
                    ),

                ),
            )

            Spacer(Modifier.height(96.dp))
        }
    }

    if (showOverlaySheet) {
        OverlayManagerSheet(onDismiss = { showOverlaySheet = false })
    }
}

/** 分组 = 组标题 + 双列网格, 标题与网格间 8dp, 组与组之间由父级 spacedBy(16dp) 控制。 */
@Composable
private fun FeatureGrid(title: String, items: List<FeatureItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        items.chunked(2).forEach { rowItems ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { item -> FeatureTile(item) }
                // 奇数个时末行补一个等宽空位, 保持两列对齐。
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 功能入口瓦片(双列网格单元): 图标 + 尾部指示 / 标题 / 一行说明。
 * - expands = true 表示点击弹出面板(尾部向下箭头), 否则为跳页(右箭头)。
 * - locked = true 时内容降透明度并在标题后加 PRO 徽章(点击仍可进入, 由目标页处理解锁)。
 */
@Composable
private fun RowScope.FeatureTile(item: FeatureItem) {
    val contentAlpha = if (item.locked) 0.6f else 1f
    TonalCard(modifier = Modifier.weight(1f).fillMaxHeight()) {
        Column(
            Modifier
                .fillMaxSize()
                .clickable(onClick = item.onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    item.icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (item.expands) Icons.Outlined.KeyboardArrowDown
                    else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // weight(fill=false): 标题过长时自己省略, 不把 PRO 徽章挤到竖排换行。
                Text(
                    item.title, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.locked) {
                    Spacer(Modifier.width(6.dp))
                    ProBadge()
                }
            }
            Text(
                item.desc,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
        }
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
            maxLines = 1,
            softWrap = false,   // 空间不足时也绝不竖排折行("PR/O")
        )
    }
}
