// 内存详情页(M6): 物理内存/Swap 概览、ZRAM 状态与算法切换、VM 内核参数编辑、meminfo 全量列表。
package com.ktwo.cts.ui.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ktwo.cts.data.MemoryDetail
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.ui.TonalCard
import com.ktwo.cts.ui.theme.MonoNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

// 可编辑 VM 参数的取值范围(null = 自由输入非负整数)。swappiness 新内核上限 200。
private val VM_RANGES: Map<String, LongRange?> = mapOf(
    "swappiness" to 0L..200L,
    "vfs_cache_pressure" to 0L..10000L,
    "dirty_ratio" to 0L..100L,
    "dirty_background_ratio" to 0L..100L,
    "min_free_kbytes" to null,
    "watermark_scale_factor" to 1L..3000L,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryDetailScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var loaded by remember { mutableStateOf(false) }
    var rooted by remember { mutableStateOf(false) }
    var snap by remember { mutableStateOf(MemoryDetail.Snapshot()) }
    var refreshKey by remember { mutableIntStateOf(0) }

    var meminfoExpanded by remember { mutableStateOf(false) }
    var editVmKey by remember { mutableStateOf<String?>(null) }
    var showAlgoPicker by remember { mutableStateOf(false) }
    var confirmAlgo by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // 每 2s 采一次快照(IO 线程); refreshKey 自增可立即重启一轮(写参数/切算法后)。
    LaunchedEffect(refreshKey) {
        while (isActive) {
            val (r, s) = withContext(Dispatchers.IO) {
                val ok = runCatching { RootShell.isRooted() }.getOrDefault(false)
                ok to runCatching { MemoryDetail.snapshot() }.getOrDefault(MemoryDetail.Snapshot())
            }
            rooted = r; snap = s; loaded = true
            delay(2000)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("内存详情") },
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
            if (!loaded) {
                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                OverviewCard(snap)
                ZramCard(
                    zram = snap.zram,
                    rooted = rooted,
                    busy = busy,
                    onSwitchAlgo = {
                        if (!rooted) scope.launch { snackbar.showSnackbar("切换 ZRAM 算法需要 Root") }
                        else showAlgoPicker = true
                    },
                )
                VmParamsCard(
                    params = snap.vmParams,
                    onEdit = { key ->
                        if (!rooted) scope.launch { snackbar.showSnackbar("修改 VM 参数需要 Root") }
                        else editVmKey = key
                    },
                )
                MeminfoCard(
                    meminfo = snap.meminfo,
                    expanded = meminfoExpanded,
                    onToggle = { meminfoExpanded = !meminfoExpanded },
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ── ZRAM 算法选择 → 二次确认 ──
    if (showAlgoPicker) {
        AlgoPickerDialog(
            current = snap.zram.algorithm,
            candidates = snap.zram.candidates,
            onPick = { showAlgoPicker = false; confirmAlgo = it },
            onDismiss = { showAlgoPicker = false },
        )
    }
    val pendingAlgo = confirmAlgo
    if (pendingAlgo != null) {
        AlertDialog(
            onDismissRequest = { confirmAlgo = null },
            title = { Text("切换 ZRAM 压缩算法") },
            text = {
                Text(
                    "将执行 ZRAM 重建: 先关闭交换分区, 把已压缩的数据换回内存, 再以「$pendingAlgo」重新启用。" +
                        "内存吃紧时可能出现数秒卡顿, 极端情况下可能触发系统清理后台应用。确定继续吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmAlgo = null
                    busy = true
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                MemoryDetail.setZramAlgorithm(pendingAlgo, snap.zram.diskSizeBytes)
                            }.getOrDefault(false)
                        }
                        busy = false
                        refreshKey++
                        snackbar.showSnackbar(
                            if (ok) "已切换到 $pendingAlgo 并重建 ZRAM"
                            else "切换失败, 请检查 Root 权限或内核支持"
                        )
                    }
                }) { Text("切换", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAlgo = null }) { Text("取消") }
            },
        )
    }

    // ── VM 参数编辑 ──
    val vmKey = editVmKey
    if (vmKey != null) {
        VmEditDialog(
            key = vmKey,
            current = snap.vmParams.firstOrNull { it.first == vmKey }?.second.orEmpty(),
            onDismiss = { editVmKey = null },
            onConfirm = { value ->
                editVmKey = null
                scope.launch {
                    val newVal = withContext(Dispatchers.IO) {
                        runCatching { MemoryDetail.writeVmParam(vmKey, value) }.getOrNull()
                    }
                    refreshKey++
                    snackbar.showSnackbar(
                        if (newVal != null) "$vmKey 已写入, 当前值 $newVal"
                        else "写入失败, 请检查 Root 权限或取值范围"
                    )
                }
            },
        )
    }
}

