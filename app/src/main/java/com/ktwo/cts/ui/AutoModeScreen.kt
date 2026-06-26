// 智能档(自动模式)设置页:把 daemon 的 Function.AutoMode 旋钮做成开关/滑块,写回 config.json 热重载。
// 总开关切换全局模式 auto<->balance;其余旋钮改内存副本,点"保存"才落盘。
package com.ktwo.cts.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoModeScreen(onBack: () -> Unit, vm: AutoModeVm = viewModel()) {
    val cfg by vm.editing.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val smartEnabled by vm.smartEnabled.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()

    var showAdvanced by remember { mutableStateOf(false) }
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

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("智能调度") },
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
                    onClick = { vm.save() },
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
            // ── 总开关 ──
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("智能模式", fontWeight = FontWeight.Bold)
                            Text(
                                if (smartEnabled) "已启用 · 全局切到智能档,按温度/电量/负载自动调度"
                                else "关闭 · 全局回到均衡档",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = smartEnabled, onCheckedChange = { vm.setSmartEnabled(it) })
                    }
                    Text(
                        "开启后,守护进程会自学习你的使用习惯,在省电/均衡/性能之间自动切换;下面的开关用来微调它的脾气。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── 智能/自适应 ──
            AmSection("智能 / 自适应") {
                AmToggle(
                    "主动调频", "实时按负载/GPU/温度调 CPU 频率上下限(更跟手);关掉则只在省电/均衡/性能间整档切换",
                    cfg.fsmEnable,
                ) { vm.update { c -> c.copy(fsmEnable = it) } }
                HorizontalDivider()
                AmToggle(
                    "低温性能", "智能档默认最高只到均衡档(更省电);开启后,机身凉爽或充电时允许冲到性能档——更流畅但更耗电、更热",
                    cfg.coolLeanPerf,
                ) { vm.update { c -> c.copy(coolLeanPerf = it) } }
                HorizontalDivider()
                AmSlider(
                    "学习强度", "越大越快适应你的习惯;太大易受偶发情况影响",
                    cfg.learnRate, 10, 300, unit = "", step = 5,
                ) { vm.update { c -> c.copy(learnRate = it) } }
                HorizontalDivider()
                AmSlider(
                    "智能省电偏向", "把智能模式整体往省电推(0=关,越大越省电、略掉跟手);仅影响智能档",
                    cfg.batteryBias, 0, 300, unit = "", step = 10,
                ) { vm.update { c -> c.copy(batteryBias = it) } }
            }

            // ── 自动省电 / 夜间安静 ──
            AmSection("自动省电 / 夜间安静") {
                AmToggle(
                    "低电自动省电", "电量低于阈值时自动偏省电(充电时不触发)",
                    cfg.autoBatteryEnable,
                ) { vm.update { c -> c.copy(autoBatteryEnable = it) } }
                AnimatedVisibility(visible = cfg.autoBatteryEnable) {
                    Column {
                        HorizontalDivider()
                        AmSlider(
                            "低电触发电量", "电量 ≤ 此值开始省电",
                            cfg.lowBatt, 5, 50, unit = "%",
                        ) { vm.update { c -> c.copy(lowBatt = it) } }
                    }
                }
                HorizontalDivider()
                AmToggle(
                    "夜间安静", "夜间时段熄屏时强制省电,睡觉少耗电",
                    cfg.nightQuietEnable,
                ) { vm.update { c -> c.copy(nightQuietEnable = it) } }
                AnimatedVisibility(visible = cfg.nightQuietEnable) {
                    Column {
                        HorizontalDivider()
                        AmSlider(
                            "夜间开始", "几点进入夜间(24 小时制)",
                            cfg.nightStart, 0, 23, unit = "时",
                        ) { vm.update { c -> c.copy(nightStart = it) } }
                        HorizontalDivider()
                        AmSlider(
                            "夜间结束", "几点退出夜间(可跨零点,如 23→6)",
                            cfg.nightEnd, 0, 23, unit = "时",
                        ) { vm.update { c -> c.copy(nightEnd = it) } }
                    }
                }
            }

            // ── 温度控制 ──
            AmSection("温度控制") {
                AmSlider(
                    "开始降温温度", "超过此温度开始逐步偏省电压频(℃)",
                    cfg.warmTemp, 40, 75, unit = "℃",
                ) { vm.update { c -> c.copy(warmTemp = it) } }
                HorizontalDivider()
                AmSlider(
                    "热保护温度", "达到此温度强制大幅省电护机(℃)",
                    cfg.vetoTemp, 50, 90, unit = "℃",
                ) { vm.update { c -> c.copy(vetoTemp = it) } }
                HorizontalDivider()
                AmSlider(
                    "压频起始温度", "主动调频:多热开始压到散热档(℃,需 > 松手温度)",
                    cfg.fsmSustainedEnter, 45, 80, unit = "℃",
                ) { vm.update { c -> c.copy(fsmSustainedEnter = it) } }
                HorizontalDivider()
                AmSlider(
                    "松手温度", "降到此温度以下才退出散热档(℃)",
                    cfg.fsmSustainedExit, 40, 70, unit = "℃",
                ) { vm.update { c -> c.copy(fsmSustainedExit = it) } }
            }

            // ── 系统优化 (WiFi/网络/内存,所有模式生效) ──
            AmSection("系统优化 (WiFi / 网络 / 内存)") {
                AmToggle(
                    "WiFi / 网络 / 内存调优",
                    "按当前档位调 WiFi 省电、网络发送队列、TCP 缓冲与内存回收倾向(低风险,建议开)",
                    cfg.sysTuneEnable,
                ) { vm.update { c -> c.copy(sysTuneEnable = it) } }
                HorizontalDivider()
                AmToggle(
                    "开机精简",
                    "开机停用 4 个调试/遥测服务并关闭 WiFi 后台扫描省电(系统级,卸载模块前持久生效)",
                    cfg.bootTrimEnable,
                ) { vm.update { c -> c.copy(bootTrimEnable = it) } }
                HorizontalDivider()
                AmToggle(
                    "后台内存回收",
                    "周期性回收常驻后台的重应用内存(只回收不杀进程,绝不动前台)",
                    cfg.bgTrimEnable,
                ) { vm.update { c -> c.copy(bgTrimEnable = it) } }
                AnimatedVisibility(visible = cfg.bgTrimEnable) {
                    Column {
                        HorizontalDivider()
                        AmSlider(
                            "回收周期", "多久回收一次后台内存(秒)",
                            cfg.bgTrimIntervalS, 30, 600, unit = "s",
                        ) { vm.update { c -> c.copy(bgTrimIntervalS = it) } }
                    }
                }
            }

            // ── 触控加速 ──
            AmSection("触控加速") {
                AmToggle(
                    "触控加速", "触摸/滑动时由内核 input boost 瞬间拉高频率更跟手(关闭则交互也省电)",
                    cfg.touchBoostEnable,
                ) { vm.update { c -> c.copy(touchBoostEnable = it) } }
                AnimatedVisibility(visible = cfg.touchBoostEnable) {
                    Column {
                        HorizontalDivider()
                        AmSlider(
                            "提频强度", "触摸时各簇提到最高频的百分比(越高越跟手、越耗电)",
                            cfg.touchBoostLevelPct, 20, 100, unit = "%", step = 5,
                        ) { vm.update { c -> c.copy(touchBoostLevelPct = it) } }
                        HorizontalDivider()
                        AmSlider(
                            "保持时长", "每次触摸后维持提频多久(毫秒)",
                            cfg.touchBoostDurationMs, 300, 3000, unit = "ms", step = 100,
                        ) { vm.update { c -> c.copy(touchBoostDurationMs = it) } }
                    }
                }
            }

            // ── 高级(默认折叠) ──
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("高级阈值", fontWeight = FontWeight.Medium)
                            Text(
                                "学习/热信号细节,一般不用动",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = showAdvanced, onCheckedChange = { showAdvanced = it })
                    }
                    AnimatedVisibility(visible = showAdvanced) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Spacer(Modifier.height(4.dp))
                            HorizontalDivider()
                            AmSlider(
                                "极低电量", "电量 ≤ 此值更激进省电(℃→%)",
                                cfg.critBatt, 3, 20, unit = "%",
                            ) { vm.update { c -> c.copy(critBatt = it) } }
                            HorizontalDivider()
                            AmSlider(
                                "凉爽阈值", "会话峰温低于此值算凉爽,学习偏均衡(℃)",
                                cfg.coolTemp, 40, 70, unit = "℃",
                            ) { vm.update { c -> c.copy(coolTemp = it) } }
                            HorizontalDivider()
                            AmSlider(
                                "热信号温度", "会话峰温 ≥ 此值,学习更偏省电(℃)",
                                cfg.hotTemp, 55, 95, unit = "℃",
                            ) { vm.update { c -> c.copy(hotTemp = it) } }
                            HorizontalDivider()
                            AmSlider(
                                "极热信号温度", "会话峰温 ≥ 此值,学习强烈偏省电(℃)",
                                cfg.vhotTemp, 65, 110, unit = "℃",
                            ) { vm.update { c -> c.copy(vhotTemp = it) } }
                            HorizontalDivider()
                            AmSlider(
                                "高耗电判定", "亮屏掉电率 ≥ 此值算高耗电(%/小时)",
                                cfg.drainHigh, 5, 40, unit = "%/h",
                            ) { vm.update { c -> c.copy(drainHigh = it) } }
                        }
                    }
                }
            }

            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun AmSection(title: String, content: @Composable () -> Unit) {
    SectionTitle(title)
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { content() }
    }
}

@Composable
private fun AmToggle(
    title: String, desc: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AmSlider(
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
            // 紧凑步进胶囊 [ − 值 + ]:小巧、贴合液态玻璃语言,精调 ±step。
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepGlyph("−", v > min) { onChange((v - step).coerceIn(min, max)) }
                    Text(
                        "$v$unit",
                        Modifier.widthIn(min = 32.dp),
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    StepGlyph("+", v < max) { onChange((v + step).coerceIn(min, max)) }
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

// 小号 −/+ 字形,28dp 触点,用 App 自带 pressClickable(液态高亮),不喧宾夺主。
@Composable
private fun StepGlyph(glyph: String, enabled: Boolean, onClick: () -> Unit) {
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
