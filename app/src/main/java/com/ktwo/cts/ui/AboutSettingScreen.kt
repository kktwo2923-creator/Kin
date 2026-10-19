// 关于 tab 下的「设置」子页 — 对齐 OShin about_setting.kt 模式:
// 独立全屏页, 收 App 级偏好(外观/检测更新入口/文档) + 功能行为开关(常规/电池/采样/权限)。
package com.ktwo.cts.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
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
import androidx.compose.ui.unit.dp
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.service.ForegroundDetector
import com.ktwo.cts.ui.theme.ThemeMode
import com.ktwo.cts.ui.theme.UiStyle
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AboutSettingScreen(
    onBack: () -> Unit,
    onOpenUpdate: () -> Unit = {},
    onOpenDocs: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val themeKey by Prefs.themeModeFlow(ctx).collectAsState(initial = "system")
    val visualTheme by Prefs.visualThemeFlow(ctx).collectAsState(initial = "snow")
    val uiStyleKey by Prefs.uiStyleFlow(ctx).collectAsState(initial = "glass")
    var showStyleDialog by remember { mutableStateOf(false) }
    val wallpaperUri by Prefs.wallpaperUriFlow(ctx).collectAsState(initial = "")
    val enableFloatingBar by Prefs.enableFloatingBottomBarFlow(ctx).collectAsState(initial = true)
    val enableFloatingBarBlur by Prefs.enableFloatingBottomBarBlurFlow(ctx)
        .collectAsState(initial = true)

    // —— 常规/电池/采样/权限(功能行为开关, 与 OShin 设置子页对齐) ——
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
        if (!android.provider.Settings.canDrawOverlays(ctx)) {
            runCatching {
                ctx.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // —— 外观(对齐 OShin about_setting 的 App 级偏好) ——
            PrefGroup("外观") {
                PrefChipsRow(title = "视觉主题", desc = null) {
                    listOf("default" to "默认", "snow" to "K2娘").forEach { (key, label) ->
                        FilterChip(
                            selected = visualTheme == key,
                            onClick = { scope.launch { Prefs.setVisualTheme(ctx, key) } },
                            label = { Text(label) },
                        )
                    }
                }
                PrefDivider()
                PrefChipsRow(title = "主题", desc = null) {
                    val cur = ThemeMode.fromKey(themeKey)
                    ThemeMode.entries.forEach { tm ->
                        FilterChip(
                            selected = tm == cur,
                            onClick = { scope.launch { Prefs.setThemeMode(ctx, tm.key) } },
                            label = { Text(tm.label) },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
                PrefDivider()
                PrefNavRow(
                    title = "界面风格",
                    desc = "当前: ${UiStyle.fromKey(uiStyleKey).label} · 仅切换主页布局, 全局保留液态玻璃",
                    onClick = { showStyleDialog = true },
                )
                PrefDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                PrefDivider()
                PrefToggleRow(
                    title = "悬浮底栏",
                    desc = "使用 Apple 风格的悬浮底栏",
                    checked = enableFloatingBar,
                    onCheckedChange = {
                        scope.launch { Prefs.setEnableFloatingBottomBar(ctx, it) }
                    },
                )
                AnimatedVisibility(visible = enableFloatingBar) {
                    Column {
                        PrefDivider()
                        PrefToggleRow(
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

            // —— 常规(服务行为开关) ——
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

            // —— 电池 ——
            PrefGroup("电池") {
                PrefNavRow(
                    title = "重新矫正电池",
                    desc = "当前: ${if (cellCount >= 2) "双电芯" else "单电芯"} · 功率偏差一倍时来这里改",
                    onClick = { showCalibrate = true },
                )
            }

            // —— 采样 ——
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

            // —— 权限(状态诊断) ——
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
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
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
                                ctx.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
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
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                )
            }

            // —— 其他(检测更新 / 使用文档, 对齐原设置页「其他」组的剩余项) ——
            PrefGroup("其他") {
                PrefNavRow(
                    icon = Icons.Outlined.SystemUpdate,
                    title = "检测更新",
                    desc = "App / Kinetic / 模块 三通道独立检测与更新",
                    onClick = onOpenUpdate,
                )
                PrefDivider()
                PrefNavRow(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = "使用文档",
                    desc = "功能说明 · 常见问题",
                    onClick = onOpenDocs,
                )
                PrefDivider()
                PrefNavRow(
                    icon = Icons.Outlined.Info,
                    title = "真机采样",
                    desc = "生成并上传脱敏设备能力报告",
                    onClick = onOpenDiagnostics,
                )
            }

            Spacer(Modifier.height(96.dp))
        }
    }

    // 电池矫正对话框
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
