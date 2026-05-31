package com.ktwo.cts.ui

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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 *  - 全局模式网格 + 快速切换
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("OnePlus V Pro") },
                scrollBehavior = scrollBehavior,
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
            val m = Mode.fromKey(currentMode)
            StatusHeroCard(
                working = working,
                icon = if (working) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                title = if (working) "接管中" else "未接管",
                subtitle = if (currentMode.isNotEmpty())
                    "当前 ${m.emoji} ${m.label} · 按画像 + 灭屏自动切换"
                else
                    "等待首次切换",
                badges = buildList {
                    if (isRoot) add("ROOT" to true)
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
                    "全局模式" to "${Mode.fromKey(globalMode).emoji} ${Mode.fromKey(globalMode).label}",
                    "后台服务" to if (enabled) "运行中" else "已停止",
                    "接管入口" to "/data/powercfg.sh <mode>",
                )
            )

            // 4) 全局模式
            SectionTitle("全局模式")
            ModeGrid(selectedKey = globalMode) { sel ->
                scope.launch {
                    globalMode = sel.key
                    withContext(Dispatchers.IO) {
                        RootShell.writePerApp(RootShell.readPerApp(), sel.key)
                        RootShell.applyMode(sel.key)
                    }
                }
            }

            // 5) 快速切换
            SectionTitle("快速切换")
            QuickRow { sel ->
                scope.launch(Dispatchers.IO) { RootShell.applyMode(sel.key) }
            }

            Spacer(Modifier.height(8.dp))
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
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh
    val text = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
               else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = modifier.height(92.dp),
        shape = RoundedCornerShape(22.dp),
        color = container,
        tonalElevation = if (selected) 6.dp else 0.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(mode.emoji, fontSize = 28.sp)
            Spacer(Modifier.height(4.dp))
            Text(mode.label, color = text, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun QuickRow(onApply: (Mode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Mode.DOWNSTREAM.forEach { m ->
            FilledTonalButton(
                onClick = { onApply(m) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text(m.label, maxLines = 1)
            }
        }
    }
}
