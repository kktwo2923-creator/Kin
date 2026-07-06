// ColorOS 专属调控页: 「系统服务」开关组(oiface / horae / GameOpt / CPU 频率限制)。
// 进页在 IO 线程探测 root / 设备识别 / 各项可用性与当前状态; 不可用的项灰置并在副文案标注;
// 非 ColorOS 设备 / 无 root 时顶部提示卡。所有写操作 IO 线程 + runCatching, 完成后重读该项
// 真实状态回填(不盲信开关), Snackbar 反馈; busy 标志防连点。
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

    var oifaceAvail by remember { mutableStateOf(false) }
    var oifaceOn by remember { mutableStateOf(false) }
    var horaeAvail by remember { mutableStateOf(false) }
    var horaeOn by remember { mutableStateOf(false) }
    var gameOptAvail by remember { mutableStateOf(false) }
    var gameOptOn by remember { mutableStateOf(false) }
    var cpuLimitAvail by remember { mutableStateOf(false) }
    var cpuLimitOn by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }

    // 进页探测: root / 设备识别 / 四项可用性与当前状态, 全部 IO 线程 + 容错。
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                rooted = RootShell.isRooted()
                colorOs = ColorOsTweaks.isColorOs()
                oifaceAvail = ColorOsTweaks.oifaceAvailable()
                if (oifaceAvail) oifaceOn = ColorOsTweaks.isOifaceOn()
                horaeAvail = ColorOsTweaks.horaeAvailable()
                if (horaeAvail) horaeOn = ColorOsTweaks.isHoraeOn()
                gameOptAvail = ColorOsTweaks.gameOptAvailable()
                if (gameOptAvail) gameOptOn = ColorOsTweaks.isGameOptOn()
                cpuLimitAvail = ColorOsTweaks.cpuLimitAvailable()
                if (cpuLimitAvail) cpuLimitOn = ColorOsTweaks.isCpuLimitOn()
            }
        }
        loaded = true
    }

    // 通用写入器: IO 线程执行 write, 完成后重读真实状态回填, Snackbar 反馈; busy 防连点。
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

    // 不可用项在副文案后追加标注。
    fun rowDesc(base: String, avail: Boolean): String =
        if (avail) base else "$base · 本机无此项"

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
            if (loaded && !rooted) {
                HintCard("需要 Root: 未检测到 root 权限, 以下开关将无法生效。")
            }
            if (loaded && !colorOs) {
                HintCard("本机不是 ColorOS/OPPO 设备, 以下开关可能无效。")
            }

            SectionTitle("系统服务")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TweakRow(
                        title = "oiface",
                        desc = rowDesc("与线程、风驰、触控相关", oifaceAvail),
                        checked = oifaceAvail && oifaceOn,
                        enabled = loaded && oifaceAvail && !busy,
                        onToggle = { on ->
                            applyTweak(
                                "oiface",
                                write = { ColorOsTweaks.setOiface(on) },
                                reload = { oifaceOn = ColorOsTweaks.isOifaceOn() },
                            )
                        },
                    )
                    HorizontalDivider()
                    TweakRow(
                        title = "horae",
                        desc = rowDesc("与调度、温控相关; 关闭有过热风险, 重启自动恢复", horaeAvail),
                        checked = horaeAvail && horaeOn,
                        enabled = loaded && horaeAvail && !busy,
                        onToggle = { on ->
                            applyTweak(
                                "horae",
                                write = { ColorOsTweaks.setHorae(on) },
                                reload = { horaeOn = ColorOsTweaks.isHoraeOn() },
                            )
                        },
                    )
                    HorizontalDivider()
                    TweakRow(
                        title = "GameOpt",
                        desc = rowDesc("游戏调度与游戏工具箱功能", gameOptAvail),
                        checked = gameOptAvail && gameOptOn,
                        enabled = loaded && gameOptAvail && !busy,
                        onToggle = { on ->
                            applyTweak(
                                "GameOpt",
                                write = { ColorOsTweaks.setGameOpt(on) },
                                reload = { gameOptOn = ColorOsTweaks.isGameOptOn() },
                            )
                        },
                    )
                    HorizontalDivider()
                    // 开=限制生效, 关=解除限制(更烫更耗电); 均可逆, 重启后恢复系统默认。
                    TweakRow(
                        title = "CPU 频率限制",
                        desc = rowDesc(
                            "根据帧率调节 CPU 频率限制; 关闭即解除限频(重启恢复)",
                            cpuLimitAvail,
                        ),
                        checked = cpuLimitAvail && cpuLimitOn,
                        enabled = loaded && cpuLimitAvail && !busy,
                        onToggle = { on ->
                            applyTweak(
                                "CPU 频率限制",
                                write = { ColorOsTweaks.setCpuLimit(on) },
                                reload = { cpuLimitOn = ColorOsTweaks.isCpuLimitOn() },
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(96.dp))
        }
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
