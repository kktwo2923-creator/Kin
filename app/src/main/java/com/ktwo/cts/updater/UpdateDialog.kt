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
 * 检查更新对话框:显示 App + Kin + 模块 的更新信息,支持下载进度和一键安装。
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

    // Kin(Kinetic 二进制) 热更新状态
    var kinDownloading by remember { mutableStateOf(false) }
    var kinProgress by remember { mutableIntStateOf(0) }
    var kinDone by remember { mutableStateOf(false) }
    var kinError by remember { mutableStateOf("") }
    var kinMsg by remember { mutableStateOf("") }

    // 模块下载状态
    var moduleDownloading by remember { mutableStateOf(false) }
    var moduleProgress by remember { mutableIntStateOf(0) }
    var moduleDone by remember { mutableStateOf(false) }
    var moduleError by remember { mutableStateOf("") }
    var modulePath by remember { mutableStateOf("") }

    val appUpdate = result.appUpdate
    val kinUpdate = result.kinUpdate
    val moduleUpdate = result.moduleUpdate
    val force = (appUpdate?.forceUpdate == true) ||
        (kinUpdate?.forceUpdate == true) ||
        (moduleUpdate?.forceUpdate == true)

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

                // Kin(Kinetic 二进制) 热更新信息 —— 免重启, 直接 cp 覆盖 + 重启守护
                if (kinUpdate != null) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Kin 调度二进制热更新", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "v${kinUpdate.versionName}" +
                                (if (kinUpdate.size > 0) " · ${UpdateChecker.formatSize(kinUpdate.size)}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (kinUpdate.changelog.isNotBlank()) {
                            Text(
                                kinUpdate.changelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "热更新无需重启手机: 下载后用 root 覆盖到模块目录, 自动重启守护进程。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (kinDownloading) {
                            LinearProgressIndicator(
                                progress = { kinProgress / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            Text(
                                "下载中 $kinProgress%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (kinError.isNotBlank()) {
                            Text(kinError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                        if (kinDone) {
                            Text(
                                "✅ $kinMsg",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                            )
                        }
                        if (!kinDownloading) {
                            Button(
                                onClick = {
                                    kinDownloading = true
                                    kinError = ""
                                    kinDone = false
                                    kinMsg = ""
                                    scope.launch {
                                        val (ok, msg) = withContext(Dispatchers.IO) {
                                            UpdateChecker.downloadAndInstallBinary(ctx, kinUpdate) {
                                                kinProgress = it
                                            }
                                        }
                                        kinDownloading = false
                                        if (ok) {
                                            kinDone = true
                                            kinMsg = msg
                                        } else {
                                            kinError = msg
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (kinDone) "重新热更新" else "热更新二进制") }
                        }
                    }
                }

                // 模块更新信息
                if (moduleUpdate != null) {
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("调度模块更新", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(
                            "v${moduleUpdate.versionName}" +
                                (if (moduleUpdate.size > 0) " · ${UpdateChecker.formatSize(moduleUpdate.size)}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (moduleUpdate.changelog.isNotBlank()) {
                            Text(
                                moduleUpdate.changelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "下载后请在 Magisk/KernelSU 中刷入模块并重启手机。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (moduleDownloading) {
                            LinearProgressIndicator(
                                progress = { moduleProgress / 100f },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            Text(
                                "下载中 $moduleProgress%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (moduleError.isNotBlank()) {
                            Text(moduleError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                        if (moduleDone) {
                            Text(
                                "✅ 已下载到: $modulePath",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                            )
                        }
                        if (!moduleDownloading) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        moduleDownloading = true
                                        moduleError = ""
                                        scope.launch {
                                            val path = withContext(Dispatchers.IO) {
                                                UpdateChecker.downloadDaemonZip(ctx, moduleUpdate) {
                                                    moduleProgress = it
                                                }
                                            }
                                            moduleDownloading = false
                                            if (path != null) {
                                                moduleDone = true
                                                modulePath = path
                                            } else {
                                                moduleError = "下载失败,请检查网络"
                                            }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) { Text(if (moduleDone) "重新下载" else "下载模块") }
                                if (moduleDone) {
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

                // 三个都没有更新(理论上不会触发这个对话框)
                if (appUpdate == null && kinUpdate == null && moduleUpdate == null) {
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
        text = { Text("App、Kin 与模块均已是最新版本。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("确定") } },
    )
}