// ── 顶部概览卡: 物理内存 + Swap + 换入换出速率 ──
@Composable
private fun OverviewCard(snap: MemoryDetail.Snapshot) {
    val totalKb = snap.memTotalKb
    val usedKb = (totalKb - snap.memAvailableKb).coerceAtLeast(0)
    val pct = if (totalKb > 0) usedKb * 100f / totalKb else 0f
    val swapTotalKb = snap.swapTotalKb
    val swapUsedKb = (swapTotalKb - snap.swapFreeKb).coerceAtLeast(0)

    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("物理内存", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    String.format(Locale.US, "%.0f%%", pct),
                    style = MonoNumber.copy(fontSize = 30.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${fmtKb(usedKb)} / ${fmtKb(totalKb)}",
                    style = MonoNumber.copy(fontSize = 14.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (pct / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
            )
            Spacer(Modifier.height(14.dp))
            KvRow("Swap 已用", if (swapTotalKb > 0) "${fmtKb(swapUsedKb)} / ${fmtKb(swapTotalKb)}" else "未启用")
            KvRow("Swap 换入", String.format(Locale.US, "%.0f KB/s", snap.swapInKbps))
            KvRow("Swap 换出", String.format(Locale.US, "%.0f KB/s", snap.swapOutKbps))
        }
    }
}

// ── ZRAM 卡 ──
@Composable
private fun ZramCard(
    zram: MemoryDetail.ZramInfo,
    rooted: Boolean,
    busy: Boolean,
    onSwitchAlgo: () -> Unit,
) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("ZRAM", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (!zram.available) {
                Text(
                    "本机未启用 ZRAM",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                KvRow("压缩算法", zram.algorithm.ifEmpty { "--" })
                KvRow("容量 disksize", fmtBytes(zram.diskSizeBytes))
                KvRow("实际内存占用", fmtBytes(zram.memUsedBytes))
                KvRow("原始数据", fmtBytes(zram.origDataBytes))
                KvRow("压缩后", fmtBytes(zram.comprDataBytes))
                KvRow(
                    "压缩比",
                    if (zram.ratio > 0) String.format(Locale.US, "%.2f : 1", zram.ratio) else "--",
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onSwitchAlgo,
                    enabled = !busy && zram.candidates.size > 1,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "正在重建 ZRAM…" else "切换压缩算法") }
                if (!rooted) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "切换算法需要 Root",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── VM 参数卡 ──
@Composable
private fun VmParamsCard(
    params: List<Pair<String, String>>,
    onEdit: (String) -> Unit,
) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Text(
                "VM 参数",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "点击一行修改; 立即生效, 重启后恢复内核默认",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
            if (params.isEmpty()) {
                Text(
                    "未读取到 VM 参数",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                params.forEach { (k, v) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(k) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            v,
                            style = MonoNumber.copy(fontSize = 14.sp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

// ── meminfo 全量卡(可折叠)──
@Composable
private fun MeminfoCard(
    meminfo: List<Pair<String, Long>>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("meminfo 全量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${meminfo.size} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    meminfo.forEach { (k, kb) -> KvRow(k, fmtKb(kb)) }
                }
            }
        }
    }
}

// ── 弹窗: ZRAM 算法候选 ──
@Composable
private fun AlgoPickerDialog(
    current: String,
    candidates: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择压缩算法") },
        text = {
            Column {
                candidates.forEach { algo ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = algo }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == algo, onClick = { selected = algo })
                        Text(algo + if (algo == current) "(当前)" else "")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onPick(selected) },
                enabled = selected.isNotEmpty() && selected != current,
            ) { Text("下一步") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ── 弹窗: VM 参数编辑 ──
@Composable
private fun VmEditDialog(
    key: String,
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(key) { mutableStateOf(current) }
    val range = VM_RANGES[key]
    val num = text.trim().toLongOrNull()
    val valid = num != null && num >= 0 && (range == null || num in range)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(key) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() } },
                    singleLine = true,
                    label = { Text("新值") },
                    isError = text.isNotEmpty() && !valid,
                    supportingText = {
                        Text(
                            if (range != null) "范围 ${range.first} - ${range.last} · 当前 $current"
                            else "非负整数 · 当前 $current"
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = valid) { Text("写入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ── 小组件与格式化 ──
@Composable
private fun KvRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            value,
            style = MonoNumber.copy(fontSize = 13.sp),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

// kB 数值友好显示: 小于 1MB 显示 kB, 小于 1GB 显示 MB, 否则 GB。
private fun fmtKb(kb: Long): String {
    if (kb < 1024) return "$kb kB"
    val mb = kb / 1024.0
    return if (mb < 1024) String.format(Locale.US, "%.1f MB", mb)
           else String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun fmtBytes(bytes: Long): String = fmtKb(bytes / 1024)
