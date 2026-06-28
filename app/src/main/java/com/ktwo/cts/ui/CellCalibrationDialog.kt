package com.ktwo.cts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.SysfsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 上报电压 >= 此阈值视为「整包(2S)电压」, 自动建议双电芯。与 computeWatt 的 <6000mV 判定一致。 */
private const val DUAL_PACK_VOLT_THRESHOLD = 6.0

/**
 * 一次性「电芯矫正」对话框。读设备上报的原始电池电压自动建议单/双电芯, 并解释其对功率读数的影响。
 * 用户二选一后: 写电芯数 + 置矫正完成(commitCellCalibration), 再 onDone()。
 *
 * @param cancelable true=允许点外部/返回键取消(设置里主动重新矫正); false=强制选择(记录页首开门禁)。
 */
@Composable
fun CellCalibrationDialog(cancelable: Boolean = false, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var rawVolt by remember { mutableStateOf<Double?>(null) }   // null = 读取中
    LaunchedEffect(Unit) {
        rawVolt = withContext(Dispatchers.IO) {
            runCatching { SysfsReader.rawVoltageV() }.getOrDefault(0.0)
        }
    }

    val v = rawVolt ?: 0.0
    val hasReading = v > 0.1
    val suggestDual = hasReading && v >= DUAL_PACK_VOLT_THRESHOLD

    fun choose(cells: Int) = scope.launch {
        Prefs.commitCellCalibration(ctx, cells)
        onDone()
    }

    Dialog(
        onDismissRequest = { if (cancelable) onDone() },   // 首开强制选择: 点外部不关闭
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = cancelable,
            dismissOnClickOutside = cancelable,
        ),
    ) {
        TonalCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("矫正电池电芯", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                Text(
                    "为了让功率(W)读数准确, 请先确认本机电池是单电芯还是双电芯串联。\n" +
                    "· 单电芯:系统上报的就是单节电压, 功率直接按读数计算。\n" +
                    "· 双电芯(2S 串联):部分机型只上报单芯电压, 实际整机功率约为两倍, 需要校正。\n" +
                    "选错只会让功率偏差一倍, 不影响其它功能, 之后也能在「设置」里改。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TonalCard(modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("当前上报电压", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                when {
                                    rawVolt == null -> "读取中…"
                                    !hasReading -> "读不到"
                                    else -> "%.3f V".format(v)
                                },
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Text(
                            when {
                                rawVolt == null -> "正在读取电池电压…"
                                !hasReading -> "无法读取电压, 默认建议「单电芯」, 可手动选择。"
                                suggestDual -> "电压约 %.1fV, 像是 2S 整包电压 → 建议选「双电芯」。".format(v)
                                else -> "电压约 %.1fV, 像是单节电压 → 建议选「单电芯」。".format(v)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val singleBtn: @Composable RowScope.() -> Unit = {
                        if (suggestDual) {
                            OutlinedButton(onClick = { choose(1) }, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)) { Text("单电芯") }
                        } else {
                            Button(onClick = { choose(1) }, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)) { Text("单电芯") }
                        }
                    }
                    val dualBtn: @Composable RowScope.() -> Unit = {
                        if (suggestDual) {
                            Button(onClick = { choose(2) }, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)) { Text("双电芯 (建议)") }
                        } else {
                            OutlinedButton(onClick = { choose(2) }, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)) { Text("双电芯") }
                        }
                    }
                    if (suggestDual) { dualBtn(); singleBtn() } else { singleBtn(); dualBtn() }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("选好后不再提示。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (cancelable) TextButton(onClick = onDone) { Text("取消") }
                }
            }
        }
    }
}
