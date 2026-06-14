package com.ktwo.cts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ktwo.cts.license.License
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 授权门:已激活直接进入 App,否则显示激活页。 */
@Composable
fun LicenseGate(content: @Composable () -> Unit) {
    var activated by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { activated = withContext(Dispatchers.IO) { License.isActivated() } }
    when (activated) {
        true -> content()
        false -> LicenseScreen(onActivated = { activated = true })
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun LicenseScreen(onActivated: () -> Unit) {
    var key by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
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
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注(选填)") },
                placeholder = { Text("如你的联系方式,便于卖家核对") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    msg = ""; busy = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { License.activate(key, note) }
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
