// 线程分配(设备调优, M5): 把前台应用/游戏的渲染类线程归拢到大核, 提升流畅度。
// 首版只做"绑核一次"式应用(taskset), 不写高风险调度节点; 实时调度/权重仅存偏好(即将支持)。
// 智能版(Pro)功能: 未解锁时整页引导解锁, 开关点击跳转付费页(参照 AutoModeScreen 门控写法)。
package com.ktwo.cts.ui.threadalloc

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ktwo.cts.data.ThreadAllocCfg
import com.ktwo.cts.data.ThreadAllocDaemonCfg
import com.ktwo.cts.data.ThreadAllocRuleStore
import com.ktwo.cts.data.ThreadAllocator
import com.ktwo.cts.service.AppSwitchService
import com.ktwo.cts.service.ForegroundDetector
import com.ktwo.cts.ui.SectionTitle
import com.ktwo.cts.ui.TonalCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadAllocScreen(
    onBack: () -> Unit,
    onOpenRules: () -> Unit = {},
    proUnlocked: Boolean = true,
    onNeedPro: () -> Unit = {},
    onOpenManual: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val enabled by ThreadAllocCfg.enabledFlow(ctx).collectAsState(initial = false)
    val mode by ThreadAllocCfg.modeFlow(ctx).collectAsState(initial = ThreadAllocCfg.MODE_AFFINITY)
    val appEnabled by ThreadAllocCfg.appEnabledFlow(ctx).collectAsState(initial = false)
    val gameEnabled by ThreadAllocCfg.gameEnabledFlow(ctx).collectAsState(initial = false)
    val gameAdaptive by ThreadAllocCfg.gameAdaptiveFlow(ctx).collectAsState(initial = false)
    val gameRt by ThreadAllocCfg.gameRtFlow(ctx).collectAsState(initial = false)
    val gameWeight by ThreadAllocCfg.gameWeightFlow(ctx).collectAsState(initial = false)
    val gamePkgs by ThreadAllocCfg.gamePkgsFlow(ctx).collectAsState(initial = emptySet())

    var conflicts by remember { mutableStateOf<List<String>>(emptyList()) }
    var bigCores by remember { mutableStateOf<List<Int>>(emptyList()) }
    var showGamePicker by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }

    // 把总开关 + 游戏库同步进守护 config.json 的 Function.ThreadAlloc 段(守护据此持续绑核)。
    // 先落 DataStore 再从 DataStore 读最新值写盘, 避免连续勾选时用到过期的 compose 状态;
    // 内部 runCatching + IO 线程, 无 root 时静默失败, 不打断用户。
    suspend fun syncDaemon() {
        runCatching {
            ThreadAllocDaemonCfg.sync(
                ctx,
                enabled = ThreadAllocCfg.enabled(ctx),
                gamePkgs = ThreadAllocCfg.gamePkgsFlow(ctx).first(),
            )
            // 补推一次当前规则: 用户可能先建好规则、再打开总开关; 确保开关一开守护就拿到最新规则文件。
            ThreadAllocRuleStore.syncToDaemon(ctx)
        }
    }

    // 冲突检测 + 大核识别: 进页时后台跑一次, 失败静默(空列表)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val c = runCatching { ThreadAllocator.conflicts() }.getOrDefault(emptyList())
            val b = runCatching { ThreadAllocator.bigCoreCpus() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { conflicts = c; bigCores = b }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("线程分配") },
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
            // ── 未解锁: 智能版提示 ──
            if (!proUnlocked) {
                TonalCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Lock, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("智能版功能", fontWeight = FontWeight.Medium)
                            Text(
                                "线程分配属于智能版, 解锁后即可把游戏渲染线程固定到大核",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = onNeedPro) { Text("解锁") }
                    }
                }
            }

            // ── 冲突检测横幅 ──
            if (conflicts.isNotEmpty()) {
                TonalCard(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.WarningAmber, contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("检测到可能的冲突", fontWeight = FontWeight.Medium)
                            Text(
                                "检测到 ${conflicts.joinToString("、")} 正在运行, 它们也会调整线程调度, 可能与本功能互相覆盖。建议只保留一个。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ── 通用 ──
            SectionTitle("通用")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TaSwitchRow(
                        "启用线程分配",
                        if (bigCores.isEmpty()) "把重负载线程归拢到性能更强的核心"
                        else "已识别大核: CPU ${bigCores.joinToString("/")} · 重负载线程将归拢到这些核心",
                        checked = enabled && proUnlocked,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                    ) { v -> scope.launch { ThreadAllocCfg.setEnabled(ctx, v); syncDaemon() } }
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("模式", fontWeight = FontWeight.Medium)
                        Text(
                            "绑核 = 强制线程固定在指定核心, 上限更高; 迁移 = 允许调度器按需搬移, 帧率可能更稳。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = mode == ThreadAllocCfg.MODE_AFFINITY,
                                onClick = {
                                    if (!proUnlocked) onNeedPro()
                                    else scope.launch { ThreadAllocCfg.setMode(ctx, ThreadAllocCfg.MODE_AFFINITY) }
                                },
                                label = { Text("绑核 Affinity") },
                                shape = RoundedCornerShape(10.dp),
                            )
                            FilterChip(
                                selected = mode == ThreadAllocCfg.MODE_MIGRATION,
                                onClick = {
                                    if (!proUnlocked) onNeedPro()
                                    else scope.launch { ThreadAllocCfg.setMode(ctx, ThreadAllocCfg.MODE_MIGRATION) }
                                },
                                label = { Text("迁移 Migration") },
                                shape = RoundedCornerShape(10.dp),
                            )
                        }
                        if (mode == ThreadAllocCfg.MODE_MIGRATION) {
                            Text(
                                "迁移策略即将支持, 当前版本仍按绑核方式生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // ── 应用线程分配 ──
            SectionTitle("应用线程分配")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    TaSwitchRow(
                        "整理前台应用线程",
                        "把前台普通应用的渲染类线程归拢到合适核心, 滑动更跟手",
                        checked = appEnabled && proUnlocked,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                    ) { v -> scope.launch { ThreadAllocCfg.setAppEnabled(ctx, v) } }
                }
            }

            // ── 游戏线程分配 ──
            SectionTitle("游戏线程分配")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TaSwitchRow(
                        "启用游戏线程分配",
                        "游戏在前台时, 把渲染/引擎线程固定到大核",
                        checked = gameEnabled && proUnlocked,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                    ) { v -> scope.launch { ThreadAllocCfg.setGameEnabled(ctx, v) } }
                    HorizontalDivider()
                    // 游戏库入口
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (!proUnlocked) onNeedPro() else showGamePicker = true
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.SportsEsports, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("游戏库", fontWeight = FontWeight.Medium)
                            Text(
                                if (gamePkgs.isEmpty()) "还没有添加游戏, 点击选择"
                                else "已选 ${gamePkgs.size} 款",
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
                    HorizontalDivider()
                    TaSwitchRow(
                        "自适应分配",
                        "按机型核心布局自动决定线程落点, 不用手动挑核心",
                        checked = gameAdaptive && proUnlocked,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                    ) { v -> scope.launch { ThreadAllocCfg.setGameAdaptive(ctx, v) } }
                    HorizontalDivider()
                    TaSwitchRow(
                        "实时调度",
                        "给关键线程更高的调度优先级 · 即将支持",
                        checked = gameRt && proUnlocked,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                    ) { v -> scope.launch { ThreadAllocCfg.setGameRt(ctx, v) } }
                    HorizontalDivider()
                    TaSwitchRow(
                        "权重调整",
                        "调整关键线程的算力权重占比 · 即将支持",
                        checked = gameWeight && proUnlocked,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                    ) { v -> scope.launch { ThreadAllocCfg.setGameWeight(ctx, v) } }
                }
            }

            // ── 线程规则管理(AppOpt 式 per-app 规则) ──
            SectionTitle("线程规则")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { if (!proUnlocked) onNeedPro() else onOpenRules() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ListAlt, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("线程规则管理", fontWeight = FontWeight.Medium)
                        Text(
                            "按应用配置「线程名→绑核」规则(AppOpt 式), 支持预设/导入导出 · 需要 Root",
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

            // ── 手动分配入口 ──
            SectionTitle("手动分配")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { if (!proUnlocked) onNeedPro() else onOpenManual() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Tune, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("手动线程绑核", fontWeight = FontWeight.Medium)
                        Text(
                            "选应用查看线程列表, 手动把某个线程绑到指定核心 · 需要 Root",
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

            // ── 立即应用 ──
            Text(
                "开启后由 Kin 守护在后台持续生效: 有「线程规则」的应用按规则逐线程绑核(不限前台, 含系统进程); " +
                    "游戏库中的游戏在前台且未配规则时, 自动把渲染线程绑到大核。改规则即时热重载, 无需重启。" +
                    "下方「立即应用」用于守护未运行时的临时绑核。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    if (!proUnlocked) { onNeedPro(); return@Button }
                    if (applying) return@Button
                    applying = true
                    scope.launch {
                        val n = withContext(Dispatchers.IO) {
                            runCatching {
                                val fg = AppSwitchService.foreground.ifBlank {
                                    ForegroundDetector(ctx).currentForegroundPackage().orEmpty()
                                }
                                if (fg.isBlank() || fg == ctx.packageName) -1
                                else ThreadAllocator.applyAffinity(fg)
                            }.getOrDefault(0)
                        }
                        applying = false
                        snackbar.showSnackbar(
                            when {
                                n < 0 -> "没拿到前台应用(本应用不算), 请先切到目标应用再回来点"
                                n == 0 -> "没有线程被处理, 请确认 Root 权限且目标应用在运行"
                                else -> "已把 $n 个线程归拢到大核"
                            }
                        )
                    }
                },
                enabled = !applying,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (applying) "正在应用…" else "立即应用到当前前台应用")
            }
            Text(
                "提示: 「立即应用」为一次性动作, 应用重启后需重新应用; 常驻自动分配由 Kin 守护按上方设置完成。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(96.dp))
        }
    }

    if (showGamePicker) {
        GamePickerSheet(
            selected = gamePkgs,
            onToggle = { pkg ->
                scope.launch { ThreadAllocCfg.toggleGamePkg(ctx, pkg); syncDaemon() }
            },
            onDismiss = { showGamePicker = false },
        )
    }
}

/** 带锁图标的开关行: 未解锁时点开关引导去付费页。 */
@Composable
private fun TaSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    proUnlocked: Boolean,
    onNeedPro: () -> Unit,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Medium)
                if (!proUnlocked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Outlined.Lock, contentDescription = "需解锁",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { want ->
                if (!proUnlocked) onNeedPro() else onChange(want)
            },
        )
    }
}

