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
                    SpAnswerRow("睡眠时间", "${latest.sleepStart} 点睡 · ${latest.sleepEnd} 点起")
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
                    "智能调度的六个负载状态各自的频率上限;未自定义的状态由智能曲线按学习结果自动决定。",
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

    if (showSurvey) {
        SurveySheet(
            initial = latest,
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
    // 尾部摘要: 覆盖了就报百分比, 没覆盖就报智能曲线大概压到多少 MHz。
    val summary = if (ov.anySet) {
        buildList {
            if (ov.maxL in 10..100) add("小核${ov.maxL}%")
            if (ov.maxB in 10..100) add("大核${ov.maxB}%")
            add(if (ov.gpu in 1..100) "GPU ${ov.gpu}%" else "GPU 跟随智能")
        }.joinToString(" · ")
    } else {
        val l = FsmCurve.maxCapKhz(idx, alpha, little = true, hwMaxKhz = hwMaxL) / 1000
        val b = FsmCurve.maxCapKhz(idx, alpha, little = false, hwMaxKhz = hwMaxB) / 1000
        if (hwMaxL > 0 && hwMaxB > 0) "智能曲线 ~${l}MHz/~${b}MHz"
        else "智能曲线 ~${l}MHz/~${b}MHz(参考机型换算)"
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
                                    maxL = curvePct(idx, alpha, little = true, hwMaxKhz = hwMaxL),
                                    maxB = curvePct(idx, alpha, little = false, hwMaxKhz = hwMaxB),
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
                            else curvePct(idx, alpha, little = true, hwMaxKhz = hwMaxL),
                            10, 100, unit = "%",
                        ) { onChange(ov.copy(maxL = it)) }
                        SpSlider(
                            "大核上限", "占本机大核最高频的百分比",
                            if (ov.maxB in 10..100) ov.maxB
                            else curvePct(idx, alpha, little = false, hwMaxKhz = hwMaxB),
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

/** 智能曲线上限换算成百分比(自定义开关打开时的初始值); 读不到本机最高频时给 100。 */
private fun curvePct(stateIdx: Int, alpha: Int, little: Boolean, hwMaxKhz: Long): Int {
    if (hwMaxKhz <= 0) return 100
    val khz = FsmCurve.maxCapKhz(stateIdx, alpha, little, hwMaxKhz)
    return (khz * 100 / hwMaxKhz).toInt().coerceIn(10, 100)
}

/* ──────────────────────────── 问卷底部弹窗 ──────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SurveySheet(
    initial: SurveyAnswers?,
    onDismiss: () -> Unit,
    onDone: (SurveyAnswers) -> Unit,
) {
    // rememberSaveable: 旋转/去解锁页再回来, 答题进度与已选项都不丢。
    var step by rememberSaveable { mutableStateOf(0) }
    var sleepStart by rememberSaveable { mutableStateOf(initial?.sleepStart ?: 23) }
    var sleepEnd by rememberSaveable { mutableStateOf(initial?.sleepEnd ?: 7) }
    var powerPref by rememberSaveable { mutableStateOf(initial?.powerPref ?: "balanced") }
    var dailyLag by rememberSaveable { mutableStateOf(initial?.dailyLag ?: "smooth") }
    var heavyLag by rememberSaveable { mutableStateOf(initial?.heavyLag ?: "ok") }
    var heat by rememberSaveable { mutableStateOf(initial?.heat ?: "normal") }

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
                    Text("你一般几点睡、几点起?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "用来设置夜间安静时段: 睡觉熄屏时强制省电, 少耗电。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SpSlider("就寝", "晚上几点睡(24 小时制)", sleepStart, 0, 23, unit = "时") { sleepStart = it }
                    SpSlider("起床", "早上几点起", sleepEnd, 0, 23, unit = "时") { sleepEnd = it }
                }
                1 -> SurveyChips(
                    "平时更希望手机偏省电还是偏流畅?",
                    "决定智能调度整体的省电倾向。",
                    SmartSurvey.POWER_PREF_OPTIONS, powerPref,
                ) { powerPref = it }
                2 -> SurveyChips(
                    "日常滑动/刷视频卡不卡?",
                    "按感受调整轻度/中度负载下的频率上限。",
                    SmartSurvey.DAILY_LAG_OPTIONS, dailyLag,
                ) { dailyLag = it }
                3 -> SurveyChips(
                    "玩游戏/重度使用时卡不卡?",
                    "按感受调整高负载/游戏状态的频率上限。",
                    SmartSurvey.HEAVY_LAG_OPTIONS, heavyLag,
                ) { heavyLag = it }
                4 -> SurveyChips(
                    "手机发烫在不在意?",
                    "决定多热开始压频散热(越在意越早压)。",
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

private fun fmtSurveyTime(ts: Long): String =
    if (ts <= 0) "—"
    else java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(ts))

/** 历史记录一行摘要: 各题答案的中文串。 */
private fun surveySummary(a: SurveyAnswers): String = listOf(
    "睡眠 ${a.sleepStart}–${a.sleepEnd} 点",
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
