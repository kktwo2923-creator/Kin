package com.ktwo.cts.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.ktwo.cts.data.Mode
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.PrefsExt
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.service.ForegroundDetector
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val screenOffMode by Prefs.screenOffModeFlow(ctx).collectAsState(initial = "powersave")
    val strict by PrefsExt.strictFlow(ctx).collectAsState(initial = false)
    var pollMs by remember { mutableStateOf(2000) }
    var hasUsagePerm by remember { mutableStateOf(false) }
    var isRoot by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        pollMs = Prefs.pollMs(ctx)
        hasUsagePerm = ForegroundDetector(ctx).hasUsageStatsPermission()
        isRoot = withContext(Dispatchers.IO) { RootShell.isRooted() }
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
            Section("接管行为") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("灭屏模式", fontWeight = FontWeight.Medium)
                    Mode.DOWNSTREAM.forEach { m ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = m.key == screenOffMode,
                                onClick = { scope.launch { Prefs.setScreenOffMode(ctx, m.key) } }
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                CtsIcons.forMode(m.key), null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(m.label)
                        }
                    }
                    HorizontalDivider()
                    ToggleRow(
                        title = "STRICT 同模式重发",
                        desc = "切换应用时,即使目标模式与当前相同也重发一次(部分游戏需要)",
                        checked = strict,
                        onCheckedChange = { scope.launch { PrefsExt.setStrict(ctx, it) } }
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

            Section("性能") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("前台 App 轮询间隔: ${pollMs / 1000.0} 秒", fontWeight = FontWeight.Medium)
                    Slider(
                        value = pollMs.toFloat(),
                        onValueChange = { pollMs = it.toInt() },
                        valueRange = 1000f..10000f,
                        steps = 17,
                        onValueChangeFinished = {
                            scope.launch { Prefs.setPollMs(ctx, pollMs) }
                        }
                    )
                    Text(
                        "短=切换跟手但耗电略多; 长=省电但有延迟",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Section("关于") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("OnePlus V Pro · 接管助手", fontWeight = FontWeight.Medium)
                    Text(
                        "替代 Scene 接管,按应用画像自动切换调度模式\n调用入口: /data/powercfg.sh <mode>",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 给浮动玻璃底栏留出空间
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
