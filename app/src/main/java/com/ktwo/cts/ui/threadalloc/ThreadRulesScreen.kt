// 「线程规则管理」页(AppOpt 式 per-app 规则管理器, 参考 appopt.suto.top / AppOpt WebUI):
// 已配置应用列表(游戏/应用标签) → 点开编辑该应用的 线程名通配→核区间 规则; 支持内置预设
// 一键套用(核区间按本机簇布局自适应)、剪贴板导入/导出 AppOpt applist.conf 兼容文本。
package com.ktwo.cts.ui.threadalloc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.ktwo.cts.data.TaRule
import com.ktwo.cts.data.ThreadAllocRuleStore
import com.ktwo.cts.data.ThreadAllocRules
import kotlinx.coroutines.launch

/** 应用标签(尽力解析, 未安装/系统进程名显示原文)。 */
private fun labelOf(ctx: Context, pkg: String): String = runCatching {
    val pm = ctx.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg.substringBefore(':'), 0)).toString()
}.getOrDefault(pkg)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadRulesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val rules by ThreadAllocRuleStore.flow(ctx).collectAsState(initial = emptyMap())

    var editPkg by remember { mutableStateOf<String?>(null) }   // 正在编辑的应用
    var showAdd by remember { mutableStateOf(false) }

    fun toast(msg: String) = scope.launch { snackbar.showSnackbar(msg) }

    fun clipboard(): ClipboardManager =
        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

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
                    // 导入: 解析剪贴板里的 AppOpt applist.conf 文本
                    IconButton(onClick = {
                        val text = runCatching {
                            clipboard().primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                        }.getOrNull().orEmpty()
                        if (text.isBlank()) { toast("剪贴板为空"); return@IconButton }
                        scope.launch {
                            val parsed = runCatching { ThreadAllocRules.parse(text) }
                                .getOrDefault(linkedMapOf())
                            if (parsed.isEmpty()) { toast("剪贴板内容不是有效的 AppOpt 配置"); return@launch }
                            val n = ThreadAllocRuleStore.importRules(ctx, parsed)
                            if (n > 0) com.ktwo.cts.service.ThreadRuleService.start(ctx)
                            toast("已导入 $n 个应用的规则")
                        }
                    }) { Icon(Icons.Outlined.ContentPaste, contentDescription = "从剪贴板导入") }
                    // 导出: 生成 AppOpt 兼容文本到剪贴板
                    IconButton(onClick = {
                        scope.launch {
                            val text = ThreadAllocRuleStore.exportText(ctx) { labelOf(ctx, it) }
                            if (text.isBlank()) { toast("还没有任何规则"); return@launch }
                            runCatching {
                                clipboard().setPrimaryClip(ClipData.newPlainText("AppOpt", text))
                            }
                            toast("已导出到剪贴板(AppOpt 格式)")
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
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "为每个应用配置「线程名 → 绑定核心」规则, 由守护进程持续生效。" +
                        "语法与 AppOpt 兼容, 可与其配置互导。",
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
                            "点右下角「添加应用」, 或从剪贴板导入 AppOpt 配置",
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
                                    labelOf(ctx, pkg),
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(6.dp))
                                // 游戏/应用标签(WebUI t-game/t-app 同款语义)
                                val game = ThreadAllocRules.isGameRules(rs)
                                Text(
                                    if (game) "游戏" else "应用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (game) Color(0xFFB8860B) else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .background(
                                            (if (game) Color(0xFFB8860B) else MaterialTheme.colorScheme.primary)
                                                .copy(alpha = 0.12f),
                                            RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                            Text(
                                pkg, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${rs.count { it.thread.isNotEmpty() }} 条线程规则" +
                                    (rs.firstOrNull { it.thread.isEmpty() }
                                        ?.let { " · 默认 ${it.cpus}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            scope.launch { ThreadAllocRuleStore.remove(ctx, listOf(pkg)) }
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

    editPkg?.let { pkg ->
        RuleEditorSheet(
            pkg = pkg,
            initial = rules[pkg].orEmpty(),
            onDismiss = { editPkg = null },
            onSave = { newRules ->
                scope.launch {
                    ThreadAllocRuleStore.put(ctx, pkg, newRules)
                    if (newRules.isNotEmpty()) com.ktwo.cts.service.ThreadRuleService.start(ctx)
                    editPkg = null
                }
            },
        )
    }
}

/** 添加应用: 从已安装应用挑选(简单列表 + 过滤)。 */
@Composable
private fun AddAppDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
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
        else apps.filter {
            it.first.contains(filter, true) || it.second.contains(filter, true)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用") },
        text = {
            Column {
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it },
                    placeholder = { Text("搜索应用名/包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(320.dp)) {
                    items(shown, key = { it.first }) { (pkg, label) ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onAdd(pkg) }
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

/** 规则编辑面板: 逐行「线程名通配 = 核区间」编辑 + 预设一键套用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorSheet(
    pkg: String,
    initial: List<TaRule>,
    onDismiss: () -> Unit,
    onSave: (List<TaRule>) -> Unit,
) {
    val ctx = LocalContext.current
    val plan = remember { ThreadAllocRules.clusterPlan() }
    var rows by remember(pkg, initial) {
        mutableStateOf(if (initial.isEmpty()) listOf(TaRule("", "")) else initial)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(labelOf(ctx, pkg), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text(pkg, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "本机核心: 小核 ${plan.small} · 中核 ${plan.mid} · 大核 ${plan.big} · 超大核 ${plan.prime}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))

            // 预设一键套用(核区间按本机簇布局换算)
            Text("套用预设", fontWeight = FontWeight.Medium)
            LazyColumn(Modifier.heightIn(max = 132.dp)) {
                items(ThreadAllocRules.presets(), key = { it.id }) { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { rows = p.build(pkg, plan) }
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

            // 规则行编辑: 线程名(空=包默认) = 核区间
            Text("规则(线程名通配 = 核区间, 线程名留空 = 该应用默认)", fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                items(rows.size) { i ->
                    val r = rows[i]
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = r.thread,
                            onValueChange = { v ->
                                rows = rows.toMutableList().also { it[i] = r.copy(thread = v) }
                            },
                            placeholder = { Text("线程名, 如 UnityMain / Thread-*") },
                            textStyle = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                            singleLine = true,
                            modifier = Modifier.weight(1.6f),
                        )
                        Spacer(Modifier.width(6.dp))
                        OutlinedTextField(
                            value = r.cpus,
                            onValueChange = { v ->
                                rows = rows.toMutableList().also { it[i] = r.copy(cpus = v) }
                            },
                            placeholder = { Text("0-3,7") },
                            textStyle = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            rows = rows.toMutableList().also { it.removeAt(i) }
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删行",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            TextButton(onClick = { rows = rows + TaRule("", "") }) { Text("+ 加一行") }

            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = { onSave(ThreadAllocRules.normalize(rows)) },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
            }
        }
    }
}
