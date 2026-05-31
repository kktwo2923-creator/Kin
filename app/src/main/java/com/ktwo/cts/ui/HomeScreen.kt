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
import com.ktwo.cts.root.RootShell
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
            val gm = withContext(Dispatchers.IO) { RootShell.readGlobalMode() }
            val m = withContext(Dispatchers.IO) { RootShell.currentMode() }
            if (gm != globalMode) globalMode = gm
            if (m != currentMode) currentMode = m
        }
    }

    // 接管与否完全取决于 CTS 模块是否就位(root + 模块)
    val working = isRoot && moduleInstalled

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
            // 读不到模块上报的当前模式时,回退显示全局模式
            val shownMode = Mode.fromKey(currentMode.ifEmpty { globalMode })
            StatusHeroCard(
                working = working,
                icon = if (working) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                title = if (working) "接管中" else "未接管",
                subtitle = if (working)
                    "当前 ${shownMode.label} · 由 CTS 模块按画像自动切换"
                else if (!isRoot)
                    "未授予 Root · 无法接管"
                else
                    "未检测到 CTS 调度模块",
                badges = buildList {
                    if (working) add("CTS 接管" to true) else add("未就绪" to false)
                },
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
                    "接管入口" to "/data/powercfg.sh <mode>",
                )
            )

            // (模式切换在「调度」页;实际接管由 CTS 模块完成)

            // 给浮动玻璃底栏留出空间
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
fun ModeGrid(selectedKey: String, onSelect: (Mode) -> Unit) {
    // 全局模式只有 4 档,单行紧凑排布(图标小 + 文字),都完整显示
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
            .clickable(onClick = onClick),
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
