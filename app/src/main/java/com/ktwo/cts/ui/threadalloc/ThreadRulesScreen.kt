// 「线程规则管理」(重做版, 三合一): 每应用一个条目, 点开是带三个标签页的编辑器 ——
//   ① 绑核规则: 线程名通配 → 核区间(可点芯片选核, 不用手打), 支持预设/自动识别;
//   ② 实时优先级: 线程名通配 → SCHED_FIFO/RR/OTHER + 优先级(原「实时优先级规则」页并入);
//   ③ 实时线程: 应用正在运行的全部线程(名字/tid/所在核/亲和), 点某条可「仅本次绑核」或
//      「存为规则」, ⚡ 一键把该线程加进实时优先级(原「手动线程绑核」页并入)。
// 顶栏: 自动生成 + 更多菜单(导入内置预设 / 剪贴板导入 / 导出)。语法与 AppOpt 兼容, 可互导。
package com.ktwo.cts.ui.threadalloc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ktwo.cts.data.TaClusterPlan
import com.ktwo.cts.data.TaPrioRule
import com.ktwo.cts.data.TaRule
import com.ktwo.cts.data.ThreadAllocRuleStore
import com.ktwo.cts.data.ThreadAllocRules
import com.ktwo.cts.data.ThreadAllocator
import com.ktwo.cts.data.ThreadPrioRuleStore
import com.ktwo.cts.data.ThreadPrioRules
import com.ktwo.cts.data.fpsrecord.ThreadSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// labelOf / autoDetectRules / detectLabel 在共享的 AutoDetect.kt(本包内直接调用)。

