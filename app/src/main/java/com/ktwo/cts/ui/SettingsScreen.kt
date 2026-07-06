package com.ktwo.cts.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
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
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.service.ForegroundDetector
import com.ktwo.cts.ui.theme.ThemeMode
import com.ktwo.cts.ui.theme.UiStyle
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onOpenAbout: () -> Unit = {},
    onOpenPro: () -> Unit = {},
    onOpenDocs: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val proUnlocked by Prefs.proUnlockedFlow(ctx).collectAsState(initial = false)
    val autostart by Prefs.autostartFlow(ctx).collectAsState(initial = true)
    val themeKey by Prefs.themeModeFlow(ctx).collectAsState(initial = "system")
    val uiStyleKey by Prefs.uiStyleFlow(ctx).collectAsState(initial = "glass")
    var showStyleDialog by remember { mutableStateOf(false) }
    val wallpaperUri by Prefs.wallpaperUriFlow(ctx).collectAsState(initial = "")
    val enableFloatingBar by Prefs.enableFloatingBottomBarFlow(ctx).collectAsState(initial = true)
    val enableFloatingBarBlur by Prefs.enableFloatingBottomBarBlurFlow(ctx)
        .collectAsState(initial = true)
    val notifyEnabled by Prefs.notifyEnabledFlow(ctx).collectAsState(initial = false)
    val refreshRate by Prefs.refreshRateFlow(ctx).collectAsState(initial = 0)
    var hasUsagePerm by remember { mutableStateOf(false) }
    var isRoot by remember { mutableStateOf(true) }
    var a11yEnabled by remember { mutableStateOf(false) }
    var batteryWhitelisted by remember { mutableStateOf(false) }
    var showCalibrate by remember { mutableStateOf(false) }
    val cellCount by Prefs.cellCountFlow(ctx).collectAsState(initial = 1)

    // 回到前台就重查权限状态(用户去系统设置授权后返回, 红/绿即时刷新)
    fun refreshPerms() = scope.launch {
        a11yEnabled = com.ktwo.cts.service.AppSwitchService.isEnabled(ctx)
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

    // 把选中图片复制到 App 私有目录存绝对路径,避免临时 content URI 重启失效
    val pickWallpaper = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val dst = java.io.File(ctx.filesDir, "wallpaper.jpg")
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        dst.outputStream().use { ins.copyTo(it) }
                    }
                    dst.absolutePath
                }.getOrNull()
            }
            if (saved != null) Prefs.setWallpaperUri(ctx, saved)
        }
    }

    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        refreshPerms()
        onPauseOrDispose { }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = { Text("设置") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Horizontal),
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 付费解锁入口(仿 MetricPro): 智能模式 + 系统优化 的解锁页
            SectionTitle("智能版")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenPro)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("解锁智能版 · Pro", fontWeight = FontWeight.Medium)
                        Text(
                            if (proUnlocked) "已解锁 · 智能模式 / 系统优化 已可用"
                            else "解锁智能模式 + 系统优化(WiFi/网络/内存)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (proUnlocked) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Section("通用") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ToggleRow(
                        title = "开机自启动",
                        desc = "开机后自动拉起常驻通知/兜底调度(无障碍服务由系统在开机后自动恢复)",
                        checked = autostart,
                        onCheckedChange = { scope.launch { Prefs.setAutostart(ctx, it) } }
                    )
                    HorizontalDivider()
                    ToggleRow(
                        title = "状态栏常驻通知",
                        desc = "显示当前模式 + 电量;点击在任意应用上弹出快切悬浮窗(需通知 + 悬浮窗权限)",
                        checked = notifyEnabled,
                        onCheckedChange = { toggleNotify(it) },
                    )
                }
            }

            Section("外观") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("主题", fontWeight = FontWeight.Medium)
                    val cur = ThemeMode.fromKey(themeKey)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { tm ->
                            FilterChip(
                                selected = tm == cur,
                                onClick = { scope.launch { Prefs.setThemeMode(ctx, tm.key) } },
                                label = { Text(tm.label) },
                                shape = RoundedCornerShape(12.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                    // 界面风格: 液态玻璃(默认) / Metric 仪表盘
                    Row(
                        Modifier.fillMaxWidth().clickable { showStyleDialog = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("界面风格", fontWeight = FontWeight.Medium)
                            Text(
                                "当前: ${UiStyle.fromKey(uiStyleKey).label} · 仅切换主页布局, 全局保留液态玻璃",
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
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自定义壁纸", fontWeight = FontWeight.Medium)
                            Text(
                                if (wallpaperUri.isBlank()) "当前: 默认渐变" else "当前: 自定义图片",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (wallpaperUri.isNotBlank()) {
                            TextButton(onClick = { scope.launch { Prefs.setWallpaperUri(ctx, "") } }) {
                                Text("重置")
                            }
                        }
                        FilledTonalButton(onClick = {
                            pickWallpaper.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }) { Text("选择") }
                    }
                    HorizontalDivider()
                    // SukiSU-Ultra 主题设置同款开关
                    ToggleRow(
                        title = "悬浮底栏",
                        desc = "使用 Apple 风格的悬浮底栏",
                        checked = enableFloatingBar,
                        onCheckedChange = {
                            scope.launch { Prefs.setEnableFloatingBottomBar(ctx, it) }
                        },
                    )
                    AnimatedVisibility(visible = enableFloatingBar) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            HorizontalDivider()
                            ToggleRow(
                                title = "液态玻璃",
                                desc = "启用悬浮底栏的液态玻璃效果",
                                checked = enableFloatingBarBlur,
                                onCheckedChange = {
                                    scope.launch { Prefs.setEnableFloatingBottomBarBlur(ctx, it) }
                                },
                            )
                        }
                    }
                }
            }

            Section("采样") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("屏幕刷新率", fontWeight = FontWeight.Medium)
                    Text(
                        "锁定后跨重启保持(root 写系统设置)。自动 = 交还系统自适应。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }

            Section("电池") {
                Row(
                    Modifier.fillMaxWidth().clickable { showCalibrate = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.BatteryFull,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("重新矫正电池", fontWeight = FontWeight.Medium)
                        Text(
                            "当前: ${if (cellCount >= 2) "双电芯" else "单电芯"} · 功率偏差一倍时来这里改",
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

            Section("权限") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PermRow(
                        name = "Root 权限",
                        granted = isRoot,
                        desc = "调用模块 powercfg.sh 必需",
                        icon = Icons.Outlined.Security,
                        onAction = null,
                    )
                    HorizontalDivider()
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
                    HorizontalDivider()
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
                    HorizontalDivider()
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
            }

            Section("日志") {
                val moreLogs by Prefs.moreLogsFlow(ctx).collectAsState(initial = false)
                ToggleRow(
                    "更多日志",
                    "日志页默认只显示 运行/模式/前台 三个日志;开启后显示全部日志文件",
                    moreLogs,
                ) { scope.launch { Prefs.setMoreLogs(ctx, it) } }
            }

            // 同 SukiSU 设置页: 关于作为入口行, 点开展示图标/版本/GitHub 链接
            SectionTitle("其他")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenDocs)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("使用文档", fontWeight = FontWeight.Medium)
                        Text(
                            "功能说明 · 常见问题",
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
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAbout)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("关于", fontWeight = FontWeight.Medium)
                        Text(
                            "版本信息 · 开源地址",
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

            Spacer(Modifier.height(96.dp))
        }
    }

    // 设置里主动重新矫正:可取消(cancelable=true)
    if (showCalibrate) {
        CellCalibrationDialog(cancelable = true, onDone = { showCalibrate = false })
    }

    // 界面风格单选对话框
    if (showStyleDialog) {
        AlertDialog(
            onDismissRequest = { showStyleDialog = false },
            title = { Text("界面风格") },
            text = {
                Column {
                    UiStyle.entries.forEach { style ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { Prefs.setUiStyle(ctx, style.key) }
                                    showStyleDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = style.key == uiStyleKey,
                                onClick = {
                                    scope.launch { Prefs.setUiStyle(ctx, style.key) }
                                    showStyleDialog = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(style.label)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "两种风格均为液态玻璃, 区别仅在主页是否显示仪表盘",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStyleDialog = false }) { Text("完成") }
            },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    SectionTitle(title)
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(
    title: String, desc: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermRow(
    name: String, granted: Boolean, desc: String,
    icon: ImageVector,
    onAction: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, null,
            modifier = Modifier.size(24.dp),
            tint = if (granted) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (granted) {
            Icon(Icons.Outlined.CheckCircle, null,
                tint = MaterialTheme.colorScheme.primary)
        } else if (onAction != null) {
            TextButton(onClick = onAction) { Text("设置") }
        }
    }
}
