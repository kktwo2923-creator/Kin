package com.ktwo.cts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ktwo.cts.license.Integrity
import com.ktwo.cts.license.License
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 授权门:先做重签名/重打包检测,再判授权状态(伪造/吊销→红色锁定页;未激活→激活页)。 */
@Composable
fun LicenseGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var repacked by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<License.State?>(null) }
    LaunchedEffect(Unit) {
        // 1) 重签名/重打包检测(未配置签名指纹时 fail-open,不影响 debug/未配置构建)
        repacked = withContext(Dispatchers.IO) { !Integrity.verify(ctx) }
        // 2) 授权状态:整段兜底,任何异常(Root 未就绪/IO/网络等)都回落到"未激活"而非闪退
        state = withContext(Dispatchers.IO) {
            runCatching {
                when (License.state()) {
                    License.State.NOT_ACTIVATED -> License.State.NOT_ACTIVATED
                    License.State.TAMPERED -> {
                        // 伪造授权:上报后台(供作者人工查看/拉黑),best-effort
                        License.reportTamper("license.json 令牌伪造或格式非法(疑似离线绕过)")
                        License.State.TAMPERED
                    }
                    License.State.VALID -> {
                        // 看似合法 → 联网就立刻再校验一次(被吊销则锁定);离线则保持放行(不惩罚离线)
                        if (License.isOnline(ctx) && License.revalidate() == License.Reval.REVOKED)
                            License.State.TAMPERED
                        else License.State.VALID
                    }
                }
            }.getOrDefault(License.State.NOT_ACTIVATED)
        }
    }
    when {
        repacked -> RepackScreen()
        state == License.State.VALID -> content()
        state == License.State.NOT_ACTIVATED -> LicenseScreen(onActivated = { state = License.State.VALID })
        state == License.State.TAMPERED -> TamperScreen(onActivated = { state = License.State.VALID })
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

/** 红色破解锁定页:检测到伪造授权,功能锁死,必须输入正版卡密恢复。 */
@Composable
private fun TamperScreen(onActivated: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val red = androidx.compose.ui.graphics.Color(0xFFB3261E)

    Scaffold(containerColor = androidx.compose.ui.graphics.Color(0xFF2A0E0C)) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(56.dp))
            Text("⚠ 授权未通过验证", style = MaterialTheme.typography.headlineSmall, color = red)
            Spacer(Modifier.height(12.dp))
            Text(
                "本机授权无效(伪造/破解,或卡密已被吊销/封禁),所有功能已锁定。\n" +
                    "请输入有效的正版卡密以恢复使用;否则本软件将无法运行。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = androidx.compose.ui.graphics.Color(0xFFF2B8B5)
            )
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("正版卡密") },
                placeholder = { Text("VPRO-XXXX-XXXX-XXXX-XXXX") },
                singleLine = true,
                enabled = !busy,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = red,
                    focusedLabelColor = red,
                    cursorColor = red
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    msg = ""; busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { License.activate(key) }
                        busy = false
                        if (r.ok) onActivated() else msg = r.message
                    }
                },
                enabled = !busy && key.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = red),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("验证并恢复")
            }
            if (msg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(msg, color = red, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "购买正版:${License.BASE_URL}/buy.html",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = androidx.compose.ui.graphics.Color(0xFFF2B8B5)
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 检测到重签名/重打包时显示,拒绝进入主界面(必须从官方渠道重新安装)。 */
@Composable
private fun RepackScreen() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "⚠️ 安装包校验未通过",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "本应用签名与官方发布不一致,可能被重新打包或篡改。\n请从官方渠道重新下载安装正版。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LicenseScreen(onActivated: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Scaffold { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            Text("OnePlus V Pro", style = MaterialTheme.typography.headlineSmall)
            Text(
                "授权激活",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "输入卡密激活本机。卡密首次激活后绑定本设备,不可换机使用。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("卡密") },
                placeholder = { Text("VPRO-XXXX-XXXX-XXXX-XXXX") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    msg = ""; busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { License.activate(key) }
                        busy = false
                        if (r.ok) onActivated() else msg = r.message
                    }
                },
                enabled = !busy && key.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Text("激活")
            }
            if (msg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(28.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("还没有卡密?", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "购买页:${License.BASE_URL}/buy.html\n扫码付款 → 在购买页填订单号 → 取得卡密。",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    "⚠️ 服务器在境外,购买与激活需要联网。若提示连不上,请先开启加速器/梯子;" +
                        "激活成功后即可关闭——日常使用完全离线,无需联网。",
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
