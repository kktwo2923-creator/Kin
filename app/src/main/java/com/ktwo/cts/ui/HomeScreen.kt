package com.ktwo.cts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ktwo.cts.data.Mode
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.service.ScheduleService
import com.ktwo.cts.ui.theme.LocalEnableBlur
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.blur.layerBackdrop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by Prefs.enabledFlow(ctx).collectAsState(initial = true)
    var globalMode by remember { mutableStateOf("balance") }
    var moduleInstalled by remember { mutableStateOf(true) }
    var isRoot by remember { mutableStateOf(true) }
    var currentMode by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            isRoot = RootShell.isRooted()
            moduleInstalled = isRoot && RootShell.moduleInstalled()
            globalMode = RootShell.readGlobalMode()
            currentMode = RootShell.currentMode()
        }
        while (isActive) {
            delay(2500)
            val m = withContext(Dispatchers.IO) { RootShell.currentMode() }
            if (m != currentMode) currentMode = m
        }
    }

    val working = isRoot && moduleInstalled && enabled

    // SukiSU 顶栏模糊: 顶栏作为 textureBlur 消费者, 滚动内容作为 layerBackdrop 采样源
    val blurBackdrop = rememberBlurBackdrop(LocalEnableBlur.current)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            BlurredBar(blurBackdrop) {
                TopAppBar(
                    title = {
                        Text("OnePlus V Pro", fontWeight = FontWeight.Bold)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                )
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Horizontal),
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (blurBackdrop != null) Modifier.layerBackdrop(blurBackdrop)
                    else Modifier
                )
                .verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 读不到当前模式时回退显示全局模式
            val shownMode = Mode.fromKey(currentMode.ifEmpty { globalMode })
            StatusHeroCard(
                working = working,
                icon = if (working) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                title = if (working) "接管中" else "未接管",
                subtitle = if (working)
                    "当前 ${shownMode.label} · 按画像 + 灭屏自动切换"
                else if (!isRoot)
                    "未授予 Root · 无法接管"
                else if (!moduleInstalled)
                    "未检测到调度模块"
                else
                    "服务已停止 · 打开开关启用接管",
                badges = buildList {
                    if (enabled) add("运行中" to true) else add("已停止" to false)
                },
                trailing = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            scope.launch {
                                Prefs.setEnabled(ctx, on)
                                if (on) {
                                    if (!RootShell.daemonAlive()) ScheduleService.start(ctx)
                                } else ScheduleService.stop(ctx)
                            }
                        }
                    )
                }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Security,
                    value = if (isRoot) "已授权" else "未授权",
                    label = "Root 权限",
                    tint = if (isRoot) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Extension,
                    value = if (moduleInstalled) "已安装" else "未安装",
                    label = "调度模块",
                    tint = if (moduleInstalled) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                )
            }

            InfoCard(
                title = "调度状态",
                rows = listOf(
                    "全局模式" to Mode.fromKey(globalMode).label,
                    "后台服务" to if (enabled) "运行中" else "已停止",
                    "接管入口" to "/data/powercfg.sh <mode>",
                )
            )

            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
fun ModeGrid(selectedKey: String, onSelect: (Mode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Mode.DOWNSTREAM.forEach { m ->
            ModeButton(m, m.key == selectedKey, Modifier.weight(1f)) { onSelect(m) }
        }
    }
}

@Composable
private fun ModeButton(mode: Mode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = if (selected) MaterialTheme.colorScheme.primary
                 else MaterialTheme.colorScheme.onSurfaceVariant
    GlassSurface(
        modifier = modifier
            .height(72.dp)
            .pressClickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        tint = if (selected) MaterialTheme.colorScheme.primaryContainer
               else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                CtsIcons.forMode(mode.key),
                contentDescription = mode.label,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                mode.label,
                color = accent,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}
