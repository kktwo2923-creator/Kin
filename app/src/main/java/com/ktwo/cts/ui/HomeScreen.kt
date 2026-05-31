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
import kotlinx.coroutines.*

/**
 * 主屏 - SukiSU-Ultra 风格:
 *  - 可折叠 LargeTopAppBar
 *  - 顶部状态主卡(接管中=secondaryContainer / 未接管=errorContainer),内联状态徽章
 *  - 2 个并排统计卡(Root / 模块)
 *  - 信息卡(当前模式 / 服务 / 调用入口)
 *  - 全局模式网格(图标按钮)
 */
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

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text("OnePlus V Pro", fontWeight = FontWeight.Bold)
                },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1) 大状态主卡
            // 读不到模块上报的当前模式时,回退显示全局模式,避免一直"等待首次切换"
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
                                if (on) ScheduleService.start(ctx) else ScheduleService.stop(ctx)
                            }
                        }
                    )
                }
            )

            // 2) 并排统计卡
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

            // 3) 信息卡
            InfoCard(
                title = "调度状态",
                rows = listOf(
                    "全局模式" to Mode.fromKey(globalMode).label,
                    "后台服务" to if (enabled) "运行中" else "已停止",
                    "接管入口" to "/data/powercfg.sh <mode>",
                )
            )

            // (模式切换已移至「调度」页)

            // 给浮动玻璃底栏留出空间
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
fun ModeGrid(selectedKey: String, onSelect: (Mode) -> Unit) {
    val modes = Mode.DOWNSTREAM
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        modes.chunked(2).forEach { rowModes ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowModes.forEach {
                    ModeButton(it, it.key == selectedKey, Modifier.weight(1f)) { onSelect(it) }
                }
                if (rowModes.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModeButton(mode: Mode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = if (selected) MaterialTheme.colorScheme.primary
                 else MaterialTheme.colorScheme.onSurfaceVariant
    GlassSurface(
        modifier = modifier
            .height(96.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        tint = if (selected) MaterialTheme.colorScheme.primaryContainer
               else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                CtsIcons.forMode(mode.key),
                contentDescription = mode.label,
                tint = accent,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(mode.label, color = accent, fontWeight = FontWeight.SemiBold)
        }
    }
}
