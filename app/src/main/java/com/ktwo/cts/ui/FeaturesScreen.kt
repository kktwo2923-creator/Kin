// 「功能」聚合入口: 顶部常规/电池/采样/权限 PrefGroup + 下方双列网格瓦片(智能调度/监测/调优)。
// 常规/电池/采样/权限从原设置页迁入(功能行为开关归功能 tab, App 级偏好在关于 tab 的设置子页)。
package com.ktwo.cts.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
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
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.service.ForegroundDetector
import kotlinx.coroutines.*

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
    val scope = rememberCoroutineScope()
    val proUnlocked by Prefs.proUnlockedFlow(ctx).collectAsState(initial = false)
    var showOverlaySheet by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // —— 从原设置页迁入的状态(常规/电池/采样/权限) ——
    val autostart by Prefs.autostartFlow(ctx).collectAsState(initial = true)
    val notifyEnabled by Prefs.notifyEnabledFlow(ctx).collectAsState(initial = false)
    val moreLogs by Prefs.moreLogsFlow(ctx).collectAsState(initial = false)
    val refreshRate by Prefs.refreshRateFlow(ctx).collectAsState(initial = 0)
    val cellCount by Prefs.cellCountFlow(ctx).collectAsState(initial = 1)
    var showCalibrate by remember { mutableStateOf(false) }
    var hasUsagePerm by remember { mutableStateOf(false) }
    var isRoot by remember { mutableStateOf(true) }
    var a11yEnabled by remember { mutableStateOf(false) }
    var batteryWhitelisted by remember { mutableStateOf(false) }
    var thirdParty by remember { mutableStateOf(false) }
    val thirdPartyGen = remember { androidx.compose.runtime.mutableIntStateOf(0) }

    fun refreshPerms() = scope.launch {
        a11yEnabled = com.ktwo.cts.service.AppSwitchService.isEnabled(ctx)
        run {
            val gen = thirdPartyGen.intValue
            val v = withContext(Dispatchers.IO) {
                runCatching {
                    com.ktwo.cts.data.TakeoverCfg.isThirdParty(com.ktwo.cts.data.KinSettings.merged())
                }.getOrDefault(false)
            }
            if (thirdPartyGen.intValue == gen) thirdParty = v
        }
        batteryWhitelisted = runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .isIgnoringBatteryOptimizations(ctx.packageName)
        }.getOrDefault(false)
        hasUsagePerm = runCatching { ForegroundDetector(ctx).hasUsageStatsPermission() }.getOrDefault(false)
        isRoot = withContext(Dispatchers.IO) { RootShell.isRooted() }
    }

    fun enableNotify() = scope.launch {
        Prefs.setNotifyEnabled(ctx, true)
        com.ktwo.cts.service.NotifyService.start(ctx)
        if (!Settings.canDrawOverlays(ctx)) {
            runCatching {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${ctx.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> enableNotify() }
    fun toggleNotify(on: Boolean) {
        if (on) {
            if (android.os.Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else enableNotify()
        } else scope.launch {
            Prefs.setNotifyEnabled(ctx, false)
            com.ktwo.cts.service.NotifyService.stop(ctx)
        }
    }

    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        refreshPerms()
        onPauseOrDispose { }
    }

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
            // —— 常规(从原设置页迁入: 服务行为开关) ——
            PrefGroup("常规") {
                PrefToggleRow(
                    title = "开机自启动",
                    desc = "开机后自动拉起常驻通知/兜底调度(无障碍服务由系统在开机后自动恢复)",
                    checked = autostart,
                    onCheckedChange = { scope.launch { Prefs.setAutostart(ctx, it) } },
                )
                PrefDivider()
                PrefToggleRow(
                    title = "状态栏常驻通知",
                    desc = "显示当前模式 + 电量;点击在任意应用上弹出快切悬浮窗(需通知 + 悬浮窗权限)",
                    checked = notifyEnabled,
                    onCheckedChange = { toggleNotify(it) },
                )
                PrefDivider()
                PrefToggleRow(
                    title = "更多日志",
                    desc = "日志页默认只显示 运行/模式/前台 三个日志;开启后显示全部日志文件",
                    checked = moreLogs,
                    onCheckedChange = { scope.launch { Prefs.setMoreLogs(ctx, it) } },
                )
                PrefDivider()
                PrefToggleRow(
                    title = "第三方接管(Scene 等)",
                    desc = "交给第三方调度管理器切档: 停用应用画像/智能分类, 仅保留基础模式(省电/均衡/性能/风驰)",
                    checked = thirdParty,
                    onCheckedChange = { want ->
                        thirdPartyGen.intValue
                        thirdParty = want
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                val ok = com.ktwo.cts.data.KinSettings.updateOverlay(ctx) { r ->
                                    com.ktwo.cts.data.TakeoverCfg.mergeInto(r, want)
                                }
                                if (ok) Prefs.setPendingTakeover(ctx, -1)
                            }
                        }
                    },
                )
                PrefDivider()
                PrefNavRow(
                    title = "重新运行初始化引导",
                    desc = "重走 ROOT 授权/模块检测(含模块下载入口)/无障碍/接管方式",
                    onClick = { scope.launch { Prefs.setSetupDone(ctx, false) } },
                )
            }

            // —— 电池(从原设置页迁入) ——
            PrefGroup("电池") {
                PrefNavRow(
                    title = "重新矫正电池",
                    desc = "当前: ${if (cellCount >= 2) "双电芯" else "单电芯"} · 功率偏差一倍时来这里改",
                    onClick = { showCalibrate = true },
                )
            }

            // —— 采样(从原设置页迁入) ——
            PrefGroup("采样") {
                PrefChipsRow(
                    title = "屏幕刷新率",
                    desc = "锁定后跨重启保持(root 写系统设置)。自动 = 交还系统自适应。",
                ) {
                    listOf(0 to "自动", 60 to "60Hz", 90 to "90Hz", 120 to "120Hz",
                           144 to "144Hz", 165 to "165Hz").forEach { (hz, label) ->
                        FilterChip(
                            selected = hz == refreshRate,
                            onClick = {
                                scope.launch {
                                    Prefs.setRefreshRate(ctx, hz)
                                    withContext(Dispatchers.IO) { RootShell.setRefreshRate(ctx, hz) }
                                }
                            },
                            label = { Text(label) },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
            }

            // —— 权限(从原设置页迁入: 状态诊断) ——
            PrefGroup("权限") {
                PermRow(
                    name = "Root 权限",
                    granted = isRoot,
                    desc = "调用模块 powercfg.sh 必需",
                    icon = Icons.Outlined.Security,
                    onAction = null,
                )
                PrefDivider()
                PermRow(
                    name = "查看使用情况",
                    granted = hasUsagePerm,
                    desc = "无 root 时的前台检测兜底",
                    icon = Icons.Outlined.Visibility,
                    onAction = {
                        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                )
                PrefDivider()
                PermRow(
                    name = "无障碍·前台推送",
                    granted = a11yEnabled,
                    desc = "识别全屏主应用推给守护进程(多窗口取最大窗口);root 一键授权、跨重启保持。可选",
                    icon = Icons.Outlined.Bolt,
                    onAction = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { RootShell.setAccessibility(true) }
                            Prefs.setA11yWanted(ctx, true)
                            if (!ok) runCatching {
                                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            kotlinx.coroutines.delay(400)
                            a11yEnabled = com.ktwo.cts.service.AppSwitchService.isEnabled(ctx)
                        }
                    }
                )
                PrefDivider()
                PermRow(
                    name = "电池优化白名单",
                    granted = batteryWhitelisted,
                    desc = "防止后台被杀",
                    icon = Icons.Outlined.BatteryFull,
                    onAction = {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                )
            }

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
                    FeatureItem(
                        Icons.Outlined.AccountTree, "线程分配",
                        if (proUnlocked) "渲染线程归拢到大核, 提升流畅度"
                        else "智能版功能 · 渲染线程归拢到大核",
                        locked = !proUnlocked,
                        onClick = onOpenThreadAlloc,
                    ),
                ),
            )

            Spacer(Modifier.height(96.dp))
        }
    }

    if (showOverlaySheet) {
        OverlayManagerSheet(onDismiss = { showOverlaySheet = false })
    }

    // 电池矫正对话框(从原设置页迁入)
    if (showCalibrate) {
        CellCalibrationDialog(cancelable = true, onDone = { showCalibrate = false })
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
