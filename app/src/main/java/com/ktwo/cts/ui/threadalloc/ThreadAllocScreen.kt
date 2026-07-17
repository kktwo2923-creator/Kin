// 线程分配(设备调优, M5) —— Metric 式布局:
//   冲突检测横幅 → 通用配置(启用/模式/权重) → 普通 App 线程分配(启用/应用黑名单)
//   → 游戏线程分配(启用/自适应/实时调度/游戏库) → 高级(预设导入/自动分配/规则管理/手动绑核)。
// 顶栏 ⓘ 弹「线程分配说明」; 选 App 弹层为草稿式多选(搜索 + 底部「确认选择 (N)」才落盘)。
// 游戏库里绿色名字 = 有预设/规则配置的游戏(Metric 同款约定)。
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
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.ktwo.cts.data.ThreadAllocRules
import com.ktwo.cts.data.ThreadAllocator
import com.ktwo.cts.service.AppSwitchService
import com.ktwo.cts.service.ForegroundDetector
import com.ktwo.cts.ui.SectionTitle
import com.ktwo.cts.ui.TonalCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 游戏库绿色名字用的"有预设配置"绿(深浅主题下都够对比)。 */
private val PresetGreen = Color(0xFF34A853)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThreadAllocScreen(
    onBack: () -> Unit,
    onOpenRules: () -> Unit = {},
    proUnlocked: Boolean = true,
    onNeedPro: () -> Unit = {},
    onOpenManual: () -> Unit = {},
    onOpenPriority: () -> Unit = {},
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
    val blacklistPkgs by ThreadAllocCfg.blacklistPkgsFlow(ctx).collectAsState(initial = emptySet())
    val ruleMap by ThreadAllocRuleStore.flow(ctx).collectAsState(initial = emptyMap())

    var bigCores by remember { mutableStateOf<List<Int>>(emptyList()) }
    var presetPkgs by remember { mutableStateOf<Set<String>>(emptySet()) }
    var conflicts by remember { mutableStateOf<List<TaConflict>>(emptyList()) }
    var showGamePicker by remember { mutableStateOf(false) }
    var showBlacklistPicker by remember { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var gameLibExpanded by rememberSaveable { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var applyingPreset by remember { mutableStateOf(false) }
    var applyingAuto by remember { mutableStateOf(false) }
    // clusterPlan() 读 sysfs(个别 ROM 回退 root), 不能在组合期主线程跑 → 先用兜底拓扑, 再异步求真值
    var plan by remember { mutableStateOf(ThreadAllocRules.fallbackPlan()) }
    LaunchedEffect(Unit) {
        val p = withContext(Dispatchers.IO) { runCatching { ThreadAllocRules.clusterPlan() }.getOrNull() }
        if (p != null) plan = p
    }

    // 把总开关 + 游戏库 + 黑名单同步进守护 config.json 的 Function.ThreadAlloc 段(守护据此持续绑核)。
    // 先落 DataStore 再从 DataStore 读最新值写盘, 避免连续勾选时用到过期的 compose 状态;
    // 内部 runCatching + IO 线程, 无 root 时静默失败, 不打断用户。
    suspend fun syncDaemon() {
        runCatching {
            ThreadAllocDaemonCfg.sync(
                ctx,
                enabled = ThreadAllocCfg.enabled(ctx),
                gamePkgs = ThreadAllocCfg.gamePkgsFlow(ctx).first(),
                rtEnabled = ThreadAllocCfg.gameRtFlow(ctx).first(),   // 实时调度开关 → rt_enable
                blacklistPkgs = ThreadAllocCfg.blacklistPkgsFlow(ctx).first(),
            )
            // 补推一次当前规则: 用户可能先建好规则、再打开开关; 确保守护拿到最新规则文件。
            ThreadAllocRuleStore.syncToDaemon(ctx)
            com.ktwo.cts.data.ThreadPrioRuleStore.syncToDaemon(ctx)
        }
    }

    // 进页一次: 大核识别 + 预设包名集合 + 冲突检测, 全部后台跑, 失败静默
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val b = runCatching { ThreadAllocator.bigCoreCpus() }.getOrDefault(emptyList())
            val pp = runCatching { ThreadAllocRuleStore.bundledPresetPkgs(ctx) }.getOrDefault(emptySet())
            withContext(Dispatchers.Main) { bigCores = b; presetPkgs = pp }
        }
        conflicts = runCatching { TaConflictDetect.detect() }.getOrDefault(emptyList())
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
                actions = {
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "线程分配说明")
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
            // ── 冲突检测横幅(Metric 式): 红 = 冲突, 黄 = 提醒; 可修复的给「修复」按钮 ──
            conflicts.forEach { c ->
                TaConflictCard(
                    conflict = c,
                    onFix = {
                        scope.launch {
                            val ok = TaConflictDetect.kill(c.killProc!!)
                            conflicts = runCatching { TaConflictDetect.detect() }.getOrDefault(emptyList())
                            snackbar.showSnackbar(if (ok) "已结束 ${c.killProc}" else "修复失败, 请稍后重试")
                        }
                    },
                )
            }

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

            // ── 通用配置 ──
            SectionTitle("通用配置")
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
                    // 模式: 亲和 / 迁移(Metric 同款分段选择)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("模式", fontWeight = FontWeight.Medium)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = mode == ThreadAllocCfg.MODE_AFFINITY,
                                onClick = {
                                    if (!proUnlocked) onNeedPro()
                                    else scope.launch { ThreadAllocCfg.setMode(ctx, ThreadAllocCfg.MODE_AFFINITY) }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            ) { Text("亲和") }
                            SegmentedButton(
                                selected = mode == ThreadAllocCfg.MODE_MIGRATION,
                                onClick = {
                                    if (!proUnlocked) onNeedPro()
                                    else scope.launch { ThreadAllocCfg.setMode(ctx, ThreadAllocCfg.MODE_MIGRATION) }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            ) { Text("迁移") }
                        }
                        Text(
                            if (mode == ThreadAllocCfg.MODE_MIGRATION)
                                "迁移策略即将支持, 当前版本仍按亲和(绑核)方式生效。"
                            else "亲和 = 强制绑定核心放置, 理论上表现更好; 迁移 = 允许调度器按需搬移, 帧率可能更稳。",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mode == ThreadAllocCfg.MODE_MIGRATION) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    TaSwitchRow(
                        "权重配置",
                        "同步调整线程 CPU 权重, 理论上帧率和流畅度更稳 · 即将支持",
                        checked = gameWeight && proUnlocked,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                    ) { v -> scope.launch { ThreadAllocCfg.setGameWeight(ctx, v) } }
                }
            }

            // ── 普通 App 线程分配 ──
            SectionTitle("普通 App 线程分配")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TaSwitchRow(
                        "启用线程分配",
                        "把前台普通应用的渲染类线程归拢到合适核心, 滑动更跟手",
                        checked = appEnabled && proUnlocked,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                    ) { v -> scope.launch { ThreadAllocCfg.setAppEnabled(ctx, v) } }
                    HorizontalDivider()
                    TaNavRow(
                        icon = Icons.Outlined.Block,
                        title = "应用黑名单",
                        desc = if (blacklistPkgs.isEmpty()) "已选择的 App 不会执行线程分配"
                               else "已选择的 App 不会执行线程分配 · 已选 ${blacklistPkgs.size} 个",
                    ) { if (!proUnlocked) onNeedPro() else showBlacklistPicker = true }
                }
            }

            // ── 游戏线程分配 ──
            SectionTitle("游戏线程分配")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TaSwitchRow(
                        "启用线程分配",
                        "游戏在前台时, 把渲染/引擎线程固定到大核",
                        checked = gameEnabled && proUnlocked,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                    ) { v -> scope.launch { ThreadAllocCfg.setGameEnabled(ctx, v) } }
                    HorizontalDivider()
                    TaSwitchRow(
                        "自适应分配",
                        "对没有预设配置的游戏尝试按核心布局自适应分配(普通 App 始终自适应)",
                        checked = gameAdaptive && proUnlocked,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                        enabled = gameEnabled || !proUnlocked,
                    ) { v -> scope.launch { ThreadAllocCfg.setGameAdaptive(ctx, v) } }
                    HorizontalDivider()
                    TaSwitchRow(
                        "实时调度",
                        "提高关键线程优先级(SCHED_FIFO/RR), 减少高负载下的调度延迟。规则见下方「高级」",
                        checked = gameRt && proUnlocked,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                        enabled = gameEnabled || !proUnlocked,
                    ) { v -> scope.launch { ThreadAllocCfg.setGameRt(ctx, v); syncDaemon() } }
                    HorizontalDivider()
                    // ── 游戏库(Metric 式): 标题 + 编辑按钮 + 已选游戏平铺, 绿色名字 = 有预设配置 ──
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.SportsEsports, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("游戏库", fontWeight = FontWeight.Medium)
                            Text(
                                if (gamePkgs.isEmpty()) "还没有添加游戏"
                                else "已选 ${gamePkgs.size} 款 · 绿色名字 = 有预设配置",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(onClick = {
                            if (!proUnlocked) onNeedPro() else showGamePicker = true
                        }) { Text("编辑游戏库") }
                    }
                    if (gamePkgs.isNotEmpty()) {
                        GameLibraryFlow(
                            pkgs = gamePkgs,
                            presetPkgs = remember(presetPkgs, ruleMap) {
                                presetPkgs + ruleMap.keys.map { it.substringBefore(':') }
                            },
                            expanded = gameLibExpanded,
                            onToggleExpand = { gameLibExpanded = !gameLibExpanded },
                        )
                    }
                }
            }

            // ── 高级(本 App 特有能力, Metric 主页之外的进阶入口) ──
            SectionTitle("高级")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TaActionRow(
                        icon = Icons.AutoMirrored.Outlined.ListAlt,
                        title = "一键使用预设规则",
                        desc = "导入内置热门应用/游戏的绑核规则, 自动适配本机核心布局",
                        buttonText = "导入", busy = applyingPreset,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                    ) {
                        applyingPreset = true
                        scope.launch {
                            val n = runCatching { ThreadAllocRuleStore.importBundledPreset(ctx) }.getOrDefault(0)
                            if (n > 0) {
                                com.ktwo.cts.service.ThreadRuleService.start(ctx)
                                syncDaemon()
                            }
                            applyingPreset = false
                            snackbar.showSnackbar(
                                if (n > 0) "已导入 $n 个应用的预设规则(已适配本机核心布局)"
                                else "导入失败, 请确认 Root 权限"
                            )
                        }
                    }
                    HorizontalDivider()
                    TaActionRow(
                        icon = Icons.Outlined.AutoFixHigh,
                        title = "自动分配(前台应用)",
                        desc = "识别前台应用的引擎类型, 自动生成绑核规则并生效",
                        buttonText = "分配", busy = applyingAuto,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                    ) {
                        applyingAuto = true
                        scope.launch {
                            val fg = withContext(Dispatchers.IO) {
                                AppSwitchService.foreground.ifBlank {
                                    runCatching { ForegroundDetector(ctx).currentForegroundPackage() }.getOrNull().orEmpty()
                                }
                            }
                            if (fg.isBlank() || fg == ctx.packageName) {
                                applyingAuto = false
                                snackbar.showSnackbar("没拿到前台应用(本应用不算), 请先切到目标应用再回来点")
                                return@launch
                            }
                            val res = autoDetectRules(ctx, fg, plan)
                            ThreadAllocRuleStore.put(ctx, fg, res.rules)
                            com.ktwo.cts.service.ThreadRuleService.start(ctx)
                            syncDaemon()
                            applyingAuto = false
                            snackbar.showSnackbar("${labelOf(ctx, fg)}: ${detectLabel(res)}, 已生成并生效")
                        }
                    }
                    HorizontalDivider()
                    TaActionRow(
                        icon = Icons.Outlined.Bolt,
                        title = "立即应用",
                        desc = "把当前前台应用的重负载线程立刻归拢到大核(一次性, 守护未运行时用)",
                        buttonText = "应用", busy = applying,
                        proUnlocked = proUnlocked, onNeedPro = onNeedPro,
                    ) {
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
                    }
                    HorizontalDivider()
                    TaNavRow(
                        icon = Icons.AutoMirrored.Outlined.ListAlt,
                        title = "线程规则管理",
                        desc = "按应用配置「线程名→绑核」规则, 支持预设/导入导出 · 需要 Root",
                    ) { if (!proUnlocked) onNeedPro() else onOpenRules() }
                    HorizontalDivider()
                    TaNavRow(
                        icon = Icons.Outlined.Bolt,
                        title = "实时优先级规则",
                        desc = "按应用配置「线程名→实时调度策略」, 需先开「实时调度」 · 需要 Root",
                    ) { if (!proUnlocked) onNeedPro() else onOpenPriority() }
                    HorizontalDivider()
                    TaNavRow(
                        icon = Icons.Outlined.Tune,
                        title = "手动线程绑核",
                        desc = "选应用查看线程列表, 手动把某个线程绑到指定核心 · 需要 Root",
                    ) { if (!proUnlocked) onNeedPro() else onOpenManual() }
                }
            }

            Text(
                "开启后由 Kin 守护在后台持续生效: 有「线程规则」的应用按规则逐线程绑核; " +
                    "游戏库中的游戏在前台且未配规则时, 自动把渲染线程绑到大核; " +
                    "黑名单中的应用一律不做线程分配。改规则即时热重载, 无需重启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(96.dp))
        }
    }

    // ── 线程分配说明(Metric 式 ⓘ 弹窗) ──
    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text("线程分配说明") },
            text = {
                Text(
                    "模式:\n" +
                        "亲和, 理论上表现更好, 强制绑定核心放置\n" +
                        "迁移, 帧率可能更稳定(即将支持)\n\n" +
                        "自适应分配:\n" +
                        "对没有预设配置的游戏尝试按核心布局自适应分配(普通 App 始终自适应)\n\n" +
                        "实时调度:\n" +
                        "将关键线程切到实时队列(SCHED_FIFO/RR), 以减少高负载下的调度延迟, 极端情况可能出现卡死\n\n" +
                        "权重配置:\n" +
                        "同步调整线程 CPU 权重, 理论上帧率和流畅度更稳\n\n" +
                        "应用黑名单:\n" +
                        "黑名单中的应用一律不执行线程分配(即使配了规则)\n\n" +
                        "游戏库显示绿色名字的是有预设配置的游戏",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text("知道了") }
            },
        )
    }

    // ── 选 App 弹层(草稿式, 底部「确认选择 (N)」才落盘) ──
    if (showGamePicker) {
        AppPickerSheet(
            title = "编辑游戏库",
            initial = gamePkgs,
            onConfirm = { picked ->
                showGamePicker = false
                scope.launch { ThreadAllocCfg.setGamePkgs(ctx, picked); syncDaemon() }
            },
            onDismiss = { showGamePicker = false },
        )
    }
    if (showBlacklistPicker) {
        AppPickerSheet(
            title = "应用黑名单",
            initial = blacklistPkgs,
            onConfirm = { picked ->
                showBlacklistPicker = false
                scope.launch { ThreadAllocCfg.setBlacklistPkgs(ctx, picked); syncDaemon() }
            },
            onDismiss = { showBlacklistPicker = false },
        )
    }
}

