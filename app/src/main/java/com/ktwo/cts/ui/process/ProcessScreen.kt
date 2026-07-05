// 进程管理器(M4): 全量进程按应用分组展示, 搜索/过滤/排序, 详情底部弹层 + 结束/强停。需 Root。
package com.ktwo.cts.ui.process

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ktwo.cts.data.ProcInfo
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.ui.TonalCard
import com.ktwo.cts.ui.theme.MonoNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** 一组进程(同一应用/同一进程名主段)。 */
private data class ProcGroup(
    val key: String,                          // 分组键 = 进程名主段(候选包名)
    val label: String,                        // 应用名, 解析不到用 key
    val isApp: Boolean,                       // 主段含 '.' 且能解析到已安装应用
    val procs: List<ProcInfo.ProcFull>,
    val cpuSum: Float,
    val memSum: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var rooted by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var procs by remember { mutableStateOf<List<ProcInfo.ProcFull>>(emptyList()) }
    var labels by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    var appsOnly by remember { mutableStateOf(false) }
    var sortByCpu by remember { mutableStateOf(true) }
    var expandedKeys by remember { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf<ProcInfo.ProcFull?>(null) }
    var detail by remember { mutableStateOf<ProcInfo.ProcDetail?>(null) }
    var confirmKill by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // 每 2s 刷新(离开页面自动取消); refreshKey 自增可立即重启一轮(kill/强停后)。
    LaunchedEffect(refreshKey) {
        while (true) {
            val (r, list, lab) = withContext(Dispatchers.IO) {
                val ok = runCatching { RootShell.isRooted() }.getOrDefault(false)
                val l = if (ok) runCatching { ProcInfo.listProcesses() }.getOrDefault(emptyList())
                        else emptyList()
                val m = l.asSequence().map { ProcInfo.mainPkg(it.name) }.distinct()
                    .associateWith { k -> ProcInfo.appLabel(ctx, k) }
                Triple(ok, l, m)
            }
            rooted = r; procs = list; labels = lab; loaded = true
            delay(2000)
        }
    }

    // 选中进程后在 IO 线程拉详情
    LaunchedEffect(selected?.pid) {
        detail = null
        val pid = selected?.pid ?: return@LaunchedEffect
        detail = withContext(Dispatchers.IO) {
            runCatching { ProcInfo.detail(pid) }.getOrNull()
        }
    }

    // 过滤 + 分组 + 排序
    val groups = remember(procs, labels, query, appsOnly, sortByCpu) {
        val q = query.trim()
        procs
            .filter {
                q.isEmpty() || it.name.contains(q, ignoreCase = true) ||
                    it.cmdline.contains(q, ignoreCase = true) || it.pid.toString() == q
            }
            .groupBy { ProcInfo.mainPkg(it.name) }
            .map { (key, list) ->
                val label = labels[key]
                val inner = if (sortByCpu) list.sortedByDescending { it.cpuPct }
                            else list.sortedByDescending { it.rssMb }
                ProcGroup(
                    key = key,
                    label = label ?: key,
                    isApp = key.contains('.') && label != null,
                    procs = inner,
                    cpuSum = list.fold(0f) { a, p -> a + p.cpuPct },
                    memSum = list.fold(0) { a, p -> a + p.rssMb.coerceAtLeast(0) },
                )
            }
            .filter { !appsOnly || it.isApp }
            .sortedByDescending { if (sortByCpu) it.cpuSum.toDouble() else it.memSum.toDouble() }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("进程管理器") },
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
        Column(Modifier.fillMaxSize().padding(inner)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索进程名") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = !appsOnly, onClick = { appsOnly = false },
                    label = { Text("全部") }, shape = RoundedCornerShape(12.dp),
                )
                FilterChip(
                    selected = appsOnly, onClick = { appsOnly = true },
                    label = { Text("仅应用") }, shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = sortByCpu, onClick = { sortByCpu = true },
                    label = { Text("CPU") }, shape = RoundedCornerShape(12.dp),
                )
                FilterChip(
                    selected = !sortByCpu, onClick = { sortByCpu = false },
                    label = { Text("内存") }, shape = RoundedCornerShape(12.dp),
                )
            }

            when {
                loaded && !rooted -> HintCard("需要 Root", "进程列表依赖 Root 权限读取, 请先授予本应用 Root。")
                loaded && groups.isEmpty() ->
                    HintCard("暂无进程数据", if (query.isBlank()) "未读取到进程信息。" else "没有匹配「${query.trim()}」的进程。")
                !loaded -> HintCard("读取中…", "正在获取进程列表。")
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(groups, key = { it.key }) { g ->
                        GroupCard(
                            group = g,
                            expanded = g.key in expandedKeys,
                            onToggle = {
                                expandedKeys =
                                    if (g.key in expandedKeys) expandedKeys - g.key
                                    else expandedKeys + g.key
                            },
                            onClickProc = { selected = it },
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }

    // ── 详情底部弹层 ──
    val sel = selected
    if (sel != null) {
        val pkg = ProcInfo.mainPkg(sel.name)
        val appName = labels[pkg]
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            val d = detail
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .navigationBarsPadding(),
            ) {
                Text(
                    appName ?: sel.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    sel.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                val rows = listOf(
                    "App" to (appName ?: "--"),
                    "PID" to sel.pid.toString(),
                    "PPID" to (d?.ppid ?: "--"),
                    "用户" to sel.user.ifBlank { "--" },
                    "状态" to (d?.state ?: sel.state.toString()),
                    "oom_adj" to (d?.oomAdj ?: "--"),
                    "oom_score_adj" to (d?.oomScoreAdj ?: "--"),
                    "cpuset" to (d?.cpuset ?: "--"),
                    "cgroup" to (d?.cgroup ?: "--"),
                    "可用 CPU 核" to (d?.cpusAllowed ?: "--"),
                    "RES" to (d?.resMb ?: if (sel.rssMb >= 0) "${sel.rssMb} MB" else "--"),
                    "SWAP" to (d?.swapMb ?: "--"),
                    "CPU%" to String.format(Locale.US, "%.1f%%", sel.cpuPct),
                    "Command" to (d?.command ?: sel.name),
                    "Cmdline" to (d?.cmdline?.ifBlank { "--" } ?: sel.cmdline.ifBlank { "--" }),
                )
                rows.forEach { (k, v) -> DetailRow(k, v) }
                if (d == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "详情读取中(读不到的字段显示 --)…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { confirmKill = true },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("结束进程") }
                    Button(
                        onClick = { confirmStop = true },
                        modifier = Modifier.weight(1f),
                        enabled = appName != null,
                    ) { Text("强制停止应用") }
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        if (confirmKill) {
            AlertDialog(
                onDismissRequest = { confirmKill = false },
                title = { Text("结束进程") },
                text = { Text("确定向进程 ${sel.name}(PID ${sel.pid})发送 kill -9 吗？未保存的数据可能丢失。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmKill = false
                        selected = null
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { ProcInfo.kill(sel.pid) } }
                            refreshKey++
                        }
                    }) { Text("结束", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmKill = false }) { Text("取消") }
                },
            )
        }
        if (confirmStop) {
            AlertDialog(
                onDismissRequest = { confirmStop = false },
                title = { Text("强制停止应用") },
                text = { Text("确定强制停止「${appName ?: pkg}」($pkg)吗？该应用的全部进程都会被停止。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmStop = false
                        selected = null
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { ProcInfo.forceStop(pkg) } }
                            refreshKey++
                        }
                    }) { Text("停止", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmStop = false }) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun HintCard(title: String, desc: String) {
    TonalCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun GroupCard(
    group: ProcGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onClickProc: (ProcInfo.ProcFull) -> Unit,
) {
    val ctx = LocalContext.current
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 应用图标: 取得到画图标, 取不到用占位芯片图标
                val bmp = remember(group.key) {
                    runCatching {
                        ProcInfo.appIcon(ctx, group.key)?.toBitmap(96, 96)
                    }.getOrNull()
                }
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        Image(
                            painter = BitmapPainter(bmp.asImageBitmap()),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Memory, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        "${group.procs.size} 个进程",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    String.format(Locale.US, "%.1f%%", group.cpuSum),
                    style = MonoNumber.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp
                    else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                group.procs.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onClickProc(p) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            p.name,
                            fontSize = 13.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            p.pid.toString(),
                            style = MonoNumber.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        Text(
                            String.format(Locale.US, "%.1f%%", p.cpuPct),
                            style = MonoNumber.copy(fontSize = 12.sp),
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(56.dp),
                        )
                        Text(
                            if (p.rssMb >= 0) "${p.rssMb} MB" else "--",
                            style = MonoNumber.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(72.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(112.dp),
        )
        Text(
            value,
            style = MonoNumber.copy(fontSize = 12.sp),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
