// 付费解锁页(仿 MetricPro):介绍解锁的额外功能 + 在线支付 + 卡密/订单号输入 + 激活。
// 解锁范围:智能模式 + 智能设置里的「系统优化」。真值来源 License.state()==VALID。
package com.ktwo.cts.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ktwo.cts.R
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.license.License
import com.ktwo.cts.license.Pro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProUnlockScreen(onBack: () -> Unit, onUnlocked: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val unlocked by Prefs.proUnlockedFlow(ctx).collectAsState(initial = false)

    var cardKey by remember { mutableStateOf("") }
    var orderNo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var msgOk by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    // 「曾购买过会员」——诚信自助:点激活直接本地解锁, 不联网/不验证(同 Metric)。
    fun doHonorUnlock() {
        scope.launch {
            Pro.markUnlocked(ctx)
            msgOk = true; msg = "已解锁, 感谢支持!"
            onUnlocked()
        }
    }

    // 在线支付(对齐 Metric 0.4.3): 下单 → 打开支付宝/微信收银台 → 轮询查询到账 → 自动激活。
    fun doOnlinePay(payType: String) {
        if (busy) return
        msg = ""; busy = true
        scope.launch {
            val order = withContext(Dispatchers.IO) { License.createPayOrder(payType) }
            if (order == null) {
                busy = false; msgOk = false
                msg = "下单失败, 请检查网络(需加速器/梯子)或稍后重试"
                return@launch
            }
            // 打开收银台(外部浏览器/支付宝)
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(order.payUrl))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            msgOk = true; msg = "已打开收银台, 付款后自动到账(约 5 分钟内轮询)…"
            // 轮询查询: 每 3s 一次, 最多 ~5 分钟
            val r = withContext(Dispatchers.IO) {
                var res = License.Result(false, "PENDING")
                repeat(100) {
                    res = License.queryPayAndActivate(order.outTradeNo)
                    if (res.ok || res.message != "PENDING") return@withContext res
                    kotlinx.coroutines.delay(3000)
                }
                res
            }
            if (r.ok) Pro.markUnlocked(ctx)
            busy = false
            msgOk = r.ok
            msg = when {
                r.ok -> "解锁成功, 感谢支持!"
                r.message == "PENDING" -> "还没查到付款。付了但没到账? 用账单里的订单号在下方'订单号'激活。"
                else -> r.message
            }
            if (r.ok) onUnlocked()
        }
    }

    // 激活:卡密优先, 否则用订单号自动取卡再激活。成功即置解锁缓存并回调。
    fun doActivate() {
        if (busy) return
        val key = cardKey.trim()
        val no = orderNo.trim()
        if (key.isEmpty() && no.isEmpty()) {
            msgOk = false; msg = "请先填写卡密, 或填写付款订单号"
            return
        }
        msg = ""; busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                if (key.isNotEmpty()) License.activate(key) else License.redeemByOrder(no)
            }
            if (r.ok) Pro.markUnlocked(ctx)
            busy = false
            msgOk = r.ok
            msg = if (r.ok) "解锁成功, 感谢支持!" else r.message
            if (r.ok) onUnlocked()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("智能版 · Pro") },
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 顶部标题 ──
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(8.dp))
                Icon(
                    Icons.Outlined.AutoAwesome, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text("解锁智能版", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (unlocked) "已解锁 · 感谢购买" else "输入卡密激活, 解锁全部智能功能",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (unlocked) {
                TonalCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("智能版已解锁", fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(48.dp))
                return@Column
            }

            // ── 解锁后可用的额外功能 ──
            SectionTitle("解锁后可用")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ProFeature(Icons.Outlined.Speed, "智能模式", "守护自学习使用习惯, 在省电/均衡/性能间自动调度")
                    HorizontalDivider()
                    ProFeature(Icons.Outlined.Tune, "智能高级调节", "主动调频、温控阈值、学习强度、夜间安静等全部旋钮")
                    HorizontalDivider()
                    ProFeature(Icons.Outlined.Memory, "系统优化", "WiFi/网络/内存调优、开机精简、后台内存回收")
                }
            }

            // ── 在线支付(支付宝/微信, 下单跳收银台, 付款后自动到账激活) ──
            SectionTitle("在线支付")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("直接付款, 到账后自动解锁, 无需手填订单号。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { doOnlinePay("alipay") },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("支付宝支付") }
                        OutlinedButton(
                            onClick = { doOnlinePay("wxpay") },
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) { Text("微信支付") }
                    }
                }
            }

            // ── 卡密 / 订单号 ──
            SectionTitle("激活")
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = cardKey,
                        onValueChange = { cardKey = it.trim() },
                        label = { Text("卡密(有卡密填这里)") },
                        placeholder = { Text("VPRO-XXXX-XXXX-XXXX-XXXX") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = orderNo,
                        onValueChange = { orderNo = it.trim() },
                        label = { Text("付款订单号(付了没自动到账时填这里)") },
                        placeholder = { Text("支付宝/微信账单里的订单号") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { doActivate() },
                        enabled = !busy && (cardKey.isNotBlank() || orderNo.isNotBlank()),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Text("激活")
                    }
                    if (msg.isNotEmpty()) {
                        Text(
                            msg,
                            color = if (msgOk) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ── 底部小标题:曾购买过会员 → 弹窗 → 激活 ──
            Text(
                "曾购买过会员",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !busy) { showConfirm = true }
                    .padding(vertical = 8.dp),
            )

            Spacer(Modifier.height(72.dp))
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("已购买本产品") },
            text = {
                Text("确认你已购买本产品?\n点「激活」即可直接解锁, 无需卡密、无需联网验证。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    doHonorUnlock()
                }) { Text("激活") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProFeature(icon: ImageVector, title: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