/** 冲突横幅: 红 = 错误级冲突, 黄 = 提醒级; killProc 非空时给「修复」按钮。 */
@Composable
private fun TaConflictCard(conflict: TaConflict, onFix: () -> Unit) {
    val container = if (conflict.isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.tertiaryContainer
    val content = if (conflict.isError) MaterialTheme.colorScheme.onErrorContainer
                  else MaterialTheme.colorScheme.onTertiaryContainer
    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (conflict.isError) Icons.Outlined.ErrorOutline else Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = content,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                conflict.msg,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                modifier = Modifier.weight(1f),
            )
            if (conflict.killProc != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onFix) { Text("修复", color = content, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** 游戏库平铺(Metric 式): 图标 + 名字, 绿色名字 = 有预设/规则配置; 超过 8 款折叠。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameLibraryFlow(
    pkgs: Set<String>,
    presetPkgs: Set<String>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<PickEntry>>(emptyList()) }
    LaunchedEffect(pkgs) {
        entries = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pkgs.sorted().map { pkg ->
                runCatching {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    PickEntry(pkg, pm.getApplicationLabel(ai).toString(),
                        runCatching { pm.getApplicationIcon(ai) }.getOrNull(), false)
                }.getOrElse { PickEntry(pkg, pkg.substringAfterLast('.'), null, false) }
            }
        }
    }
    val visible = if (expanded) entries else entries.take(8)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visible.forEach { e ->
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val bmp = remember(e.pkg) {
                        runCatching { e.icon?.toBitmap(48, 48) }.getOrNull()
                    }
                    if (bmp != null) {
                        Image(
                            painter = BitmapPainter(bmp.asImageBitmap()),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        e.label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (e.pkg in presetPkgs) PresetGreen
                                else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (entries.size > 8) {
            TextButton(onClick = onToggleExpand) {
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                )
                Spacer(Modifier.width(4.dp))
                Text(if (expanded) "收起游戏库" else "展开游戏库(共 ${entries.size} 款)")
            }
        }
    }
}

/** 带锁图标的开关行: 未解锁时点开关引导去付费页; enabled=false 时整行淡显且不可点。 */
@Composable
private fun TaSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    proUnlocked: Boolean,
    onNeedPro: () -> Unit,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val alpha = if (enabled) 1f else 0.45f
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                )
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
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { want ->
                if (!proUnlocked) onNeedPro() else onChange(want)
            },
        )
    }
}

