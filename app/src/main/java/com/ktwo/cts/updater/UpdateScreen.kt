package com.ktwo.cts.updater

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ktwo.cts.BuildConfig
import com.ktwo.cts.ui.TonalCard
import com.ktwo.cts.updater.UpdateChecker.LocalVersions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 更新页面, 对齐 AStop `UpdateScreen`(t4/k.java):
 * Scaffold + TopAppBar(标题"检测更新" + 返回 + 刷新) + 滚动内容
 *   - HeroCard: 概览(图标 + 标题 + 副标题 + 上次检查时间)
 *   - 错误卡(若检查失败)
 *   - 三张 ComponentUpdateCard: APP / Kinetic / 模块
 *   - WaitingRebootCard: 模块下载完, 引导去 Magisk/KSU 刷入并重启
 *
 * 进入页面自动检查一次; 点刷新手动重查。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { UpdateController(scope) }

    // 进入页面自动检查一次
    LaunchedEffect(Unit) { controller.checkUpdates() }

    // 异步读三通道本地版本号(供卡片显示"当前版本")
    val local by produceState(
        initialValue = LocalVersions(0, 0, 0),
        producer = { value = UpdateChecker.readLocalVersions() },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("检测更新", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { controller.checkUpdates() },
                        enabled = !controller.state.checking,
                    ) {
                        if (controller.state.checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
        containerColor = Color.Transparent,
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val s = controller.state

            // —— HeroCard ——
            HeroCard(
                checking = s.checking,
                lastCheckedTime = s.lastCheckedTime,
                hasUpdate = s.hasUpdate,
            )

            // —— 错误卡 ——
            if (s.error.isNotBlank()) {
                ErrorCard(error = s.error, onRetry = { controller.checkUpdates() })
            }

            // —— 三通道 ComponentUpdateCard ——
            ComponentUpdateCard(
                title = "App 应用",
                icon = Icons.Outlined.Android,
                info = s.appInfo,
                state = s.app,
                localVersion = "v${BuildConfig.VERSION_NAME} (${local.appCode})",
                hint = "下载 APK 后会自动调起系统安装器, 需开启\"允许安装未知来源\"。",
                downloadLabel = "下载并安装",
                onDownload = { s.appInfo?.let { controller.downloadApp(ctx, it) } },
            )

            ComponentUpdateCard(
                title = "Kinetic",
                icon = Icons.Outlined.Memory,
                info = s.kinInfo,
                state = s.binary,
                localVersion = "v${local.binaryCode}",
                hint = "热更新无需重启手机: 下载后用 root 覆盖到模块目录, 自动重启守护进程。",
                downloadLabel = "热更新二进制",
                onDownload = { s.kinInfo?.let { controller.downloadBinary(ctx, it) } },
            )

            ComponentUpdateCard(
                title = "调度模块",
                icon = Icons.Outlined.Extension,
                info = s.moduleInfo,
                state = s.module,
                localVersion = "v${local.moduleCode}",
                hint = "下载后请在 Magisk/KernelSU 中刷入模块并重启手机。",
                downloadLabel = "下载模块",
                onDownload = { s.moduleInfo?.let { controller.downloadModule(ctx, it) } },
                onSecondary = { controller.openModuleManager(ctx) },
                secondaryLabel = "打开 Magisk",
            )

            // —— WaitingReboot 引导卡(模块已下载, 待刷入重启) ——
            if (s.module.done && s.module.outputPath.isNotBlank()) {
                WaitingRebootCard(
                    path = s.module.outputPath,
                    onOpenMagisk = { controller.openModuleManager(ctx) },
                )
            }

            Spacer(Modifier.size(96.dp))
        }
    }
}

/** 概览卡: 圆形图标 + 标题 + 副标题 + 上次检查时间。 */
@Composable
private fun HeroCard(
    checking: Boolean,
    lastCheckedTime: Long,
    hasUpdate: Boolean,
) {
    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        Icons.Outlined.SystemUpdate, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (checking) "正在检查更新…" else if (hasUpdate) "发现新版本" else "已是最新",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    "App / Kinetic / 模块 三通道独立检测与更新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                if (lastCheckedTime > 0) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        "上次检查: ${formatTime(lastCheckedTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

/** 错误卡: 红色容器 + 错误文案 + 重试按钮。 */
@Composable
private fun ErrorCard(error: String, onRetry: () -> Unit) {
    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "检查更新失败",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/** 模块下载完成后的引导卡: 提示去 Magisk/KSU 刷入并重启。 */
@Composable
private fun WaitingRebootCard(path: String, onOpenMagisk: () -> Unit) {
    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.RestartAlt, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "等待刷入并重启",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                "模块已下载到:\n$path\n请在 Magisk/KernelSU 中刷入该 zip 并重启手机完成更新。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            OutlinedButton(onClick = onOpenMagisk, modifier = Modifier.fillMaxWidth()) {
                Text("打开 Magisk/KernelSU")
            }
        }
    }
}

private fun formatTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3600_000} 小时前"
        else -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}
