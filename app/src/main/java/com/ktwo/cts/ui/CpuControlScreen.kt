package com.ktwo.cts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ktwo.cts.data.ClusterModelCfg
import com.ktwo.cts.data.Mode
import com.ktwo.cts.data.PolicyMap
import com.ktwo.cts.data.ProfileModel
import kotlinx.coroutines.delay

/**
 * CPU 控制面板(方案丙:画像编辑器)。
 * 顶部画像选择器选中哪个画像,下面就编辑 config.json 里那个 model 的
 * 频率 / 调速器 / GPU / 核心开关。改完「应用」写回 config.json,daemon inotify 热重载。
 * 顶栏显示实时功耗 + 主频(daemon status.json 或 App 自读 sysfs)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpuControlScreen(vm: CpuControlVm = viewModel()) {
    val cfg by vm.cfg.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val saveResult by vm.saveResult.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(saveResult) {
        when (saveResult) {
            true -> snackbar.showSnackbar("已写入 config.json,daemon 将热重载")
            false -> snackbar.showSnackbar("写入失败,请检查 Root 权限")
            null -> {}
        }
        if (saveResult != null) { delay(100); vm.clearSaveResult() }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("CPU 控制", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                actions = { PowerFreqBadge(watt = live.watt, mainMhz = mainMhz(live)) },
            )
        },
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
            HelpCard()

            // 画像选择器(方案丙核心)
            ProfileSelectorCard(
                profiles = cfg.profileNames,
                selected = selected,
                onSelect = vm::selectProfile,
            )

            val prof = editing

            if (prof == null) {
                if (!loading) {
                    TonalCard(Modifier.fillMaxWidth()) {
                        Text(
                            "config.json 中没有画像(models)。请确认 daemon 已生成配置文件。",
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                prof.clusters.forEach { c ->
                    ClusterCard(
                        cfg = c,
                        displayName = vm.clusterName(c.policy),
                        curMhz = live.clusterFreqMhz[c.policy] ?: -1,
                        liveGov = live.clusterGov[c.policy].orEmpty(),
                        availFreqsKhz = vm.availFreqsKhz(c.policy),
                        availGovs = vm.availGovernors(c.policy),
                        onMin = { vm.setClusterMin(c.cIndex, it) },
                        onMax = { vm.setClusterMax(c.cIndex, it) },
                        onGov = { vm.setClusterGov(c.cIndex, it) },
                    )
                }

                GpuCard(
                    minMhz = prof.gpuMinInt,
                    maxMhz = prof.gpuMaxInt,
                    curMhz = live.gpuMhz,
                    availMhz = vm.availGpuMhz(),
                    onChange = vm::setGpu,
                )

                CoreOnlineCard(prof.coreOnline, vm::toggleCore)

                Button(
                    onClick = vm::save,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("应用「${profileLabel(selected)}」配置", fontWeight = FontWeight.SemiBold) }
            }

            Spacer(Modifier.height(96.dp))
        }
    }
}

/** 顶栏功耗 + 主频徽章,对应截图 "1.13W 883MHz" */
@Composable
private fun PowerFreqBadge(watt: Double, mainMhz: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(end = 12.dp),
    ) {
        if (watt > 0.0) {
            Text(
                "%.2fW".format(watt),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (mainMhz > 0) {
            Text(
                "${mainMhz}MHz",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 主频 = 所有 policy 当前频率里的最大值(通常是大核) */
private fun mainMhz(live: com.ktwo.cts.data.LiveStatus): Int =
    live.clusterFreqMhz.values.filter { it > 0 }.maxOrNull() ?: -1

/** 画像 key -> 中文标签 */
private fun profileLabel(key: String): String = Mode.fromKey(key).label.ifEmpty { key }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSelectorCard(
    profiles: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("编辑画像", fontWeight = FontWeight.Bold)
            Text(
                "选择要编辑的性能画像。daemon 会按前台 App 自动匹配对应画像。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                profiles.forEach { name ->
                    FilterChip(
                        selected = name == selected,
                        onClick = { onSelect(name) },
                        label = { Text(profileLabel(name)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HelpCard() {
    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("帮助", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(
                "部分手机具有 CPU 参数动态调整机制,会导致自定义的参数很快失效,此外,温控也会限制你调高 CPU 频率。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ClusterCard(
    cfg: ClusterModelCfg,
    displayName: String,
    curMhz: Int,
    liveGov: String,
    availFreqsKhz: List<Long>,
    availGovs: List<String>,
    onMin: (Long) -> Unit,
    onMax: (Long) -> Unit,
    onGov: (String) -> Unit,
) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("CPU - $displayName", fontWeight = FontWeight.Bold)
                if (curMhz > 0) {
                    Text(
                        "实时 ${curMhz}MHz",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                "policy${cfg.policy}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FreqPickerRow("最小频率", cfg.minKhzLong, availFreqsKhz, onMin)
            FreqPickerRow("最大频率", cfg.maxKhzLong, availFreqsKhz, onMax)
            // 调速器: 空 = 跟随系统(config 写 gov=""), liveGov 仅作当前实际值提示
            GovPickerRow("调速器", cfg.governor, liveGov, availGovs, onGov)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreqPickerRow(
    label: String,
    valueKhz: Long,
    availKhz: List<Long>,
    onPick: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            AssistChip(
                onClick = { if (availKhz.isNotEmpty()) expanded = true },
                label = { Text(if (valueKhz > 0) "${valueKhz / 1000} MHz" else "—") },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                availKhz.forEach { khz ->
                    DropdownMenuItem(
                        text = { Text("${khz / 1000} MHz") },
                        onClick = { onPick(khz); expanded = false },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GovPickerRow(
    label: String,
    value: String,        // 空白 = 跟随系统(config 里 gov="")
    liveGov: String,      // 当前系统实际调速器, 仅在「跟随系统」时作提示
    avail: List<String>,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val follow = value.isBlank()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            AssistChip(
                onClick = { expanded = true },   // 「跟随系统」始终可选, 即使读不到 avail
                label = {
                    Text(
                        if (follow)
                            if (liveGov.isNotBlank()) "跟随系统 ($liveGov)" else "跟随系统"
                        else value
                    )
                },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                // 跟随系统 = 写空串, daemon 不强制覆盖调速器
                DropdownMenuItem(
                    text = { Text("跟随系统") },
                    onClick = { onPick(""); expanded = false },
                )
                avail.forEach { g ->
                    DropdownMenuItem(text = { Text(g) }, onClick = { onPick(g); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun GpuCard(
    minMhz: Int,
    maxMhz: Int,
    curMhz: Int,
    availMhz: List<Int>,
    onChange: (Int, Int) -> Unit,
) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("GPU", fontWeight = FontWeight.Bold)
                if (curMhz > 0) {
                    Text(
                        "实时 ${curMhz}MHz",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            // 候选项从 sysfs 的 GPU 可用频率表读;读不到则禁用选择,仅显示当前值
            MhzPickerRow("最小频率", minMhz, availMhz) { onChange(it, maxMhz) }
            MhzPickerRow("最大频率", maxMhz, availMhz) { onChange(minMhz, it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MhzPickerRow(
    label: String,
    valueMhz: Int,
    availMhz: List<Int>,
    onPick: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            AssistChip(
                onClick = { if (availMhz.isNotEmpty()) expanded = true },
                label = { Text(if (valueMhz > 0) "$valueMhz MHz" else "—") },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                availMhz.forEach { mhz ->
                    DropdownMenuItem(
                        text = { Text("$mhz MHz") },
                        onClick = { onPick(mhz); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun CoreOnlineCard(online: List<Int>, onToggle: (Int) -> Unit) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Core Online", fontWeight = FontWeight.Bold)
            for (row in 0 until 2) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (col in 0 until 4) {
                        val idx = row * 4 + col
                        // -1(不指定)在 UI 上当作开
                        val on = online.getOrElse(idx) { 1 } != 0
                        // 竖排居中: 复选框在上、CPU 标签在下,文字不换行
                        Column(
                            Modifier.weight(1f).clickable { onToggle(idx) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Checkbox(checked = on, onCheckedChange = { onToggle(idx) })
                            Text(
                                "CPU$idx",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
            Text(
                "取消勾选将让对应核心离线(Core=0)。大核全离线会显著影响性能。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