/** 核列表 → "0-3,7" 区间写法(连续段折叠), 与 AppOpt/守护解析约定一致。 */
private fun cpusSpec(cpus: Collection<Int>): String {
    val s = cpus.distinct().sorted()
    if (s.isEmpty()) return ""
    val sb = StringBuilder()
    var i = 0
    while (i < s.size) {
        var j = i
        while (j + 1 < s.size && s[j + 1] == s[j] + 1) j++
        if (sb.isNotEmpty()) sb.append(',')
        if (j > i) sb.append("${s[i]}-${s[j]}") else sb.append("${s[i]}")
        i = j + 1
    }
    return sb.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadRulesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val rules by ThreadAllocRuleStore.flow(ctx).collectAsState(initial = emptyMap())
    val prioRules by ThreadPrioRuleStore.flow(ctx).collectAsState(initial = emptyMap())

    var editPkg by remember { mutableStateOf<String?>(null) }
    var editOverride by remember { mutableStateOf<List<TaRule>?>(null) }  // 自动生成预填
    var showAdd by remember { mutableStateOf(false) }
    var showAutoPick by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    // clusterPlan() 读 sysfs(个别 ROM 回退 root), 不能在组合期主线程跑 → 先用兜底拓扑, 再异步求真值
    var plan by remember { mutableStateOf(ThreadAllocRules.fallbackPlan()) }
    LaunchedEffect(Unit) {
        val p = withContext(Dispatchers.IO) { runCatching { ThreadAllocRules.clusterPlan() }.getOrNull() }
        if (p != null) plan = p
    }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }
    fun clipboard(): ClipboardManager =
        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // 应用列表 = 绑核规则 ∪ 实时优先级规则 覆盖的全部应用
    val allPkgs = remember(rules, prioRules) { (rules.keys + prioRules.keys).sorted() }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("线程规则管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 一键自动生成: 选应用 → 识别引擎类型 → 套自适应预设 → 直接保存生效
                    IconButton(onClick = { showAutoPick = true }) {
                        Icon(Icons.Outlined.AutoFixHigh, contentDescription = "自动生成规则")
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("导入内置预设") },
                            leadingIcon = { Icon(Icons.Outlined.GridView, null) },
                            onClick = {
                                showMenu = false
                                scope.launch {
                                    val n = runCatching { ThreadAllocRuleStore.importBundledPreset(ctx) }
                                        .getOrDefault(0)
                                    if (n > 0) com.ktwo.cts.service.ThreadRuleService.start(ctx)
                                    toast(
                                        if (n > 0) "已导入 $n 个应用的预设规则(已适配本机核心布局)"
                                        else "导入失败, 请确认 Root 权限"
                                    )
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("从剪贴板导入") },
                            leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) },
                            onClick = {
                                showMenu = false
                                val text = runCatching {
                                    clipboard().primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                                }.getOrNull().orEmpty()
                                if (text.isBlank()) { toast("剪贴板为空"); return@DropdownMenuItem }
                                scope.launch {
                                    // 依次尝试两种格式: applist.conf(绑核) / priority.prop(优先级)
                                    val bind = runCatching { ThreadAllocRules.parse(text) }
                                        .getOrDefault(linkedMapOf())
                                    val prio = runCatching { ThreadPrioRules.parse(text) }
                                        .getOrDefault(linkedMapOf())
                                    when {
                                        bind.isNotEmpty() -> {
                                            val n = ThreadAllocRuleStore.importRules(ctx, bind)
                                            if (n > 0) com.ktwo.cts.service.ThreadRuleService.start(ctx)
                                            toast("已导入 $n 个应用的绑核规则")
                                        }
                                        prio.isNotEmpty() -> {
                                            val n = ThreadPrioRuleStore.importRules(ctx, prio)
                                            toast("已导入 $n 个应用的实时优先级规则")
                                        }
                                        else -> toast("剪贴板内容不是有效的 AppOpt/priority.prop 配置")
                                    }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出到剪贴板") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = {
                                showMenu = false
                                scope.launch {
                                    val bind = ThreadAllocRuleStore.exportText(ctx) { labelOf(ctx, it) }
                                    val prio = ThreadPrioRuleStore.exportText(ctx) { labelOf(ctx, it) }
                                    val text = listOf(bind, prio).filter { it.isNotBlank() }
                                        .joinToString("\n\n")
                                    if (text.isBlank()) { toast("还没有任何规则"); return@launch }
                                    runCatching {
                                        clipboard().setPrimaryClip(ClipData.newPlainText("ThreadRules", text))
                                    }
                                    toast("已导出到剪贴板(绑核 + 优先级)")
                                }
                            },
                        )
                    }
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
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "每个应用一份配置: 绑核规则(线程名→核心) + 实时优先级(线程名→调度策略), " +
                        "点开还能看应用正在跑的线程、直接把某条线程绑核或存成规则。由 Kin 守护持续生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (allPkgs.isEmpty()) item {
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
                            "点右下角「添加应用」, 或右上角菜单导入预设/剪贴板配置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(allPkgs, key = { it }) { pkg ->
                val rs = rules[pkg].orEmpty()
                val ps = prioRules[pkg].orEmpty()
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
                                    labelOf(ctx, pkg),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(6.dp))
                                val game = ThreadAllocRules.isGameRules(rs)
                                TagChip(if (game) "游戏" else "应用",
                                    if (game) Color(0xFFB8860B) else MaterialTheme.colorScheme.primary)
                                if (ThreadPrioRules.hasRealtime(ps)) {
                                    Spacer(Modifier.width(4.dp))
                                    TagChip("实时", Color(0xFFB8860B))
                                }
                            }
                            Text(
                                pkg, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                buildString {
                                    append("绑核 ${rs.count { it.thread.isNotEmpty() }} 条")
                                    rs.firstOrNull { it.thread.isEmpty() }?.let { append(" · 默认 ${it.cpus}") }
                                    append(" · 优先级 ${ps.count { it.thread.isNotEmpty() }} 条")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                ThreadAllocRuleStore.remove(ctx, listOf(pkg))
                                ThreadPrioRuleStore.remove(ctx, listOf(pkg))
                            }
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    if (showAdd) AddAppDialog(
        onDismiss = { showAdd = false },
        onAdd = { pkg ->
            showAdd = false
            editPkg = pkg
        },
    )

    // 一键自动生成: 选中应用后, 读它的实时线程识别引擎类型 → 套自适应预设。
    // 已有规则则不静默覆盖, 而是带自动结果进编辑器让用户确认后再存(防误删手改的规则)。
    if (showAutoPick) AddAppDialog(
        title = "自动生成 · 选择应用",
        onDismiss = { showAutoPick = false },
        onAdd = { pkg ->
            showAutoPick = false
            scope.launch {
                val res = autoDetectRules(ctx, pkg, plan)
                val n = res.rules.count { it.thread.isNotEmpty() }
                val existing = rules[pkg].orEmpty()
                if (existing.isNotEmpty()) {
                    editOverride = res.rules
                    editPkg = pkg
                    toast("${detectLabel(res)}; 确认保存将覆盖原有 ${existing.size} 条规则")
                } else {
                    ThreadAllocRuleStore.put(ctx, pkg, res.rules)
                    com.ktwo.cts.service.ThreadRuleService.start(ctx)
                    toast(
                        "${detectLabel(res)}, 已生成 $n 条并生效" +
                            if (res.source == ThreadAllocRules.Source.FALLBACK)
                                "; 若是游戏请启动后再自动识别" else ""
                    )
                }
            }
        },
    )

    editPkg?.let { pkg ->
        RuleEditorSheet(
            pkg = pkg,
            initialBind = editOverride ?: rules[pkg].orEmpty(),
            initialPrio = prioRules[pkg].orEmpty(),
            plan = plan,
            onDismiss = { editPkg = null; editOverride = null },
            onSave = { newBind, newPrio ->
                scope.launch {
                    if (newBind.isEmpty()) ThreadAllocRuleStore.remove(ctx, listOf(pkg))
                    else ThreadAllocRuleStore.put(ctx, pkg, newBind)
                    if (newPrio.isEmpty()) ThreadPrioRuleStore.remove(ctx, listOf(pkg))
                    else ThreadPrioRuleStore.put(ctx, pkg, newPrio)
                    if (newBind.isNotEmpty()) com.ktwo.cts.service.ThreadRuleService.start(ctx)
                    editPkg = null; editOverride = null
                }
            },
        )
    }
}

