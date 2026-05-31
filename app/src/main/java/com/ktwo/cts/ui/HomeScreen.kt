package com.ktwo.cts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
 * 主屏 - 模仿 SukiSU-Ultra 风格:
 *  1) 顶部大圆角主状态卡(突出"接管中/未接管"状态)
 *  2) 2x2 信息小卡片网格(Root / 模块 / 模式 / 接管)
 *  3) 全局模式选择
 *  4) 一键切换
 */
@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by Prefs.enabledFlow(ctx).collectAsState(initial = true)
    var globalMode by remember { mutableStateOf("balance") }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { globalMode = RootShell.readGlobalMode() } }
    var moduleInstalled by remember { mutableStateOf(true) }
    var isRoot by remember { mutableStateOf(true) }
    var currentMode by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            isRoot = RootShell.isRooted()
            moduleInstalled = isRoot && RootShell.moduleInstalled()
            currentMode = RootShell.currentMode()
        }
        while (true) {
            delay(2500)
            val m = withContext(Dispatchers.IO) { RootShell.currentMode() }
            if (m != currentMode) currentMode = m
        }
    }

    val working = isRoot && moduleInstalled && enabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1) 大状态主卡
        HeroStatusCard(
            working = working,
            currentMode = currentMode,
            serviceEnabled = enabled,
            onToggle = { on ->
                scope.launch {
                    Prefs.setEnabled(ctx, on)
                    if (on) ScheduleService.start(ctx) else ScheduleService.stop(ctx)
                }
            }
        )

        // 2) 2x2 小卡片
        InfoGrid(
            isRoot = isRoot,
            moduleInstalled = moduleInstalled,
            globalMode = globalMode,
            serviceEnabled = enabled,
        )

        // 3) 全局模式
        SectionTitle("全局模式")
        ModeGrid(selectedKey = globalMode) { m ->
            scope.launch {
                globalMode = m.key
                withContext(Dispatchers.IO) {
                    RootShell.writePerApp(RootShell.readPerApp(), m.key)
                    RootShell.applyMode(m.key)
                }
            }
        }

        // 4) 一键切换
        SectionTitle("快速切换")
        QuickRow { m ->
            scope.launch(Dispatchers.IO) { RootShell.applyMode(m.key) }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun HeroStatusCard(
    working: Boolean,
    currentMode: String,
    serviceEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val container = if (working)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.errorContainer
    val onContainer = if (working)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onErrorContainer

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (working) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        if (working) "接管中" else "未接管",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    val m = Mode.fromKey(currentMode)
                    Text(
                        if (currentMode.isNotEmpty()) "当前 ${m.emoji} ${m.label}" else "等待首次切换",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            HorizontalDivider(color = onContainer.copy(alpha = 0.15f))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动接管", fontWeight = FontWeight.Medium)
                    Text(
                        "按应用画像 + 灭屏自动切换",
                        style = MaterialTheme.typography.bodySmall,
                        color = onContainer.copy(alpha = 0.7f)
                    )
                }
                Switch(checked = serviceEnabled, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
private fun InfoGrid(
    isRoot: Boolean,
    moduleInstalled: Boolean,
    globalMode: String,
    serviceEnabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            InfoCard(
                Modifier.weight(1f),
                icon = Icons.Outlined.Security,
                title = "Root",
                value = if (isRoot) "已授权" else "未授权",
                ok = isRoot,
            )
            InfoCard(
                Modifier.weight(1f),
                icon = Icons.Outlined.Extension,
                title = "模块",
                value = if (moduleInstalled) "已安装" else "未安装",
                ok = moduleInstalled,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val m = Mode.fromKey(globalMode)
            InfoCard(
                Modifier.weight(1f),
                icon = Icons.Outlined.Tune,
                title = "全局模式",
                value = "${m.emoji} ${m.label}",
                ok = true,
            )
            InfoCard(
                Modifier.weight(1f),
                icon = Icons.Outlined.SwitchAccount,
                title = "服务",
                value = if (serviceEnabled) "运行中" else "已停止",
                ok = serviceEnabled,
            )
        }
    }
}

@Composable
private fun InfoCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    value: String,
    ok: Boolean,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon, null,
                    modifier = Modifier.size(20.dp),
                    tint = if (ok) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(6.dp))
                Text(title, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp, bottom = 2.dp)
    )
}

@Composable
fun ModeGrid(selectedKey: String, onSelect: (Mode) -> Unit) {
    val modes = Mode.DOWNSTREAM
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            modes.take(2).forEach {
                ModeButton(it, it.key == selectedKey, Modifier.weight(1f)) { onSelect(it) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            modes.drop(2).forEach {
                ModeButton(it, it.key == selectedKey, Modifier.weight(1f)) { onSelect(it) }
            }
        }
    }
}

@Composable
private fun ModeButton(mode: Mode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
    val text = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
               else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        modifier = modifier.height(88.dp),
        shape = RoundedCornerShape(20.dp),
        color = container,
        tonalElevation = if (selected) 4.dp else 0.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(mode.emoji, fontSize = 26.sp)
            Spacer(Modifier.height(4.dp))
            Text(mode.label, color = text, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun QuickRow(onApply: (Mode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Mode.DOWNSTREAM.forEach { m ->
            FilledTonalButton(
                onClick = { onApply(m) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text(m.label)
            }
        }
    }
}
