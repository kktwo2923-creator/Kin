// 「实时优先级规则」页: per-app「线程名通配 → 调度策略(SCHED_FIFO/RR/OTHER)+优先级」管理。
// 与「线程规则」页(绑核)平行, 由守护读 thread_priority_rules.conf 用 sched_setscheduler 应用。
// 语法与 AppOpt priority.prop 兼容, 可与其互导。需先在「线程分配」页打开「实时调度」开关才生效。
package com.ktwo.cts.ui.threadalloc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ktwo.cts.data.TaPrioRule
import com.ktwo.cts.data.ThreadPrioRuleStore
import com.ktwo.cts.data.ThreadPrioRules
import kotlinx.coroutines.launch

private fun prioLabelOf(ctx: Context, pkg: String): String = runCatching {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg.substringBefore(':'), 0)).toString()
}.getOrDefault(pkg)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadPriorityScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val rules by ThreadPrioRuleStore.flow(ctx).collectAsState(initial = emptyMap())

    var editPkg by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }
    fun clipboard(): ClipboardManager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("实时优先级规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val text = runCatching {
                            clipboard().primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                        }.getOrNull().orEmpty()
                        if (text.isBlank()) { toast("剪贴板为空"); return@IconButton }
                        scope.launch {
                            val parsed = runCatching { ThreadPrioRules.parse(text) }.getOrDefault(linkedMapOf())
                            if (parsed.isEmpty()) { toast("剪贴板内容不是有效的 priority.prop 配置"); return@launch }
                            val n = ThreadPrioRuleStore.importRules(ctx, parsed)
                            toast("已导入 $n 个应用的实时优先级规则")
                        }
                    }) { Icon(Icons.Outlined.ContentPaste, contentDescription = "从剪贴板导入") }
                    IconButton(onClick = {
                        scope.launch {
                            val text = ThreadPrioRuleStore.exportText(ctx) { prioLabelOf(ctx, it) }
                            if (text.isBlank()) { toast("还没有任何规则"); return@launch }
                            runCatching { clipboard().setPrimaryClip(ClipData.newPlainText("priority.prop", text)) }
                            toast("已导出到剪贴板(priority.prop 格式)")
                        }
                    }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "导出到剪贴板") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("添加应用") },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "为关键线程设置实时调度策略(SCHED_FIFO/RR), 让它抢占普通线程更不掉帧。" +
                        "优先级建议取低值(1-3), 过高可能拖慢系统。需先在上一页打开「实时调度」开关。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (rules.isEmpty()) item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("还没有配置任何应用", fontWeight = FontWeight.Medium)
                        Text(
                            "点右下角「添加应用」, 或从剪贴板导入 priority.prop",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(rules.keys.sorted(), key = { it }) { pkg ->
                val rs = rules[pkg].orEmpty()
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().clickable { editPkg = pkg },
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    prioLabelOf(ctx, pkg), fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(6.dp))
                                if (ThreadPrioRules.hasRealtime(rs)) Text(
                                    "实时", style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFB8860B),
                                    modifier = Modifier
                                        .background(Color(0xFFB8860B).copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                            Text(
                                pkg, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${rs.count { it.thread.isNotEmpty() }} 条线程规则",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { scope.launch { ThreadPrioRuleStore.remove(ctx, listOf(pkg)) } }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    if (showAdd) PrioAddAppDialog(
        onDismiss = { showAdd = false },
        onAdd = { pkg -> showAdd = false; editPkg = pkg },
    )

    editPkg?.let { pkg ->
        PrioEditorSheet(
            pkg = pkg,
            initial = rules[pkg].orEmpty(),
            onDismiss = { editPkg = null },
            onSave = { newRules ->
                scope.launch { ThreadPrioRuleStore.put(ctx, pkg, newRules); editPkg = null }
            },
        )
    }
}

@Composable
private fun PrioAddAppDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val ctx = LocalContext.current
    var filter by remember { mutableStateOf("") }
    val apps = remember {
        runCatching {
            ctx.packageManager.getInstalledApplications(0)
                .filter { ctx.packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .map { it.packageName to ctx.packageManager.getApplicationLabel(it).toString() }
                .sortedBy { it.second.lowercase() }
        }.getOrDefault(emptyList())
    }
    val shown = remember(filter, apps) {
        if (filter.isBlank()) apps
        else apps.filter { it.first.contains(filter, true) || it.second.contains(filter, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用") },
        text = {
            Column {
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it },
                    placeholder = { Text("搜索应用名/包名") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(320.dp)) {
                    items(shown, key = { it.first }) { (pkg, label) ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onAdd(pkg) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pkg, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrioEditorSheet(
    pkg: String,
    initial: List<TaPrioRule>,
    onDismiss: () -> Unit,
    onSave: (List<TaPrioRule>) -> Unit,
) {
    val ctx = LocalContext.current
    // 首次默认给个安全的具体示例(UnityMain→FIFO 2): 只命中单个线程、优先级低;
    // 非 Unity 应用不匹配也无害。空线程名+实时会把「所有线程」提为实时, 故不做默认。
    var rows by remember(pkg, initial) {
        mutableStateOf(if (initial.isEmpty()) listOf(TaPrioRule("UnityMain", ThreadPrioRules.FIFO, 2)) else initial)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(prioLabelOf(ctx, pkg), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "SCHED_FIFO/RR 为实时调度(优先级 1-99, 建议 1-3); SCHED_OTHER 为普通(优先级 0)。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))

            Text("套用预设", fontWeight = FontWeight.Medium)
            LazyColumn(Modifier.heightIn(max = 116.dp)) {
                items(ThreadPrioRules.presets(), key = { it.id }) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { rows = p.build(pkg) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyMedium)
                            Text(p.desc, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("规则(线程名通配 = 策略 + 优先级, 线程名留空 = 该应用默认)", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(rows.size) { i ->
                    val r = rows[i]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = r.thread,
                            onValueChange = { v -> rows = rows.toMutableList().also { it[i] = r.copy(thread = v) } },
                            placeholder = { Text("线程名, 如 UnityMain") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            singleLine = true, modifier = Modifier.weight(1.5f),
                        )
                        Spacer(Modifier.width(6.dp))
                        PolicyDropdown(
                            policy = r.policy,
                            onSelect = { pol -> rows = rows.toMutableList().also { it[i] = r.copy(policy = pol) } },
                            modifier = Modifier.weight(1.1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = if (r.policy == ThreadPrioRules.OTHER) "0" else r.prio.toString(),
                            onValueChange = { v ->
                                val p = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                                rows = rows.toMutableList().also { it[i] = r.copy(prio = p) }
                            },
                            enabled = r.policy != ThreadPrioRules.OTHER,
                            placeholder = { Text("1-99") },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            singleLine = true, modifier = Modifier.weight(0.7f),
                        )
                        IconButton(onClick = { rows = rows.toMutableList().also { it.removeAt(i) } }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删行",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            TextButton(onClick = { rows = rows + TaPrioRule("", ThreadPrioRules.OTHER, 0) }) { Text("+ 加一行") }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { onSave(ThreadPrioRules.normalize(rows)) },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun PolicyDropdown(policy: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        // 短标签(去掉 SCHED_ 前缀)更省地方; 点开选调度策略
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(policy.removePrefix("SCHED_"), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThreadPrioRules.POLICIES.forEach { pol ->
                DropdownMenuItem(
                    text = { Text(pol.removePrefix("SCHED_")) },
                    onClick = { onSelect(pol); expanded = false },
                )
            }
        }
    }
}
