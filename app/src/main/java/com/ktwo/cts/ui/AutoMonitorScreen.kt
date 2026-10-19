// 智能调度监控面板:仅在开启智能调度(auto)时作为一个 tab 出现,读 daemon 的 auto_status.txt
// 实时展示 FSM 六状态、AI 学习状态(省电倾向/置信度)与温度/电量/GPU/负载/频率上限。
// 健壮性约定: root 读取失败时保留上一份数据并标注"Ns 前", 不清空面板; 状态与存活判定一次往返取回。
package com.ktwo.cts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ktwo.cts.data.Mode
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class AutoStatus(
    val resolved: String = "",
    val fsm: String = "",
    val bucket: Int = -1,
    val daypart: Int = -1,
    val alpha: Int = -1,
    val conf: Int = -1,
    val temp: Int = -1,
    val batt: Int = -1,
    val gpu: Int = -1,
    val load1: Float = -1f,
    val cpuBusy: Int = -1,
    val curMa: Int = -1,
    val nightSleep: Int = -1,
    val nightWake: Int = -1,
    val nightSamples: Int = 0,
    val cpuMaxLittle: Int = 0,
    val cpuMaxBig: Int = 0,
    val charging: Boolean = false,
    val screen: Int = 1,
    val lowbat: Boolean = false,
    val ts: Long = 0,          // daemon 写入时刻(epoch 秒), 用于算数据年龄
    val valid: Boolean = false,
) {
    companion object {
        fun parse(raw: String): AutoStatus {
            if (raw.isBlank()) return AutoStatus()
            val m = HashMap<String, String>()
            raw.lineSequence().forEach {
                val i = it.indexOf('=')
                if (i > 0) m[it.substring(0, i).trim()] = it.substring(i + 1).trim()
            }
            fun gi(k: String, d: Int = -1) = m[k]?.toIntOrNull() ?: d
            fun gf(k: String, d: Float = -1f) = m[k]?.toFloatOrNull() ?: d
            val resolved = m["resolved"] ?: ""
            val fsm = m["fsm"] ?: ""
            return AutoStatus(
                resolved = resolved,
                fsm = fsm,
                bucket = gi("bucket"), daypart = gi("daypart"),
                alpha = gi("alpha"), conf = gi("conf"), temp = gi("temp"), batt = gi("batt"),
                gpu = gi("gpu"), load1 = gf("load1"), cpuBusy = gi("cpu_busy"), curMa = gi("cur_ma"),
                nightSleep = gi("night_sleep"), nightWake = gi("night_wake"), nightSamples = gi("night_samples", 0),
                cpuMaxLittle = gi("cpu_max_little", 0), cpuMaxBig = gi("cpu_max_big", 0),
                charging = gi("charging", 0) == 1, screen = gi("screen", 1),
                lowbat = gi("lowbat", 0) == 1,
                ts = m["timestamp"]?.toLongOrNull() ?: 0L,
                // 只要能解析出任意 key=value 就算有数据: 老模块(2026-08 之前)的状态文件字段集
                // 与新版不同, 卡死要求 fsm/resolved 会让这些用户永远显示"读取失败"。
                // 空内容/读失败时 m 为空 → valid=false, 调用方保留上一份数据。
                valid = m.isNotEmpty(),
            )
        }
    }
}

private val FSM_STATES = listOf(
    "DEEP_IDLE" to "空闲",
    "LIGHT_IDLE" to "低负载",
    "MODERATE" to "中负载",
    "HEAVY" to "重负载",
    "SUSTAINED" to "持续高负载",
    "GAMING" to "游戏",
)

