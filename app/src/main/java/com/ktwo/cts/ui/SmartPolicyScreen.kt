// 智能策略页: 问答调优(5 题小问卷)+ FSM 六状态频率上限覆盖(fsm_policy), 写回 config.json 热重载。
// 问卷把口语化的使用感受(卡不卡/在不在意发烫)翻译成调度参数; 六状态列表实时高亮当前命中状态。
package com.ktwo.cts.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ktwo.cts.data.AutoModeCfg
import com.ktwo.cts.data.FsmCurve
import com.ktwo.cts.data.FsmStateOverride
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.SmartPolicyCfg
import com.ktwo.cts.data.SmartSurvey
import com.ktwo.cts.data.SurveyAnswers
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartPolicyScreen(
    onBack: () -> Unit,
    openSurvey: Boolean = false,
    proUnlocked: Boolean = true,
    onNeedPro: () -> Unit = {},
    vm: SmartPolicyVm = viewModel(),
) {
    val ctx = LocalContext.current
    val editing by vm.editing.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val autoCfg by vm.autoCfg.collectAsStateWithLifecycle()
    val hwMaxL by vm.hwMaxL.collectAsStateWithLifecycle()
    val hwMaxB by vm.hwMaxB.collectAsStateWithLifecycle()

    val surveyJson by Prefs.smartSurveyFlow(ctx).collectAsState(initial = "")
    val historyJson by Prefs.smartSurveyHistoryFlow(ctx).collectAsState(initial = "[]")
    val latest = remember(surveyJson) { SurveyAnswers.parse(surveyJson) }

    // rememberSaveable: 旋转/进程重建不丢问卷弹窗状态; autoOpened 记"已自动拉起过",
    // 防止用户关掉问卷后一转屏又被强行弹回来。
    var showSurvey by rememberSaveable { mutableStateOf(false) }
    var surveyAutoOpened by rememberSaveable { mutableStateOf(false) }
    // openSurvey=true(从提示弹窗跳来)时自动拉起问卷, 只消费一次。
    LaunchedEffect(Unit) {
        if (openSurvey && !surveyAutoOpened) {
            surveyAutoOpened = true
            showSurvey = true
        }
    }

    val snackbar = remember { SnackbarHostState() }
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
        vm.surveyResult.collect { r ->
            if (r != null) {
                vm.clearSurveyResult()
                // 写成功才关问卷; 失败保留弹窗与已选答案, 用户修好 Root 后可直接重试。
                if (r) showSurvey = false
                snackbar.showSnackbar(
                    if (r) "已按回答调整智能调度"
                    else "写入失败,请检查 Root 权限"
                )
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("智能策略") },
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
        floatingActionButton = {
            AnimatedVisibility(visible = dirty) {
                ExtendedFloatingActionButton(
                    // 未解锁时引导去付费页, 不落盘(与智能调度设置页一致)。
                    onClick = { if (!proUnlocked) onNeedPro() else vm.save() },
                    text = { Text("保存") },
                    icon = { Icon(Icons.Filled.Check, contentDescription = null) },
                )
            }
        },
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { inner ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 问答调优 ──
            SpSection("问答调优") {
                if (latest != null) {
                    SpAnswerRow(
                        "睡眠时间",
                        if (latest.nightOk) "跟随自动学习"
                        else "${latest.sleepStart} 点睡 · ${latest.sleepEnd} 点起",
                    )
                    HorizontalDivider()
                    SpAnswerRow("省电偏好", SmartSurvey.optionLabel(SmartSurvey.POWER_PREF_OPTIONS, latest.powerPref))
                    HorizontalDivider()
                    SpAnswerRow("日常滑动/刷视频", SmartSurvey.optionLabel(SmartSurvey.DAILY_LAG_OPTIONS, latest.dailyLag))
                    HorizontalDivider()
                    SpAnswerRow("玩游戏/重度使用", SmartSurvey.optionLabel(SmartSurvey.HEAVY_LAG_OPTIONS, latest.heavyLag))
                    HorizontalDivider()
                    SpAnswerRow("手机发烫", SmartSurvey.optionLabel(SmartSurvey.HEAT_OPTIONS, latest.heat))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "回答时间 ${fmtSurveyTime(latest.ts)}",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = { showSurvey = true }) { Text("重新回答") }
                    }
                } else {
                    Text(
                        "回答几个小问题(睡眠时间、省电偏好、日常/游戏卡不卡、在不在意发烫)," +
                            "智能调度会按你的习惯把不同负载下的频率上限调得更合适。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { showSurvey = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("开始回答") }
                }

                // 历史记录(可展开)
                var showHistory by remember { mutableStateOf(false) }
                HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showHistory = !showHistory }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("历史记录", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    Icon(
                        if (showHistory) Icons.Outlined.KeyboardArrowUp
                        else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = showHistory) {
                    val entries = remember(historyJson) { parseSurveyHistory(historyJson) }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (entries.isEmpty()) {
                            Text(
                                "暂无记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else entries.forEach { e ->
                            Column {
                                Text(
                                    fmtSurveyTime(e.ts),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    surveySummary(e),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // ── 状态频率策略 ──
            SpSection("状态频率策略") {
                Text(
                    "六个负载状态各自的频率「上限」(封顶值, 不是实时运行频率)。" +
                        "带 ~ 的上限由智能曲线按学习结果自动决定、随省电倾向浮动;自定义后为固定封顶。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var expandedState by remember { mutableStateOf<String?>(null) }
                SmartPolicyCfg.STATE_ORDER.forEachIndexed { idx, key ->
                    HorizontalDivider()
                    FsmStateRow(
                        idx = idx,
                        stateKey = key,
                        ov = editing[key] ?: FsmStateOverride(),
                        active = live.fsm.equals(key, ignoreCase = true),
                        alpha = live.alpha,
                        hwMaxL = hwMaxL,
                        hwMaxB = hwMaxB,
                        expanded = expandedState == key,
                        onToggleExpand = { expandedState = if (expandedState == key) null else key },
                        onChange = { vm.update(key, it) },
                    )
                }
            }

            Spacer(Modifier.height(96.dp))
        }
    }

    // 数据就绪门: config(loading=false)与 auto_status(live.valid)都到位后再组合问卷,
    // rememberSaveable 的种子值才来自真实数据 —— 否则会把"已学到的作息"误判成"未学到",
    // 默认时段被当成纠正下发, 反把真学到的窗口冲掉。守护没跑时 auto_status 永远读不到:
    // 3 秒超时放行, 按"未学到"处理。
    var surveyGateTimeout by remember { mutableStateOf(false) }
    LaunchedEffect(showSurvey) {
        surveyGateTimeout = false
        if (showSurvey) {
            kotlinx.coroutines.delay(3000)
            surveyGateTimeout = true
        }
    }
    if (showSurvey && !loading && (live.valid || surveyGateTimeout)) {
        SurveySheet(
            live = live,
            autoCfg = autoCfg,
            editing = editing,
            hwMaxL = hwMaxL,
            hwMaxB = hwMaxB,
            onDismiss = { showSurvey = false },
            onDone = { answers ->
                if (!proUnlocked) {
                    onNeedPro()
                } else {
                    // 不在这里关弹窗: 等 surveyResult=true 再关, 写失败时答案不丢。
                    vm.applySurvey(answers)
                }
            },
        )
    }
}

/* ──────────────────────────── 六状态行 ──────────────────────────── */

@Composable
private fun FsmStateRow(
    idx: Int,
    stateKey: String,
    ov: FsmStateOverride,
    active: Boolean,
    alpha: Int,
    hwMaxL: Long,
    hwMaxB: Long,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onChange: (FsmStateOverride) -> Unit,
) {
    val cn = SmartPolicyCfg.STATE_CN[stateKey] ?: stateKey
    // 摘要统一为「上限 小核/大核MHz · GPU% · 来源」—— 全是封顶值(scaling_max), 不是实时频率。
    // 自定义百分比换算成 MHz(不带 ~, 固定封顶); 曲线值带 ~(随学习/省电倾向浮动)。
    // alpha 兜底 500, 与问卷展示/应用口径一致。
    val alphaEff = if (alpha >= 0) alpha else 500
    val gpuPct = if (ov.gpu in 1..100) ov.gpu else FsmCurve.GPU_BUILTIN.getOrElse(idx) { 100 }
    val summary = if (hwMaxL > 0 && hwMaxB > 0) {
        val lTxt = ov.maxL.takeIf { it in 10..100 }
            ?.let { "${it * hwMaxL / 100 / 1000}" }
            ?: "~${FsmCurve.maxCapKhz(idx, alphaEff, little = true, hwMaxKhz = hwMaxL) / 1000}"
        val bTxt = ov.maxB.takeIf { it in 10..100 }
            ?.let { "${it * hwMaxB / 100 / 1000}" }
            ?: "~${FsmCurve.maxCapKhz(idx, alphaEff, little = false, hwMaxKhz = hwMaxB) / 1000}"
        "上限 $lTxt/${bTxt}MHz · GPU ${gpuPct}% · " + (if (ov.anySet) "自定义" else "智能曲线")
    } else if (ov.anySet) {
        // 读不到本机最高频: 自定义无法换算 MHz, 退回百分比表述。
        buildList {
            if (ov.maxL in 10..100) add("小核${ov.maxL}%")
            if (ov.maxB in 10..100) add("大核${ov.maxB}%")
            add("GPU ${gpuPct}%")
        }.joinToString(" · ").let { "上限 $it · 自定义" }
    } else {
        val l = FsmCurve.maxCapKhz(idx, alphaEff, little = true, hwMaxKhz = hwMaxL) / 1000
        val b = FsmCurve.maxCapKhz(idx, alphaEff, little = false, hwMaxKhz = hwMaxB) / 1000
        "上限 ~$l/~${b}MHz · GPU ${gpuPct}% · 智能曲线(参考机型换算)"
    }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 状态点: 当前命中状态高亮为主色。
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                        CircleShape,
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        cn,
                        fontWeight = FontWeight.Medium,
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "当前",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = expanded) {
            // 编辑区可见性用显式 UI 状态, 不直接跟随 ov.anySet: 否则"仅 GPU 覆盖"的状态把
            // GPU 滑条拖到 0(合法的"跟随智能"档)时 anySet 瞬间变 false, 编辑区会在手指
            // 还没抬起时塌掉、开关自己弹回。展开该行时按当前是否有覆盖初始化。
            var custom by remember(stateKey, expanded) { mutableStateOf(ov.anySet) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自定义上限", fontWeight = FontWeight.Medium)
                        Text(
                            "关闭则该状态整体交回智能曲线",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = custom,
                        onCheckedChange = { on ->
                            custom = on
                            if (!on) onChange(FsmStateOverride())
                            else onChange(
                                FsmStateOverride(
                                    maxL = FsmCurve.curvePct(idx, alpha, little = true, hwMaxKhz = hwMaxL),
                                    maxB = FsmCurve.curvePct(idx, alpha, little = false, hwMaxKhz = hwMaxB),
                                    gpu = 0,   // 默认仍跟随智能, 想改再拖 GPU 滑条
                                )
                            )
                        },
                    )
                }
                AnimatedVisibility(visible = custom) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SpSlider(
                            "小核上限", "占本机小核最高频的百分比",
                            if (ov.maxL in 10..100) ov.maxL
                            else FsmCurve.curvePct(idx, alpha, little = true, hwMaxKhz = hwMaxL),
                            10, 100, unit = "%",
                        ) { onChange(ov.copy(maxL = it)) }
                        SpSlider(
                            "大核上限", "占本机大核最高频的百分比",
                            if (ov.maxB in 10..100) ov.maxB
                            else FsmCurve.curvePct(idx, alpha, little = false, hwMaxKhz = hwMaxB),
                            10, 100, unit = "%",
                        ) { onChange(ov.copy(maxB = it)) }
                        SpSlider(
                            "GPU 上限", "0 = 跟随智能(内置 ${FsmCurve.GPU_BUILTIN.getOrElse(idx) { 100 }}%)",
                            ov.gpu.coerceIn(0, 100), 0, 100, unit = "%",
                        ) { onChange(ov.copy(gpu = it)) }
                        TextButton(onClick = {
                            custom = false
                            onChange(FsmStateOverride())
                        }) { Text("恢复智能") }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

/* ──────────────────────────── 问卷底部弹窗 ──────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SurveySheet(
    live: SmartLiveStatus,
    autoCfg: AutoModeCfg,
    editing: Map<String, FsmStateOverride>,
    hwMaxL: Long,
    hwMaxB: Long,
    onDismiss: () -> Unit,
    onDone: (SurveyAnswers) -> Unit,
) {
    // 是否已学到作息(样本 >=3 且 daemon 已给出学习值)。
    val learned = live.nightSamples >= 3 && live.nightSleep >= 0 && live.nightWake >= 0

    // rememberSaveable: 旋转/去解锁页再回来, 答题进度与已选项都不丢。
    // 默认全是"确认现状": 学到作息时默认"准确", 其余题默认"保持现状"。
    var step by rememberSaveable { mutableStateOf(0) }
    var nightOk by rememberSaveable { mutableStateOf(learned) }
    // 用户是否主动改过作息(点了"不太准"或动过滑条): 只有主动纠正才下发 night_seed/改配置,
    // 一路"保持现状"点完不会把默认时段当成回答冲掉学习数据。
    var nightTouched by rememberSaveable { mutableStateOf(false) }
    var sleepStart by rememberSaveable {
        mutableStateOf(if (learned) live.nightSleep / 60 else autoCfg.nightStart)
    }
    var sleepEnd by rememberSaveable {
        mutableStateOf(if (learned) live.nightWake / 60 else autoCfg.nightEnd)
    }
    var powerPref by rememberSaveable { mutableStateOf("keep") }
    var dailyLag by rememberSaveable { mutableStateOf("keep") }
    var heavyLag by rememberSaveable { mutableStateOf("keep") }
    var heat by rememberSaveable { mutableStateOf("keep") }

    // 某状态当前生效的 CPU 上限描述(覆盖优先, 否则智能曲线换算)。
    // alpha 兜底 500 与 VM 构造 SurveyContext 的口径一致: 展示基线 = 应用基线。
    fun effCpu(stateKey: String, idx: Int): String {
        val a = live.alpha.takeIf { it >= 0 } ?: 500
        val ov = editing[stateKey]
        val l = ov?.maxL?.takeIf { it in 10..100 }
            ?: FsmCurve.curvePct(idx, a, little = true, hwMaxKhz = hwMaxL)
        val b = ov?.maxB?.takeIf { it in 10..100 }
            ?: FsmCurve.curvePct(idx, a, little = false, hwMaxKhz = hwMaxB)
        return "小核${l}%/大核${b}%"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "第${step + 1}/5题",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            when (step) {
                0 -> {
                    Text("先确认下你的作息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (learned) {
                        Text(
                            "已学到你大约 ${fmtMin(live.nightSleep)} 睡 · ${fmtMin(live.nightWake)} 起",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = nightOk,
                                onClick = { nightOk = true },
                                label = { Text("准确,就按这个学") },
                            )
                            FilterChip(
                                selected = !nightOk,
                                onClick = { nightOk = false; nightTouched = true },
                                label = { Text("不太准,我来改") },
                            )
                        }
                        AnimatedVisibility(visible = !nightOk) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SpSlider("就寝", "晚上几点睡(24 小时制)", sleepStart, 0, 23, unit = "时") { sleepStart = it; nightTouched = true }
                                SpSlider("起床", "早上几点起", sleepEnd, 0, 23, unit = "时") { sleepEnd = it; nightTouched = true }
                            }
                        }
                    } else {
                        // 未学到分支必须视作"待纠正": 防止旧的 nightOk=true 存档残留,
                        // 导致用户拨了滑条却因 nightOk 而被整体忽略。
                        LaunchedEffect(Unit) { nightOk = false }
                        Text(
                            "还没学到你的作息(需 3 个晚上), 先手动告诉我: 夜间安静时段用来睡觉时强制省电。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SpSlider("就寝", "晚上几点睡(24 小时制)", sleepStart, 0, 23, unit = "时") { sleepStart = it; nightTouched = true }
                        SpSlider("起床", "早上几点起", sleepEnd, 0, 23, unit = "时") { sleepEnd = it; nightTouched = true }
                    }
                }
                1 -> SurveyChips(
                    "现在的省电/流畅平衡合适吗?",
                    when {
                        live.alpha < 0 -> "暂无学习数据"
                        live.alpha > 650 -> "目前整体偏省电(省电倾向 ${live.alpha}‰, 手动偏移 ${autoCfg.batteryBias})"
                        live.alpha < 350 -> "目前整体偏流畅(省电倾向 ${live.alpha}‰, 手动偏移 ${autoCfg.batteryBias})"
                        else -> "目前整体均衡(省电倾向 ${live.alpha}‰, 手动偏移 ${autoCfg.batteryBias})"
                    },
                    SmartSurvey.POWER_PREF_OPTIONS, powerPref,
                ) { powerPref = it }
                2 -> SurveyChips(
                    "日常滑动/刷视频卡不卡?",
                    "当前上限: 轻度 ${effCpu(SmartPolicyCfg.LIGHT_IDLE, 1)} · 中度 ${effCpu(SmartPolicyCfg.MODERATE, 2)}",
                    SmartSurvey.DAILY_LAG_OPTIONS, dailyLag,
                ) { dailyLag = it }
                3 -> SurveyChips(
                    "玩游戏/重度使用时卡不卡?",
                    "当前上限: 高载 ${effCpu(SmartPolicyCfg.HEAVY, 3)} · 持续 ${effCpu(SmartPolicyCfg.SUSTAINED, 4)} · 游戏 ${effCpu(SmartPolicyCfg.GAMING, 5)}",
                    SmartSurvey.HEAVY_LAG_OPTIONS, heavyLag,
                ) { heavyLag = it }
                4 -> SurveyChips(
                    "现在的降温时机合适吗?",
                    "现在 ≥${autoCfg.fsmSustainedEnter}℃ 开始压频降温",
                    SmartSurvey.HEAT_OPTIONS, heat,
                ) { heat = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { step-- },
                    enabled = step > 0,
                    modifier = Modifier.weight(1f),
                ) { Text("上一题") }
                Button(
                    onClick = {
                        if (step < 4) step++
                        else onDone(
                            SurveyAnswers(
                                sleepStart = sleepStart,
                                sleepEnd = sleepEnd,
                                nightOk = nightOk,
                                nightTouched = nightTouched,
                                powerPref = powerPref,
                                dailyLag = dailyLag,
                                heavyLag = heavyLag,
                                heat = heat,
                                ts = System.currentTimeMillis(),
                            )
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(if (step < 4) "下一题" else "完成并应用") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SurveyChips(
    title: String,
    desc: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text(
        desc,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (key, label) ->
            FilterChip(
                selected = selected == key,
                onClick = { onSelect(key) },
                label = { Text(label) },
            )
        }
    }
}

/* ──────────────────────────── 小组件 / 工具 ──────────────────────────── */

/** 当日分钟数 -> "HH:MM"。 */
private fun fmtMin(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

private fun fmtSurveyTime(ts: Long): String =
    if (ts <= 0) "—"
    else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts))

/** 历史记录一行摘要: 各题答案的中文串。 */
private fun surveySummary(a: SurveyAnswers): String = listOf(
    if (a.nightOk) "睡眠:跟随学习" else "睡眠 ${a.sleepStart}–${a.sleepEnd} 点",
    "省电偏好:${SmartSurvey.optionLabel(SmartSurvey.POWER_PREF_OPTIONS, a.powerPref)}",
    "日常:${SmartSurvey.optionLabel(SmartSurvey.DAILY_LAG_OPTIONS, a.dailyLag)}",
    "重度:${SmartSurvey.optionLabel(SmartSurvey.HEAVY_LAG_OPTIONS, a.heavyLag)}",
    "发烫:${SmartSurvey.optionLabel(SmartSurvey.HEAT_OPTIONS, a.heat)}",
).joinToString(" · ")

/** 解析历史 JSON 数组 -> 最新在前。 */
private fun parseSurveyHistory(json: String): List<SurveyAnswers> = runCatching {
    val arr = org.json.JSONArray(json)
    (0 until arr.length())
        .mapNotNull { i -> arr.optJSONObject(i)?.let { SurveyAnswers.parse(it.toString()) } }
        .sortedByDescending { it.ts }
}.getOrDefault(emptyList())

// 以下小组件复刻 AutoModeScreen 的 AmSection/AmSlider(那边是 private, 不跨文件引用)。

@Composable
private fun SpSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionTitle(title)
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

@Composable
private fun SpAnswerRow(label: String, answer: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(answer, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SpSlider(
    title: String, desc: String,
    value: Int, min: Int, max: Int, unit: String,
    step: Int = 1,
    onChange: (Int) -> Unit,
) {
    val v = value.coerceIn(min, max)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            // 紧凑步进胶囊 [ − 值 + ]: 与智能调度设置页一致。
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpStepGlyph("−", v > min) { onChange((v - step).coerceIn(min, max)) }
                    Text(
                        "$v$unit",
                        Modifier.widthIn(min = 32.dp),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    SpStepGlyph("+", v < max) { onChange((v + step).coerceIn(min, max)) }
                }
            }
        }
        Slider(
            value = v.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SpStepGlyph(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .then(if (enabled) Modifier.pressClickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
    }
}
