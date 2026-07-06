// 手动线程分配(线程分配的进阶入口): 选一个正在运行的应用 → 列出它的全部线程
// (线程名 / tid / 当前所在核 / 允许的核范围) → 点某个线程弹出核心多选(按 小核/中核/大核 分簇)
// → taskset 把该线程绑到勾选的核心上。需要 Root; 无 root 静默降级只展示提示。
// 全部读取/写入都在 IO 线程 + runCatching 兜底, 不影响现有线程分配(总开关/游戏库)逻辑。
package com.ktwo.cts.ui.threadalloc

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ktwo.cts.data.ThreadAllocator
import com.ktwo.cts.data.fpsrecord.ThreadSampler
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.ui.TonalCard
import com.ktwo.cts.ui.theme.MonoNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAllocScreen(
    onBack: () -> Unit,
    proUnlocked: Boolean = true,
    onNeedPro: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // 环境信息: 进页后台探测一次(root 状态 / 簇分组 / 大核列表), 失败静默
    var rooted by remember { mutableStateOf(true) }
    var clusters by remember { mutableStateOf<List<Pair<String, List<Int>>>>(emptyList()) }
    var bigCores by remember { mutableStateOf<List<Int>>(emptyList()) }

    // 选中的应用与它的线程
    var pickedPkg by remember { mutableStateOf("") }
    var pickedLabel by remember { mutableStateOf("") }
    var pid by remember { mutableStateOf(-1) }
    var threads by remember { mutableStateOf<List<ThreadAllocator.ThreadInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var dialogThread by remember { mutableStateOf<ThreadAllocator.ThreadInfo?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val r = runCatching { RootShell.isRooted() }.getOrDefault(false)
            val c = runCatching { ThreadAllocator.coreClusters() }.getOrDefault(emptyList())
            val b = runCatching { ThreadAllocator.bigCoreCpus() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { rooted = r; clusters = c; bigCores = b }
        }
    }

    // 重新拉取选中应用的线程列表(pidof 找进程 → 一条 root shell 列线程), 失败静默为空
    fun reload() {
        val pkg = pickedPkg
        if (pkg.isBlank() || loading) return
        loading = true
        scope.launch {
            val (p, list) = withContext(Dispatchers.IO) {
                runCatching {
                    val found = ThreadSampler.resolvePid(pkg)
                    found to (if (found > 0) ThreadAllocator.listThreads(found) else emptyList())
                }.getOrDefault(-1 to emptyList())
            }
            pid = p
            threads = list
            loading = false
            if (p <= 0) {
                snackbar.showSnackbar(
                    "没找到「${pickedLabel.ifBlank { pkg }}」的进程, 请确认它正在运行(需要 Root)"
                )
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("手动分配") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (!proUnlocked) onNeedPro()
                            else if (pickedPkg.isBlank()) showPicker = true
                            else reload()
                        },
                        enabled = !loading,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
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
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
        ) {
            // ── 未解锁: 智能版提示(与 ThreadAllocScreen 同款门控) ──
            if (!proUnlocked) {
                item(key = "pro") {
                    TonalCard(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Lock, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("智能版功能", fontWeight = FontWeight.Medium)
                                Text(
                                    "手动分配属于智能版, 解锁后即可手动把任意线程绑到指定核心",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(onClick = onNeedPro) { Text("解锁") }
                        }
                    }
                }
            }

            // ── 无 Root 提示 ──
            if (!rooted) {
                item(key = "noroot") {
                    TonalCard(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.WarningAmber, contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("手动分配需要 Root", fontWeight = FontWeight.Medium)
                                Text(
                                    "读取线程列表与绑核都依赖 Root 权限, 请先授予本应用 Root",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // ── 选中的应用(可点击切换) ──
            item(key = "app") {
                TonalCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { if (!proUnlocked) onNeedPro() else showPicker = true }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Apps, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (pickedPkg.isBlank()) "请选择应用"
                                else pickedLabel.ifBlank { pickedPkg },
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                when {
                                    pickedPkg.isBlank() -> "选择一个正在运行的应用, 查看并手动绑核它的线程"
                                    pid > 0 -> "$pickedPkg · pid $pid · ${threads.size} 个线程"
                                    else -> "$pickedPkg · 未找到运行中的进程"
                                },
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

            if (loading) {
                item(key = "loading") {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "正在读取线程…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (pickedPkg.isNotBlank() && !loading && threads.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "没有读到线程。请确认应用正在运行且已授予 Root, 然后点右上角刷新。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }

            // ── 线程列表: 点整行弹绑核对话框 ──
            items(threads, key = { it.tid }) { t ->
                TonalCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { if (!proUnlocked) onNeedPro() else dialogThread = t }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.name,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "tid ${t.tid}",
                                style = MonoNumber.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                if (t.core >= 0) "核 ${t.core}" else "核 -",
                                style = MonoNumber.copy(fontSize = 13.sp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "亲和 ${t.affinity.ifBlank { "?" }}",
                                style = MonoNumber.copy(fontSize = 12.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // ── 应用选择器(单选) ──
    if (showPicker) {
        AppPickerSheet(
            onPick = { pkg, label ->
                showPicker = false
                pickedPkg = pkg
                pickedLabel = label
                pid = -1
                threads = emptyList()
                reload()
            },
            onDismiss = { showPicker = false },
        )
    }

    // ── 绑核对话框 ──
    dialogThread?.let { t ->
        BindCoreDialog(
            thread = t,
            clusters = clusters,
            bigCores = bigCores,
            onDismiss = { dialogThread = null },
            onApply = { cpus ->
                dialogThread = null
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            var mask = 0
                            cpus.forEach { c -> if (c in 0..30) mask = mask or (1 shl c) }
                            ThreadAllocator.applyAffinityToTid(t.tid, mask)
                        }.getOrDefault(false)
                    }
                    if (ok) reload()
                    snackbar.showSnackbar(
                        if (ok) "已把「${t.name}」绑到 CPU ${cpus.sorted().joinToString("/")}"
                        else "绑核失败, 请确认 Root 权限且该线程仍在运行"
                    )
                }
            },
        )
    }
}

/**
 * 绑核对话框: 按 小核/中核/大核 分簇的核心多选, 预选 = 该线程当前亲和解析出的核集合;
 * 快捷键「全大核」「全部核心」; 至少选一个核才允许应用。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BindCoreDialog(
    thread: ThreadAllocator.ThreadInfo,
    clusters: List<Pair<String, List<Int>>>,
    bigCores: List<Int>,
    onDismiss: () -> Unit,
    onApply: (Set<Int>) -> Unit,
) {
    val allCpus = remember(clusters) { clusters.flatMap { it.second }.distinct().sorted() }
    var selected by remember(thread.tid) {
        // 预选当前亲和; 解析不出(如 "?" )时不预选, 由用户自己勾
        mutableStateOf(
            ThreadAllocator.parseCpuList(thread.affinity).filter { it in allCpus }.toSet()
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(thread.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "tid ${thread.tid} · 当前核 ${if (thread.core >= 0) "${thread.core}" else "-"}" +
                        " · 亲和 ${thread.affinity.ifBlank { "?" }}",
                    style = MonoNumber.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                clusters.forEach { (name, cpus) ->
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        cpus.forEach { cpu ->
                            FilterChip(
                                selected = cpu in selected,
                                onClick = {
                                    selected =
                                        if (cpu in selected) selected - cpu else selected + cpu
                                },
                                label = { Text("CPU$cpu") },
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { if (bigCores.isNotEmpty()) selected = bigCores.toSet() },
                        enabled = bigCores.isNotEmpty(),
                    ) { Text("全大核") }
                    OutlinedButton(
                        onClick = { if (allCpus.isNotEmpty()) selected = allCpus.toSet() },
                        enabled = allCpus.isNotEmpty(),
                    ) { Text("全部核心") }
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
            TextButton(
                onClick = { if (selected.isNotEmpty()) onApply(selected) },
                enabled = selected.isNotEmpty(),
            ) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private data class ManualPickEntry(
    val pkg: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val isSystem: Boolean,
)

/** 应用单选弹层: 已安装应用列表(默认只列用户应用) + 搜索, 点击即选中并关闭。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    onPick: (pkg: String, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<ManualPickEntry>>(emptyList()) }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var showSystem by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            runCatching {
                val pm = ctx.packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA).map {
                    ManualPickEntry(
                        pkg = it.packageName,
                        label = pm.getApplicationLabel(it).toString(),
                        icon = runCatching { pm.getApplicationIcon(it) }.getOrNull(),
                        isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }.sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
        }
    }

    val filtered = remember(apps, query.text, showSystem) {
        apps.filter {
            (showSystem || !it.isSystem) &&
                (query.text.isEmpty() ||
                    it.label.contains(query.text, ignoreCase = true) ||
                    it.pkg.contains(query.text, ignoreCase = true))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "选择应用",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = showSystem,
                    onClick = { showSystem = !showSystem },
                    label = { Text("含系统应用") },
                    shape = RoundedCornerShape(10.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索应用") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                contentPadding = PaddingValues(bottom = 32.dp),
            ) {
                items(filtered, key = { it.pkg }) { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(app.pkg, app.label) }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val bmp = remember(app.pkg) {
                            runCatching { app.icon?.toBitmap(72, 72) }.getOrNull()
                        }
                        Box(
                            Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
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
                                Text(
                                    app.label.firstOrNull()?.toString() ?: "?",
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(
                                app.pkg,
                                fontSize = 11.sp,
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
        }
    }
}