private data class PickEntry(
    val pkg: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val isSystem: Boolean,
)

/** 游戏多选弹层: 应用列表 + 复选框, 勾选即时保存。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GamePickerSheet(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<PickEntry>>(emptyList()) }
    var query by remember { mutableStateOf(TextFieldValue("")) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            runCatching {
                val pm = ctx.packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA).map {
                    PickEntry(
                        pkg = it.packageName,
                        label = pm.getApplicationLabel(it).toString(),
                        icon = runCatching { pm.getApplicationIcon(it) }.getOrNull(),
                        isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }.sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
        }
    }

    val filtered = remember(apps, query.text, selected) {
        apps.filter {
            // 默认只列用户应用; 已勾选的(哪怕是系统应用)始终保留在列表里
            (!it.isSystem || it.pkg in selected) &&
                (query.text.isEmpty() ||
                    it.label.contains(query.text, ignoreCase = true) ||
                    it.pkg.contains(query.text, ignoreCase = true))
        }.sortedWith(
            compareByDescending<PickEntry> { it.pkg in selected }
                .thenBy { it.label.lowercase() }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "选择游戏",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "已选 ${selected.size} 款",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
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
                            .clickable { onToggle(app.pkg) }
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
                        Checkbox(
                            checked = app.pkg in selected,
                            onCheckedChange = { onToggle(app.pkg) },
                        )
                    }
                }
            }
        }
    }
}
