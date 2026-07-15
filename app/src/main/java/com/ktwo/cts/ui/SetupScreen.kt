// 首次启动初始化引导: ROOT 授权 → 内核模块检测(未装给下载链接) → 无障碍权限(可选)。
// 全部状态在返回本页(如从浏览器/系统设置回来)时自动重新检测; 完成后置 setup_done 进入主界面。
package com.ktwo.cts.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Accessibility
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.service.AppSwitchService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 内核模块(Magisk/KernelSU)下载地址: 未检测到模块时引导用户去下载刷入。 */
private const val MODULE_DOWNLOAD_URL =
    "https://example.com/123pan/QGwjVv-ixUHh?"

@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // 三项状态: null = 检测中。ROOT 检测本身就会拉起授权弹窗(首次打开即向用户要 ROOT)。
    var rootOk by remember { mutableStateOf<Boolean?>(null) }
    var moduleOk by remember { mutableStateOf<Boolean?>(null) }
    var a11yOk by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun refresh() {
        if (checking) return
        checking = true
        scope.launch {
            // isRooted() 首次调用会触发 su 授权弹窗 = "初始化授权"; 拒绝后再点可重试。
            rootOk = withContext(Dispatchers.IO) { runCatching { RootShell.isRooted() }.getOrDefault(false) }
            moduleOk = withContext(Dispatchers.IO) { runCatching { RootShell.moduleInstalled() }.getOrDefault(false) }
            a11yOk = runCatching { AppSwitchService.isEnabled(ctx) }.getOrDefault(false)
            checking = false
        }
    }

    // 进入即检测(触发 ROOT 授权); 从系统设置/浏览器返回(ON_RESUME)自动复检。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Scaffold { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("欢迎使用", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "先完成三步初始化, 让调度功能完整可用。之后也可以随时在「设置」里调整。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── 第 1 步: ROOT 授权 ──
            SetupStepCard(
                icon = Icons.Outlined.Security,
                title = "ROOT 授权",
                ok = rootOk,
                okText = "已获得 ROOT 权限",
                pendingText = "调频/调度需要 ROOT。弹出授权窗口时请选择「允许」。",
                actionText = "重新请求授权",
                onAction = { refresh() },
            )

            // ── 第 2 步: 内核模块 ──
            SetupStepCard(
                icon = Icons.Outlined.Extension,
                title = "调度模块",
                ok = moduleOk,
                okText = "模块已安装",
                pendingText = "未检测到调度模块。请下载模块包, 在 Magisk/KernelSU 中刷入并重启手机, 然后回到这里。",
                actionText = "下载模块",
                onAction = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(MODULE_DOWNLOAD_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                secondaryActionText = "重新检测",
                onSecondaryAction = { refresh() },
            )

            // ── 第 3 步: 无障碍(可选) ──
            SetupStepCard(
                icon = Icons.Outlined.Accessibility,
                title = "无障碍权限(可选)",
                ok = a11yOk,
                okText = "无障碍服务已开启",
                pendingText = "用于秒级识别前台应用, 应用画像/分类切换更快。不开启也能用(自动回退轮询)。",
                actionText = "去开启",
                onAction = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )

            Spacer(Modifier.weight(1f, fill = false))
            Spacer(Modifier.height(8.dp))

            // ROOT/模块未就绪也允许进入(比如先看看界面), 但明确提示功能受限。
            if (rootOk == false || moduleOk == false) {
                Text(
                    "提示: ROOT 或模块未就绪时, 调频/调度功能不可用, 仅能浏览界面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    if (rootOk == true && moduleOk == true) "完成, 进入应用" else "跳过, 直接进入",
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * 单个初始化步骤卡片: 图标 + 标题 + 状态徽标 / 说明 / 操作按钮。
 * ok = null 检测中(转圈), true 完成(绿勾+完成文案, 隐藏按钮), false 待处理(说明+按钮)。
 */
@Composable
private fun SetupStepCard(
    icon: ImageVector,
    title: String,
    ok: Boolean?,
    okText: String,
    pendingText: String,
    actionText: String,
    onAction: () -> Unit,
    secondaryActionText: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                when (ok) {
                    null -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    )
                    true -> Icon(
                        Icons.Filled.CheckCircle, contentDescription = "已完成",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    false -> Icon(
                        Icons.Outlined.ErrorOutline, contentDescription = "未完成",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                if (ok == true) okText else pendingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (ok == false) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onAction) { Text(actionText) }
                    if (secondaryActionText != null && onSecondaryAction != null) {
                        OutlinedButton(onClick = onSecondaryAction) { Text(secondaryActionText) }
                    }
                }
            }
        }
    }
}
