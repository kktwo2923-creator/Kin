// ColorOS 专属调控页: 面向 OnePlus/OPPO/realme(ColorOS/realmeUI)的独家控制项。
// 进页在 IO 线程探测节点/服务可用性, 不可用的项灰置并标注; 非 ColorOS 设备/无 root 顶部提示。
// 所有写操作 IO 线程 + runCatching, 完成后 Snackbar 反馈并重读该项真实状态(不盲信开关)。
package com.ktwo.cts.ui.coloros

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ktwo.cts.data.ColorOsTweaks
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.ui.SectionTitle
import com.ktwo.cts.ui.TonalCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorOsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var loaded by remember { mutableStateOf(false) }
    var rooted by remember { mutableStateOf(true) }
    var colorOs by remember { mutableStateOf(true) }

    var cpuAvail by remember { mutableStateOf(false) }
    var cpuRemoved by remember { mutableStateOf(false) }
    var touchAvail by remember { mutableStateOf(false) }
    var touchOn by remember { mutableStateOf(false) }
    var thermalAvail by remember { mutableStateOf(false) }
    var thermalOn by remember { mutableStateOf(true) }

    var busy by remember { mutableStateOf(false) }
    var confirmThermalOff by remember { mutableStateOf(false) }

    // 进页探测: root / 设备识别 / 各节点可用性与当前状态, 全部 IO 线程 + 容错。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                rooted = RootShell.isRooted()
                colorOs = ColorOsTweaks.isColorOs()
                cpuAvail = ColorOsTweaks.cpuLimitAvailable()
                if (cpuAvail) cpuRemoved = ColorOsTweaks.isCpuLimitRemoved()
                touchAvail = ColorOsTweaks.touchGameAvailable()
                if (touchAvail) touchOn = ColorOsTweaks.isTouchGameOn()
                thermalAvail = ColorOsTweaks.thermalAvailable()
                if (thermalAvail) thermalOn = ColorOsTweaks.isThermalOn()
            }
        }
        loaded = true
    }

    // 通用写入器: IO 线程执行 write, 完成后重读真实状态回填, Snackbar 反馈。
    fun applyTweak(label: String, write: () -> Boolean, reload: () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val r = runCatching { write() }.getOrDefault(false)
                runCatching { reload() }
                r
            }
            busy = false
            snackbar.showSnackbar(
                if (ok) "$label: 已应用"
                else "$label: 操作失败" + if (!rooted) "(需要 Root)" else ""
            )
        }
    }

    fun setThermalChecked(on: Boolean) = applyTweak(
        if (on) "恢复温控" else "临时关闭温控",
        write = { ColorOsTweaks.setThermal(on) },
        reload = { thermalOn = ColorOsTweaks.isThermalOn() },
    )

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("ColorOS 专属") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
            if (loaded && !colorOs) {
                HintCard("本机不是 ColorOS/OPPO 设备, 以下开关可能无效。仍可尝试, 但请降低期待。")
            }
            if (loaded && !rooted) {
                HintCard("需要 Root: 未检测到 root 权限, 以下开关将无法生效。")
            }

            SectionTitle("独家控制")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TweakRow(
                        title = "解除 CPU 限频(满血)",
                        desc = if (cpuAvail) "解除 ColorOS 游戏限频, CPU 可跑满频; 更烫更耗电。"
                               else "本机无此节点",
                        checked = cpuAvail && cpuRemoved,
                        enabled = loaded && cpuAvail && !busy,
                        onToggle = { on ->
                            applyTweak(
                                "解除 CPU 限频",
                                write = { ColorOsTweaks.setCpuLimitRemoved(on) },
                                reload = { cpuRemoved = ColorOsTweaks.isCpuLimitRemoved() },
                            )
                        },
                    )
                    HorizontalDivider()
                    TweakRow(
                        title = "游戏触控优化",
                        desc = if (touchAvail) "开启触控面板游戏模式, 提升跟手。"
                               else "本机无此节点",
                        checked = touchAvail && touchOn,
                        enabled = loaded && touchAvail && !busy,
                        onToggle = { on ->
                            applyTweak(
                                "游戏触控优化",
                                write = { ColorOsTweaks.setTouchGame(on) },
                                reload = { touchOn = ColorOsTweaks.isTouchGameOn() },
                            )
                        },
                    )
                    HorizontalDivider()
                    // 开关语义 = 温控服务是否在运行: 关掉开关 = 停温控(危险, 先二次确认)。
                    TweakRow(
                        title = "临时关闭温控",
                        desc = if (thermalAvail)
                            "停用温控服务解除热降频(重启自动恢复); 请注意散热, " +
                                "长时间高负载有过热风险。部分机型可能无效。"
                        else "本机未检测到已知温控服务",
                        checked = thermalAvail && thermalOn,
                        enabled = loaded && thermalAvail && !busy,
                        onToggle = { on ->
                            if (!on) confirmThermalOff = true else setThermalChecked(true)
                        },
                    )
                }
            }

            Spacer(Modifier.height(96.dp))
        }
    }

    if (confirmThermalOff) {
        AlertDialog(
            onDismissRequest = { confirmThermalOff = false },
            title = { Text("确认关闭温控?") },
            text = {
                Text(
                    "停用温控服务后系统不再热降频, 长时间高负载可能导致机身过热、" +
                        "异常降亮度甚至硬件受损。重启手机会自动恢复温控。确定继续?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmThermalOff = false
                    setThermalChecked(false)
                }) { Text("确认关闭", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmThermalOff = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun HintCard(text: String) {
    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TweakRow(
    title: String,
    desc: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
    }
}