@Composable
private fun TagChip(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

private data class AppRow(
    val pkg: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
)

/** 添加应用: 从已安装应用挑选(带图标 + 搜索)。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
    title: String = "选择应用",
) {
    val ctx = LocalContext.current
    var filter by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppRow>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            runCatching {
                val pm = ctx.packageManager
                pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map {
                        AppRow(
                            it.packageName,
                            pm.getApplicationLabel(it).toString(),
                            runCatching { pm.getApplicationIcon(it) }.getOrNull(),
                        )
                    }
                    .sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
        }
    }
    val shown = remember(filter, apps) {
        if (filter.isBlank()) apps
        else apps.filter { it.pkg.contains(filter, true) || it.label.contains(filter, true) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = filter, onValueChange = { filter = it },
                placeholder = { Text("搜索应用名/包名") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.pkg }) { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onAdd(app.pkg) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val bmp = remember(app.pkg) {
                            runCatching { app.icon?.toBitmap(72, 72) }.getOrNull()
                        }
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (bmp != null) {
                                Image(BitmapPainter(bmp.asImageBitmap()), null,
                                    modifier = Modifier.fillMaxSize())
                            } else {
                                Text(app.label.firstOrNull()?.toString() ?: "?",
                                    fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.pkg, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 三合一编辑器: 绑核规则 / 实时优先级 / 实时线程。
 * 「保存」同时落两份规则(空列表 = 删除该应用条目); 实时线程页的「仅本次绑核」即点即生效不进规则。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorSheet(
    pkg: String,
    initialBind: List<TaRule>,
    initialPrio: List<TaPrioRule>,
    plan: TaClusterPlan,
    onDismiss: () -> Unit,
    onSave: (List<TaRule>, List<TaPrioRule>) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember(pkg) { mutableStateOf(0) }
    var bindRows by remember(pkg, initialBind) {
        mutableStateOf(if (initialBind.isEmpty()) listOf(TaRule("", "")) else initialBind)
    }
    var prioRows by remember(pkg, initialPrio) { mutableStateOf(initialPrio) }
    var detected by remember(pkg) { mutableStateOf<String?>(null) }
    var detecting by remember { mutableStateOf(false) }
    var corePickIdx by remember { mutableStateOf(-1) }        // 正在选核的绑核规则行
    var bindOnce by remember { mutableStateOf<ThreadAllocator.ThreadInfo?>(null) }  // 实时线程→选核

    // 实时线程页状态
    var threads by remember(pkg) { mutableStateOf<List<ThreadAllocator.ThreadInfo>>(emptyList()) }
    var pid by remember(pkg) { mutableStateOf(-1) }
    var loadingThreads by remember { mutableStateOf(false) }
    // 选核对话框的分簇 = plan 的真实频率簇(与「本机核心」展示一致, 2 簇设备就 2 组, 不造重复)
    val clusters = remember(plan) { plan.tierLabels() }
    fun reloadThreads() {
        if (loadingThreads) return
        loadingThreads = true
        scope.launch {
            val (p, list) = withContext(Dispatchers.IO) {
                runCatching {
                    val found = ThreadSampler.resolvePid(pkg)
                    found to (if (found > 0) ThreadAllocator.listThreads(found) else emptyList())
                }.getOrDefault(-1 to emptyList())
            }
            pid = p
            threads = list
            loadingThreads = false
        }
    }
    LaunchedEffect(tab) { if (tab == 2 && threads.isEmpty()) reloadThreads() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(labelOf(ctx, pkg), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(pkg, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "本机核心: " + plan.tierLabels().joinToString(" · ") { (name, cpus) ->
                    "$name ${cpus.joinToString(",", "[", "]")}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))

            TabRow(
                selectedTabIndex = tab,
                containerColor = Color.Transparent,
            ) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("绑核规则") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("实时优先级") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("实时线程") })
            }
            Spacer(Modifier.height(10.dp))

            when (tab) {
                // ── ① 绑核规则 ──
                0 -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("套用预设", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        TextButton(
                            enabled = !detecting,
                            onClick = {
                                detecting = true
                                scope.launch {
                                    val res = autoDetectRules(ctx, pkg, plan)
                                    if (res.source == ThreadAllocRules.Source.FALLBACK) {
                                        detected = "应用未运行, 读不到线程无法识别; 请手动选预设, 或启动应用后重试"
                                    } else {
                                        bindRows = res.rules
                                        detected = detectLabel(res)
                                    }
                                    detecting = false
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.AutoFixHigh, contentDescription = null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (detecting) "识别中…" else "自动识别")
                        }
                    }
                    detected?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    // 预设选择: 每条一张卡, 名字醒目 + 一行说明, 点整卡套用(点后规则出现在下方可再改)
                    LazyColumn(
                        Modifier.heightIn(max = 190.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(ThreadAllocRules.presets(), key = { it.id }) { p ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { bindRows = p.build(pkg, plan); detected = null },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold)
                                        Text(p.brief, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("套用", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("规则(线程名通配 = 核区间, 线程名留空 = 该应用默认)", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(bindRows.size) { i ->
                            val r = bindRows[i]
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = r.thread,
                                    onValueChange = { v ->
                                        bindRows = bindRows.toMutableList().also { it[i] = r.copy(thread = v) }
                                        detected = null
                                    },
                                    placeholder = {
                                        Text("线程名 / 留空=默认", maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall)
                                    },
                                    textStyle = MaterialTheme.typography.bodySmall
                                        .copy(fontFamily = FontFamily.Monospace),
                                    singleLine = true,
                                    modifier = Modifier.weight(1.5f),
                                )
                                Spacer(Modifier.width(6.dp))
                                // 核区间: 点击弹芯片选核, 不用手打
                                OutlinedButton(
                                    onClick = { corePickIdx = i },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        r.cpus.ifBlank { "选核心" },
                                        style = MaterialTheme.typography.bodySmall
                                            .copy(fontFamily = FontFamily.Monospace),
                                        maxLines = 1,
                                    )
                                }
                                IconButton(onClick = {
                                    bindRows = bindRows.toMutableList().also { it.removeAt(i) }
                                    detected = null
                                }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "删行",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    TextButton(onClick = { bindRows = bindRows + TaRule("", ""); detected = null }) {
                        Text("+ 加一行")
                    }
                }

                // ── ② 实时优先级 ──
                1 -> Column {
                    Text(
                        "SCHED_FIFO/RR 为实时调度(优先级 1-99, 建议 1-3, 过高可能拖慢系统); " +
                            "需在「线程分配」页打开「实时调度」开关才生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("套用预设", fontWeight = FontWeight.Medium)
                    LazyColumn(Modifier.heightIn(max = 96.dp)) {
                        items(ThreadPrioRules.presets(), key = { it.id }) { p ->
                            Row(
                                Modifier.fillMaxWidth().clickable { prioRows = p.build(pkg) }
                                    .padding(vertical = 6.dp),
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
                    Text("规则(线程名通配 = 策略 + 优先级)", fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(prioRows.size) { i ->
                            val r = prioRows[i]
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = r.thread,
                                    onValueChange = { v ->
                                        prioRows = prioRows.toMutableList().also { it[i] = r.copy(thread = v) }
                                    },
                                    placeholder = {
                                        Text("线程名", maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall)
                                    },
                                    textStyle = MaterialTheme.typography.bodySmall
                                        .copy(fontFamily = FontFamily.Monospace),
                                    singleLine = true, modifier = Modifier.weight(1.5f),
                                )
                                Spacer(Modifier.width(6.dp))
                                PolicyDropdown(
                                    policy = r.policy,
                                    onSelect = { pol ->
                                        prioRows = prioRows.toMutableList().also { it[i] = r.copy(policy = pol) }
                                    },
                                    modifier = Modifier.weight(1.1f),
                                )
                                Spacer(Modifier.width(6.dp))
                                OutlinedTextField(
                                    value = if (r.policy == ThreadPrioRules.OTHER) "0" else r.prio.toString(),
                                    onValueChange = { v ->
                                        val p = v.filter { it.isDigit() }.toIntOrNull() ?: 0
                                        prioRows = prioRows.toMutableList().also { it[i] = r.copy(prio = p) }
                                    },
                                    enabled = r.policy != ThreadPrioRules.OTHER,
                                    placeholder = { Text("1-99") },
                                    textStyle = MaterialTheme.typography.bodySmall
                                        .copy(fontFamily = FontFamily.Monospace),
                                    singleLine = true, modifier = Modifier.weight(0.7f),
                                )
                                IconButton(onClick = {
                                    prioRows = prioRows.toMutableList().also { it.removeAt(i) }
                                }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "删行",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    TextButton(onClick = {
                        // 安全的具体示例(UnityMain→FIFO 2): 只命中单个线程、优先级低
                        prioRows = prioRows + if (prioRows.isEmpty())
                            TaPrioRule("UnityMain", ThreadPrioRules.FIFO, 2)
                        else TaPrioRule("", ThreadPrioRules.OTHER, 0)
                    }) { Text("+ 加一行") }
                }

                // ── ③ 实时线程(原手动绑核): 运行中线程列表, 点条目绑核/存规则, ⚡ 加优先级 ──
                2 -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                loadingThreads -> "正在读取线程…"
                                pid > 0 -> "pid $pid · ${threads.size} 个线程 · 点线程绑核, ⚡ 加实时优先级"
                                else -> "应用未运行或无 Root, 读不到线程"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { reloadThreads() }, enabled = !loadingThreads) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    }
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(threads, key = { it.tid }) { t ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { bindOnce = t }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        t.name, fontWeight = FontWeight.Medium, fontSize = 14.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "tid ${t.tid} · 核 ${if (t.core >= 0) "${t.core}" else "-"}" +
                                            " · 亲和 ${t.affinity.ifBlank { "?" }}",
                                        style = MaterialTheme.typography.bodySmall
                                            .copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = {
                                    // ⚡: 以该线程名加一条实时优先级规则(FIFO 2), 跳到优先级页确认
                                    if (prioRows.none { it.thread == t.name }) {
                                        prioRows = prioRows + TaPrioRule(t.name, ThreadPrioRules.FIFO, 2)
                                    }
                                    tab = 1
                                }) {
                                    Icon(Icons.Outlined.Bolt, contentDescription = "加实时优先级",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = {
                        onSave(ThreadAllocRules.normalize(bindRows), ThreadPrioRules.normalize(prioRows))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }
        }
    }

    // 绑核规则行的核区间选择器
    if (corePickIdx >= 0) {
        val idx = corePickIdx
        CorePickDialog(
            title = "选择核心",
            clusters = clusters,
            initial = ThreadAllocator.parseCpuList(bindRows.getOrNull(idx)?.cpus.orEmpty()).toSet(),
            confirmText = "确定",
            onConfirm = { cpus ->
                bindRows.getOrNull(idx)?.let { r ->
                    bindRows = bindRows.toMutableList().also { it[idx] = r.copy(cpus = cpusSpec(cpus)) }
                }
                corePickIdx = -1
            },
            onDismiss = { corePickIdx = -1 },
        )
    }

    // 实时线程 → 选核: 「仅本次」立即 taskset; 「存为规则」以线程名加进绑核规则
    bindOnce?.let { t ->
        CorePickDialog(
            title = t.name,
            subtitle = "tid ${t.tid} · 当前核 ${if (t.core >= 0) "${t.core}" else "-"} · 亲和 ${t.affinity.ifBlank { "?" }}",
            clusters = clusters,
            initial = ThreadAllocator.parseCpuList(t.affinity).toSet(),
            confirmText = "仅本次",
            neutralText = "存为规则",
            onConfirm = { cpus ->
                bindOnce = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            var mask = 0
                            cpus.forEach { c -> if (c in 0..30) mask = mask or (1 shl c) }
                            ThreadAllocator.applyAffinityToTid(t.tid, mask)
                        }.getOrDefault(false)
                    }
                    if (ok) reloadThreads()
                }
            },
            onNeutral = { cpus ->
                bindOnce = null
                val spec = cpusSpec(cpus)
                val i = bindRows.indexOfFirst { it.thread == t.name }
                bindRows =
                    if (i >= 0) bindRows.toMutableList().also { it[i] = it[i].copy(cpus = spec) }
                    else bindRows.filterNot { it.thread.isEmpty() && it.cpus.isEmpty() } +
                        TaRule(t.name, spec)
                tab = 0   // 跳回绑核页让用户看到新规则, 按「保存」落盘
            },
            onDismiss = { bindOnce = null },
        )
    }
}

/**
 * 核心多选对话框(按簇分组芯片): confirmText 为主动作;
 * neutralText 非空时多一个次动作(如「存为规则」)。至少选一个核才允许确认。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CorePickDialog(
    title: String,
    clusters: List<Pair<String, List<Int>>>,
    initial: Set<Int>,
    confirmText: String,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    neutralText: String? = null,
    onNeutral: ((Set<Int>) -> Unit)? = null,
) {
    val allCpus = remember(clusters) { clusters.flatMap { it.second }.distinct().sorted() }
    var selected by remember(title, initial) {
        mutableStateOf(initial.filter { it in allCpus }.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                clusters.forEach { (name, cpus) ->
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        cpus.forEach { cpu ->
                            FilterChip(
                                selected = cpu in selected,
                                onClick = {
                                    selected = if (cpu in selected) selected - cpu else selected + cpu
                                },
                                label = { Text("CPU$cpu") },
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }
                }
                if (selected.isEmpty()) {
                    Text(
                        "至少选择一个核心",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (neutralText != null && onNeutral != null) {
                TextButton(
                    onClick = { if (selected.isNotEmpty()) onNeutral(selected) },
                    enabled = selected.isNotEmpty(),
                ) { Text(neutralText) }
            }
            TextButton(
                onClick = { if (selected.isNotEmpty()) onConfirm(selected) },
                enabled = selected.isNotEmpty(),
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PolicyDropdown(policy: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
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