/** 导航行: 图标 + 标题/说明 + 右箭头。 */
@Composable
private fun TaNavRow(
    icon: ImageVector,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                desc,
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

/** 动作行: 图标 + 标题/说明 + 主按钮(未解锁点按钮引导付费, busy 时禁用)。 */
@Composable
private fun TaActionRow(
    icon: ImageVector,
    title: String,
    desc: String,
    buttonText: String,
    busy: Boolean,
    proUnlocked: Boolean,
    onNeedPro: () -> Unit,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        FilledTonalButton(
            onClick = { if (!proUnlocked) onNeedPro() else if (!busy) onClick() },
            enabled = !busy,
        ) { Text(if (busy) "…" else buttonText) }
    }
}

private data class PickEntry(
    val pkg: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val isSystem: Boolean,
)

/**
 * 选 App 弹层(Metric 式草稿多选): 搜索 + 复选列表 + 底部「确认选择 (N)」。
 * 勾选只改草稿, 点确认才回调落盘; 下滑/点外关闭 = 放弃修改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    title: String,
    initial: Set<String>,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<PickEntry>>(emptyList()) }
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val draft = remember { mutableStateOf(initial) }

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

    val filtered = remember(apps, query.text) {
        apps.filter {
            // 默认只列用户应用; 已勾选的(哪怕是系统应用)始终保留在列表里
            (!it.isSystem || it.pkg in draft.value) &&
                (query.text.isEmpty() ||
                    it.label.contains(query.text, ignoreCase = true) ||
                    it.pkg.contains(query.text, ignoreCase = true))
        }.sortedWith(
            compareByDescending<PickEntry> { it.pkg in initial }
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
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "已选 ${draft.value.size} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索 App") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (apps.isNotEmpty() && filtered.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "未找到 App",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 440.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(filtered, key = { it.pkg }) { app ->
                    val checked = app.pkg in draft.value
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                draft.value = if (checked) draft.value - app.pkg else draft.value + app.pkg
                            }
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
                            checked = checked,
                            onCheckedChange = {
                                draft.value = if (checked) draft.value - app.pkg else draft.value + app.pkg
                            },
                        )
                    }
                }
            }
            Button(
                onClick = { onConfirm(draft.value) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) { Text("确认选择 (${draft.value.size})") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
