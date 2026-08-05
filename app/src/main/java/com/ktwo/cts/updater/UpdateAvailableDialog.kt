package com.ktwo.cts.updater

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ktwo.cts.updater.UpdateChecker.CheckResult

/**
 * 启动时检测到更新后的提示对话框(轻量提示, 不含下载按钮)。
 *
 * 与 AStop 的 `UpdateCoordinator.startupPrompt` 对齐: 启动检测到更新 → 弹轻量提示 →
 * 用户点"去更新"导航到 [UpdateScreen] 独立页面查看详情并下载; 点"稍后"关闭。
 *
 * 强制更新(任意通道 forceUpdate=true)时只允许"去更新", 禁用"稍后"。
 *
 * @param result    启动检查结果(含三通道 VersionInfo)
 * @param onGoUpdate 导航到 UpdateScreen
 * @param onDismiss  关闭(强制更新时不调用)
 */
@Composable
fun UpdateAvailableDialog(
    result: CheckResult,
    onGoUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val force = (result.appUpdate?.forceUpdate == true) ||
        (result.binaryUpdate?.forceUpdate == true) ||
        (result.daemonUpdate?.forceUpdate == true)

    val channels = buildList {
        result.appUpdate?.let { add("App v${it.versionName}") }
        result.binaryUpdate?.let { add("调度二进制 v${it.versionName}") }
        result.daemonUpdate?.let { add("模块 v${it.versionName}") }
    }

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
            ) {
                Text(
                    if (force) "本次更新为强制更新, 请尽快完成后再使用。"
                    else "检测到以下组件有新版本, 可以前往更新页面查看详情并下载。",
                )
                Spacer(Modifier.height(10.dp))
                channels.forEach { ch ->
                    Text(
                        "· $ch",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "在更新页面可分别更新 App / 调度二进制 / 模块三个通道。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onGoUpdate) { Text("去更新") }
        },
        dismissButton = {
            if (!force) {
                TextButton(onClick = onDismiss) { Text("稍后") }
            }
        },
    )
}