@Composable
fun AutoMonitorScreen() {
    var st by remember { mutableStateOf(AutoStatus()) }
    var alive by remember { mutableStateOf<Boolean?>(null) }   // null = 本轮未取到(shell 整体失败), 保留上次判定
    var readFail by remember { mutableStateOf(0) }             // 连续读取失败次数(用于退避 + 提示)
    var fail by remember { mutableStateOf<RootShell.AutoStatusRead.Fail?>(null) }   // 失败原因
    var nowS by remember { mutableStateOf(0L) }                // 当前时刻(秒), 驱动数据年龄刷新
    LaunchedEffect(Unit) {
        while (true) {
            // 一次 root 往返取「状态 + 存活」, 失败时保留上一份数据, 绝不清空面板。
            val read = withContext(Dispatchers.IO) { RootShell.readAutoStatusSnapshot() }
            val parsed = AutoStatus.parse(read.raw)
            if (parsed.valid) {
                st = parsed
                readFail = 0
                fail = null
            } else {
                readFail++
                fail = read.fail
            }
            read.alive?.let { alive = it }
            nowS = System.currentTimeMillis() / 1000
            // 读失败时退避: 不再继续每分钟 40 次去挤那个正被长命令占用的 su 会话。
            delay(if (readFail == 0) 1500 else 3000)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)   // 标题下移, 不再压系统状态栏
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        SectionTitle("智能调度监控")

        val isFsm = st.fsm.isNotBlank()
        // 数据年龄按 daemon 写入时刻算: 读失败时它自然变大, 守护息屏慢 tick 时也能正确体现。
        val ageS = if (st.ts > 0 && nowS >= st.ts) (nowS - st.ts).toInt() else -1
        val noRoot = fail == RootShell.AutoStatusRead.Fail.NO_ROOT
        val noFile = fail == RootShell.AutoStatusRead.Fail.NO_FILE
        val stateLabel = when {
            alive == false -> "守护未运行"
            isFsm -> FSM_STATES.firstOrNull { it.first == st.fsm }?.second ?: st.fsm
            st.resolved.isNotBlank() -> "整档 · " + Mode.fromKey(st.resolved).label
            noRoot -> "读取失败 · 未获得 ROOT"
            // 刚开启/刚开机时文件还没生成, 前两轮先当"读取中", 避免一闪而过的误报
            noFile -> if (readFail >= 2) "无实时数据 · 智能调度未开启" else "正在读取…"
            readFail > 0 -> "读取失败 · 重试中"
            else -> "正在读取…"
        }
        // 顶部状态主卡
        TonalCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (isFsm) "实时状态(主动调频)" else "当前",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stateLabel,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (alive != false) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                )
                if (alive != false && (st.cpuMaxBig > 0 || st.cpuMaxLittle > 0)) {
                    Text(
                        "频率上限 小核 ${st.cpuMaxLittle / 1000}MHz · 大核 ${st.cpuMaxBig / 1000}MHz",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 数据新鲜度 / 失败原因: 直接告诉用户是 ROOT、还是智能调度没开, 不笼统说"读取失败"。
                val hint = when {
                    !st.valid && noRoot -> "面板需要 ROOT 才能读守护的状态文件, 请到 Root 管理器授权"
                    !st.valid && noFile && readFail >= 2 -> "守护只在「智能调度」模式写状态文件, 开启后约 2 秒出数据"
                    !st.valid && readFail > 0 -> "读不到守护状态文件, 正在重试…"
                    st.valid && readFail > 0 -> "读取中断, 正在重试 · 数据${if (ageS >= 0) " $ageS 秒前" else "为上次读数"}"
                    st.valid && ageS >= 5 -> "上次更新 $ageS 秒前"
                    else -> null
                }
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (st.valid && readFail == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // FSM 六状态
        if (isFsm) {
            SectionTitle("状态")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FSM_STATES.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (key, label) ->
                                val active = key == st.fsm
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (active) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        label,
                                        Modifier.padding(vertical = 10.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                        color = if (active) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // AI 学习状态
        SectionTitle("AI 学习")
        TonalCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (st.daypart in 0..5) {
                    val weekend = st.bucket >= 0 && (st.bucket % 2 == 1)
                    MonRow("学习时段", "${periodName(st.daypart)} · ${if (weekend) "周末" else "工作日"}")
                }
                // alpha 不设界(可负/可超 1000): 有状态就显示原值, 条按 0..1000 区间截取展示。
                MonBar("省电倾向", st.alpha, 1000, "越高越省电", hasValue = st.valid)
                MonBar("学习程度", st.conf, 1000, "学得越久越准 · 当前时段")
                MonRow("睡眠时段", when {
                    st.nightSleep >= 0 && st.nightWake >= 0 && st.nightSamples >= 3 ->
                        "${hhmm(st.nightSleep)}–${hhmm(st.nightWake)} · 已自学"
                    st.nightSamples in 1..2 -> "学习中 (${st.nightSamples}/3 晚)"
                    else -> "学习中 · 暂用固定时段"
                })
            }
        }

        // 实时指标
        SectionTitle("实时指标")
        TonalCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MonRow("温度", if (st.temp >= 0) "${st.temp}℃" else "—")
                MonRow("电量", if (st.batt >= 0) "${st.batt}%${if (st.charging) " (充电)" else ""}" else "—")
                MonRow("GPU 占用", if (st.gpu >= 0) "${st.gpu}%" else "—")
                MonRow("CPU 占用", if (st.cpuBusy >= 0) "${st.cpuBusy}%" else "—")
                MonRow("系统负载", if (st.load1 >= 0) String.format("%.2f", st.load1) else "—")
                MonRow("电流", when {
                    st.curMa < 0 -> "—"
                    st.charging  -> "充电 ${st.curMa} mA"
                    else         -> "放电 ${st.curMa} mA"
                })
                MonRow("屏幕", if (st.screen == 0) "息屏" else "亮屏")
                if (st.lowbat) MonRow("低电省电", "已触发")
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun MonBar(
    title: String, value: Int, max: Int, desc: String,
    hasValue: Boolean = value >= 0,   // 默认按符号判缺失; alpha 这类可负指标由调用方显式给
) {
    val v = value.coerceIn(0, max)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (hasValue) "$value / $max" else "—",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { if (hasValue) v.toFloat() / max else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// 学习时段(原 bucket 的 daypart):一天分 6 段,配合工作日/周末构成学习分组。
private fun hhmm(min: Int): String =
    if (min in 0..1439) "%02d:%02d".format(min / 60, min % 60) else "—"

private fun periodName(daypart: Int): String = when (daypart) {
    0 -> "凌晨"   // 0–5 点
    1 -> "清晨"   // 6–8 点
    2 -> "上午"   // 9–11 点
    3 -> "下午"   // 12–16 点
    4 -> "傍晚"   // 17–20 点
    5 -> "夜间"   // 21–23 点
    else -> "—"
}

@Composable
private fun MonRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}
