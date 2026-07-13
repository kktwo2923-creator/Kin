package com.ktwo.cts.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Tune
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

/**
 * CPU 控制面板:编辑 config.json 里选中 model 的频率/调速器/GPU/核心,写回后 daemon 热重载。
 * 调速器参数编辑入口整合在本页(不再在「功能」页单列), 点击跳转 GovernorParamsScreen。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpuControlScreen(
    onOpenGovernor: () -> Unit = {},
    vm: CpuControlVm = viewModel(),
) {
    val cfg by vm.cfg.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val soc by vm.soc.collectAsStateWithLifecycle()
    var showRestoreDialog by remember { mutableStateOf(false) }

    val snackbar = remember { SnackbarHostState() }
    // 用 collect 先 clear 再 showSnackbar:避免离开页面协程被取消导致结果永留 VM 而重放,
    // 也避免 clear 改 key 把未显示的 Snackbar 取消。
    LaunchedEffect(Unit) {
        vm.saveResult.collect { r ->
            if (r != null) {
                vm.clearSaveResult()
                snackbar.showSnackbar(
                    if (r) "已写入 config.json,daemon 将热重载"
                    else "写入失败,请检查 Root 权限"
                )
            }
        }
    }
    LaunchedEffect(Unit) {
        vm.restoreResult.collect { r ->
            if (r != null) {
                vm.clearRestoreResult()
                snackbar.showSnackbar(
                    if (r) "已恢复默认配置(${soc?.configName ?: "config.json"}),daemon 将热重载"
                    else "恢复失败:模块默认配置缺失或无 Root"
                )
            }
        }
    }

    if (showRestoreDialog) {
        RestoreDefaultsDialog(
            configName = soc?.configName ?: "config.json",
            chip = soc?.chip.orEmpty(),
            onConfirm = { alsoPerapp ->
                showRestoreDialog = false
                vm.restoreDefaults(alsoPerapp)
            },
            onDismiss = { showRestoreDialog = false },
        )
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

            SocCard(soc = soc, onRestore = { showRestoreDialog = true })

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

            GovernorEntryCard(onClick = onOpenGovernor)

            Spacer(Modifier.height(96.dp))
        }
    }
}

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

private fun mainMhz(live: com.ktwo.cts.data.LiveStatus): Int =
    live.clusterFreqMhz.values.filter { it > 0 }.maxOrNull() ?: -1

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
private fun SocCard(soc: com.ktwo.cts.root.RootShell.SocInfo?, onRestore: () -> Unit) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SoC", fontWeight = FontWeight.Bold)
                if (soc == null) {
                    Text(
                        "识别中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        buildString {
                            append(soc.socModel.ifEmpty { soc.platform.ifEmpty { "未知" } }.uppercase())
                            if (soc.chip.isNotEmpty()) append("(${soc.chip})")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "默认配置: ${soc.configName}" + if (soc.chip.isEmpty()) "(未识别, 用通用配置)" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = onRestore, enabled = soc != null) { Text("恢复默认") }
        }
    }
}

@Composable
private fun RestoreDefaultsDialog(
    configName: String,
    chip: String,
    onConfirm: (alsoPerapp: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var alsoPerapp by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复默认配置?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "将用模块内置的 $configName" +
                        (if (chip.isNotEmpty()) "(按 SoC $chip 匹配)" else "") +
                        " 覆盖当前 config.json,你的所有画像修改会丢失。daemon 会自动热重载。"
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { alsoPerapp = !alsoPerapp },
                ) {
                    Checkbox(checked = alsoPerapp, onCheckedChange = { alsoPerapp = it })
                    Text("同时恢复应用画像表(perapp_powermode.txt)")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(alsoPerapp) }) { Text("恢复") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 调速器参数编辑入口: 点击跳转独立编辑页(每簇 governor tunable)。 */
@Composable
private fun GovernorEntryCard(onClick: () -> Unit) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Tune, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("调速器参数", fontWeight = FontWeight.Bold)
                Text(
                    "每簇 governor 可调参数精细编辑",
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
            // cfg.governor 传配置里实际写的值(可能为空=留空交ROM);liveGov 仅提示,不写入。
            // 不用 ifEmpty{liveGov}:否则点选就把实测 walt 固化进 config,破坏"留空"语义。
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
    value: String,        // 配置里实际写的值;空 = 留空交 ROM
    liveHint: String,     // 实测值;仅提示,绝不写入 config
    avail: List<String>,
    onPick: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (value.isEmpty() && liveHint.isNotEmpty()) {
                Text(
                    "当前 $liveHint",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            AssistChip(
                onClick = { expanded = true },
                label = { Text(if (value.isNotEmpty()) value else "默认") },
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("默认(留空)") },
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
                        val on = online.getOrElse(idx) { 1 } != 0
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
