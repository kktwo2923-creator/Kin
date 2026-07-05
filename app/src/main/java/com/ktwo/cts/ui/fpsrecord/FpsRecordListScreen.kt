// 帧率记录列表页: 设备概览 + 开始/停止录制 + 历史会话列表(玻璃卡)。
package com.ktwo.cts.ui.fpsrecord

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ktwo.cts.data.fpsrecord.FpsRecordStore
import com.ktwo.cts.data.fpsrecord.FpsSessionMeta
import com.ktwo.cts.service.FpsRecordService
import com.ktwo.cts.ui.SectionTitle
import com.ktwo.cts.ui.TonalCard
import com.ktwo.cts.ui.theme.MonoNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FpsRecordListScreen(
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rec by FpsRecordService.state.collectAsState()

    var metas by remember { mutableStateOf<List<FpsSessionMeta>>(emptyList()) }
    var reloadKey by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<FpsSessionMeta?>(null) }

    // 服务停止(active 翻 false)或删除后刷新列表
    LaunchedEffect(rec.active, reloadKey) {
        metas = withContext(Dispatchers.IO) {
            runCatching { FpsRecordStore.list(ctx) }.getOrDefault(emptyList())
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("帧率记录") },
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
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DeviceOverviewCard() }
            item {
                RecordControlCard(
                    rec = rec,
                    onStart = { FpsRecordService.start(ctx) },
                    onStop = { FpsRecordService.stop(ctx) },
                )
            }
            item { SectionTitle("历史记录") }
            if (metas.isEmpty()) {
                item {
                    TonalCard(modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "暂无帧率记录",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(metas, key = { it.id }) { m ->
                    RecordRow(
                        meta = m,
                        onClick = { onOpenDetail(m.id) },
                        onDelete = { confirmDelete = m },
                    )
                }
            }
            item { Spacer(Modifier.height(96.dp)) }
        }
    }

    val pendingDelete = confirmDelete
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除记录") },
            text = { Text("确定删除「${pendingDelete.appName.ifEmpty { pendingDelete.pkg }}」的这条帧率记录吗？删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch(Dispatchers.IO) {
                        runCatching { FpsRecordStore.delete(ctx, pendingDelete.id) }
                        withContext(Dispatchers.Main) { reloadKey++ }
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 设备概览: 型号 / 安卓版本 / SoC。 */
@Composable
private fun DeviceOverviewCard() {
    val model = runCatching { Build.MODEL }.getOrDefault("").ifBlank { "--" }
    val android = runCatching { "Android ${Build.VERSION.RELEASE}" }.getOrDefault("--")
    val soc = runCatching { Build.SOC_MODEL }.getOrDefault("")
        .let { if (it.isBlank() || it.equals("unknown", ignoreCase = true)) "--" else it }
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("设备概览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                OverviewItem("型号", model, Modifier.weight(1f))
                OverviewItem("安卓版本", android, Modifier.weight(1f))
                OverviewItem("SoC", soc, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OverviewItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
        )
    }
}

/** 录制控制卡: 空闲=开始按钮; 等待=提示+停止; 录制中=实时 fps/时长 + 停止。 */
@Composable
private fun RecordControlCard(
    rec: FpsRecordService.RecState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            when {
                rec.recording -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.FiberManualRecord,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "录制中 · ${rec.appName.ifEmpty { rec.pkg }}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (rec.fps > 0) "${rec.fps}" else "--",
                            style = MaterialTheme.typography.headlineMedium.merge(MonoNumber),
                        )
                        Text(
                            " fps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        Text(
                            fmtElapsed(rec.elapsedSec),
                            style = MaterialTheme.typography.titleMedium.merge(MonoNumber),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        FilledTonalButton(onClick = onStop) { Text("停止录制") }
                    }
                }
                rec.active -> {
                    Text(
                        "等待进入目标应用",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "切换到要录制的应用后自动开始记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onStop) { Text("取消") }
                }
                else -> {
                    Text(
                        "录制目标应用的帧率、帧时间与卡顿, 生成会话报告",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text("开始录制")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "点击后切换到目标应用即自动开始; 切出该应用自动停止并保存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 历史会话行: 应用名 / 时长·平均FPS·平均功耗 / 时间; 右侧删除。 */
@Composable
private fun RecordRow(
    meta: FpsSessionMeta,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    meta.appName.ifEmpty { meta.pkg.ifEmpty { "未知应用" } },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(fmtDuration(meta.durationMs))
                        append(" · 平均 ")
                        append(if (meta.avgFps > 0) "%.1f".format(meta.avgFps) else "--")
                        append(" fps · ")
                        append(if (meta.avgWatt > 0) "%.1f W".format(meta.avgWatt) else "-- W")
                    },
                    style = MaterialTheme.typography.bodySmall.merge(MonoNumber),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    fmtDate(meta.startMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun fmtElapsed(sec: Int): String =
    if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
    else "%02d:%02d".format(sec / 60, sec % 60)

private fun fmtDuration(ms: Long): String {
    val sec = (ms / 1000).toInt()
    return when {
        sec >= 3600 -> "${sec / 3600}时${sec % 3600 / 60}分"
        sec >= 60 -> "${sec / 60}分${sec % 60}秒"
        else -> "${sec}秒"
    }
}

private fun fmtDate(ms: Long): String =
    runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    }.getOrDefault("--")
