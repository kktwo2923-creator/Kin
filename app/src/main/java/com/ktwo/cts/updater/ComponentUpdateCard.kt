package com.ktwo.cts.updater

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ktwo.cts.ui.TonalCard
import com.ktwo.cts.updater.UpdateChecker.VersionInfo

/**
 * 单个更新通道卡片, 对齐 AStop `ComponentUpdateCard`(t4/k.java):
 * 图标 + 标题 + 当前版本 + StatusBadge + (有更新时)分隔线 + 新版本/大小 + 更新日志(Markdown 可折叠) +
 * 下载进度 + DownloadProgressButton。
 *
 * @param title          卡片标题(如 "App 应用" / "调度二进制" / "模块")
 * @param icon           leading 图标
 * @param info           服务端返回的版本信息(null = 该通道无更新)
 * @param state          该通道的下载/安装状态
 * @param localVersion   本地当前版本文案(显示在标题下)
 * @param hint           卡片底部说明文案(如 "热更新无需重启手机…")
 * @param downloadLabel  下载按钮文字(如 "下载并安装" / "热更新")
 * @param onDownload     点击下载
 * @param onSecondary    二级按钮(模块"打开 Magisk"); null = 不显示
 * @param secondaryLabel 二级按钮文字
 */
@Composable
fun ComponentUpdateCard(
    title: String,
    icon: ImageVector,
    info: VersionInfo?,
    state: ComponentState,
    localVersion: String,
    hint: String,
    downloadLabel: String,
    onDownload: () -> Unit,
    onSecondary: (() -> Unit)? = null,
    secondaryLabel: String = "",
) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // —— 顶部: 图标 + 标题 + 当前版本 + StatusBadge ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(
                        "当前版本 $localVersion",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(info != null, state)
            }

            // —— 状态文字(下载中/已完成/失败) ——
            val statusText = when {
                state.downloading -> "下载中 ${state.progress}%"
                state.error.isNotBlank() -> state.error
                state.done && state.message.isNotBlank() -> state.message
                info != null -> "有新版本可用"
                else -> ""
            }
            if (statusText.isNotBlank()) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        state.error.isNotBlank() -> MaterialTheme.colorScheme.error
                        state.done -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            // —— 有更新: 详情区(新版本 + 大小 + 更新日志 + 进度 + 按钮) ——
            if (info != null) {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "v${info.versionName}",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (info.size > 0) SizeBadge(info.size)
                    if (info.forceUpdate) {
                        Spacer(Modifier.width(6.dp))
                        ForceBadge()
                    }
                }

                // 更新日志(Markdown, 可折叠)
                if (info.changelog.isNotBlank()) {
                    var expanded by remember { mutableStateOf(false) }
                    Text("更新日志", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    MarkdownText(
                        text = info.changelog,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                    )
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) "收起" else "查看完整更新日志",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                // 下载进度条
                AnimatedVisibility(visible = state.downloading) {
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // 提示文案
                if (hint.isNotBlank()) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 按钮
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onDownload,
                        enabled = !state.downloading,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (state.downloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("${state.progress}%")
                        } else {
                            Icon(
                                if (state.done) Icons.Outlined.Refresh else Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (state.done) "重新下载" else downloadLabel)
                        }
                    }
                    if (onSecondary != null && state.done) {
                        OutlinedButton(
                            onClick = onSecondary,
                            modifier = Modifier.weight(1f),
                        ) { Text(secondaryLabel) }
                    }
                }
            } else {
                // 无更新: 已是最新
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CheckCircle, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "已是最新版本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** 状态徽章: 有更新→"有新版本"; 下载中→"下载中"; 已完成→"已完成"; 否则→"已是最新"。 */
@Composable
private fun StatusBadge(updateAvailable: Boolean, state: ComponentState) {
    val (text, color) = when {
        state.downloading -> "下载中" to MaterialTheme.colorScheme.primary
        state.done -> "已完成" to MaterialTheme.colorScheme.primary
        state.error.isNotBlank() -> "失败" to MaterialTheme.colorScheme.error
        updateAvailable -> "有新版本" to MaterialTheme.colorScheme.primary
        else -> "已是最新" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** 大小徽章(格式化为 KB/MB/GB)。 */
@Composable
private fun SizeBadge(bytes: Long) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            UpdateChecker.formatSize(bytes),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 强制更新徽章。 */
@Composable
private fun ForceBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text("必须更新", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
    }
}
