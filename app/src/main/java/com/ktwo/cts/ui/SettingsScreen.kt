package com.ktwo.cts.ui

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
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onOpenAbout: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val autostart by Prefs.autostartFlow(ctx).collectAsState(initial = true)
    val themeKey by Prefs.themeModeFlow(ctx).collectAsState(initial = "system")
    val wallpaperUri by Prefs.wallpaperUriFlow(ctx).collectAsState(initial = "")
    val enableFloatingBar by Prefs.enableFloatingBottomBarFlow(ctx).collectAsState(initial = true)
    val enableFloatingBarBlur by Prefs.enableFloatingBottomBarBlurFlow(ctx)
        .collectAsState(initial = true)
    val notifyEnabled by Prefs.notifyEnabledFlow(ctx).collectAsState(initial = false)
    var hasUsagePerm by remember { mutableStateOf(false) }
    var isRoot by remember { mutableStateOf(true) }
    var a11yEnabled by remember { mutableStateOf(false) }

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

    LaunchedEffect(Unit) {
        hasUsagePerm = ForegroundDetector(ctx).hasUsageStatsPermission()
        isRoot = withContext(Dispatchers.IO) { RootShell.isRooted() }
        a11yEnabled = com.ktwo.cts.service.AppSwitchService.isEnabled(ctx)
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

            Section("通知") {
                ToggleRow(
                    title = "状态栏常驻通知",
                    desc = "显示当前模式 + 电量;点击在任意应用上弹出快切悬浮窗(需通知 + 悬浮窗权限)",
                    checked = notifyEnabled,
                    onCheckedChange = { toggleNotify(it) },
                )
            }

            Section("通用") {
                ToggleRow(
                    title = "开机自启动",
                    desc = "开机后自动拉起常驻通知/兜底调度(无障碍服务由系统在开机后自动恢复)",
                    checked = autostart,
                    onCheckedChange = { scope.launch { Prefs.setAutostart(ctx, it) } }
                )
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
                        desc = "实时把前台应用推给守护进程,替代 dumpsys 轮询(更快更省电);仅读包名不读屏幕内容。可选",
                        icon = Icons.Outlined.Bolt,
                        onAction = {
                            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    )
                    HorizontalDivider()
                    PermRow(
                        name = "电池优化白名单",
                        granted = false,
                        desc = "防止后台被杀",
                        icon = Icons.Outlined.BatteryFull,
                        onAction = {
                            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    )
                }
            }

            // 同 SukiSU 设置页: 关于作为入口行, 点开展示图标/版本/GitHub 链接
            SectionTitle("其他")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
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
