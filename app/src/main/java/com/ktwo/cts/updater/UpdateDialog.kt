package com.ktwo.cts.updater

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 检查更新对话框:显示 App + daemon 的更新信息,支持下载进度和一键安装。
 *
 * @param result 版本检查结果(来自 UpdateChecker.check())
 * @param onDismiss 关闭对话框(force_update=true 时禁用)
 */
@Composable
fun UpdateDialog(
    result: UpdateChecker.CheckResult,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // App 下载状态
    var appDownloading by remember { mutableStateOf(false) }
    var appProgress by remember { mutableIntStateOf(0) }
    var appDone by remember { mutableStateOf(false) }
    var appError by remember { mutableStateOf("") }

    // Daemon 下载状态
    var daemonDownloading by remember { mutableStateOf(false) }
    var daemonProgress by remember { mutableIntStateOf(0) }
    var daemonDone by remember { mutableStateOf(false) }
    var daemonError by remember { mutableStateOf("") }
    var daemonPath by remember { mutableStateOf("") }

    val appUpdate = result.appUpdate
    val daemonUpdate = result.daemonUpdate
    val force = (appUpdate?.forceUpdate == true) || (daemonUpdate?.forceUpdate == true)

    AlertDialog(
        onDismissRequest = { if (!force) onDismiss() },
        title = {
            Text(
                if (force) "⚠️ 必须更新" else "发现新版本",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // App 更新信息
                if (appUpdate != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("App 更新", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "v${appUpdate.versionName}" +
                                (if (appUpdate.size > 0) " · ${UpdateChecker.formatSize(appUpdate.size)}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (appUpdate.changelog.isNotBlank()) {
                            Text(
                                appUpdate.changelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 下载进度条
                        if (appDownloading) {
                            LinearProgressIndicator(
                                progress = { appProgress / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            Text(
                                "下载中 $appProgress%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (appError.isNotBlank()) {
                            Text(appError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                        if (appDone) {
                            Text(
                                "✅ 已下载,正在调起安装器…",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                            )
                        }
                        // 下载按钮
                        if (!appDownloading && !appDone) {
                            Button(
                                onClick = {
                                    appDownloading = true
                                    appError = ""
                                    UpdateChecker.downloadAndInstallApk(
                                        ctx, appUpdate,
                                        onProgress = { appProgress = it },
                                        onDone = { ok, err ->
                                            appDownloading = false
                                            if (ok) appDone = true
                                            else appError = err
                                        },
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("下载并安装 App") }
                        }
                    }
                }

                // Daemon 更新信息
                if (daemonUpdate != null) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("调度模块更新", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "v${daemonUpdate.versionName}" +
                                (if (daemonUpdate.size > 0) " · ${UpdateChecker.formatSize(daemonUpdate.size)}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (daemonUpdate.changelog.isNotBlank()) {
                            Text(
                                daemonUpdate.changelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "下载后请在 Magisk/KernelSU 中刷入模块并重启手机。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (daemonDownloading) {
                            LinearProgressIndicator(
                                progress = { daemonProgress / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            Text(
                                "下载中 $daemonProgress%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (daemonError.isNotBlank()) {
                            Text(daemonError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                        if (daemonDone) {
                            Text(
                                "✅ 已下载到: $daemonPath",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                            )
                        }
                        if (!daemonDownloading) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        daemonDownloading = true
                                        daemonError = ""
                                        scope.launch {
                                            val path = withContext(Dispatchers.IO) {
                                                UpdateChecker.downloadDaemonZip(ctx, daemonUpdate) {
                                                    daemonProgress = it
                                                }
                                            }
                                            daemonDownloading = false
                                            if (path != null) {
                                                daemonDone = true
                                                daemonPath = path
                                            } else {
                                                daemonError = "下载失败,请检查网络"
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(if (daemonDone) "重新下载" else "下载模块") }
                                if (daemonDone) {
                                    OutlinedButton(
                                        onClick = {
                                            UpdateChecker.openModuleManager(ctx)
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("打开 Magisk") }
                                }
                            }
                        }
                    }
                }

                // 两个都没有更新(理论上不会触发这个对话框)
                if (appUpdate == null && daemonUpdate == null) {
                    Text("当前已是最新版本", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            if (!force) {
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        },
    )
}

/** 无更新时的提示 (用户主动点"检查更新"时用) */
@Composable
fun NoUpdateDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已是最新", fontWeight = FontWeight.Bold) },
        text = { Text("App 和调度模块均已是最新版本。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } },
    )
}
