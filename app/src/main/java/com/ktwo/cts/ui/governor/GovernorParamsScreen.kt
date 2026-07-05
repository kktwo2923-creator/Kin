// 调速器参数编辑页(M6): 按 CPU 簇列出当前 governor 的全部 tunable, 点击一行即可 root 写入。
package com.ktwo.cts.ui.governor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ktwo.cts.data.GovernorParams
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.ui.TonalCard
import com.ktwo.cts.ui.theme.MonoNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 正在编辑的 tunable 定位信息。 */
private data class EditTarget(val dir: String, val file: String, val current: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GovernorParamsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var loaded by remember { mutableStateOf(false) }
    var rooted by remember { mutableStateOf(true) }
    var params by remember { mutableStateOf<List<GovernorParams.PolicyParams>>(emptyList()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    // 进入页面读一次; 写入后 refreshKey 自增重读(governor 参数不会自己变, 无需轮询)。
    LaunchedEffect(refreshKey) {
        val (r, list) = withContext(Dispatchers.IO) {
            val ok = runCatching { RootShell.isRooted() }.getOrDefault(false)
            val l = if (ok) runCatching { GovernorParams.readAll() }.getOrDefault(emptyList())
                    else emptyList()
            ok to l
        }
        rooted = r; params = list; loaded = true
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("调速器参数") },
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
        when {
            !loaded -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            !rooted -> HintCard(
                "需要 Root",
                "调速器参数编辑需要 Root 权限枚举与写入 governor tunable 节点。",
                Modifier.padding(inner),
            )
            params.isEmpty() -> HintCard(
                "未读取到调速器信息",
                "没有发现 cpufreq policy 节点, 或读取失败。",
                Modifier.padding(inner),
            )
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "修改会即时生效, 重启后失效(不做持久化)。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                items(count = params.size, key = { params[it].policy }) { i ->
                    PolicyCard(params[i]) { t -> editing = t }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // ── tunable 编辑弹窗 ──
    val target = editing
    if (target != null) {
        TunableEditDialog(
            target = target,
            onDismiss = { editing = null },
            onConfirm = { value ->
                editing = null
                scope.launch {
                    val newVal = withContext(Dispatchers.IO) {
                        runCatching { GovernorParams.write(target.dir, target.file, value) }.getOrNull()
                    }
                    refreshKey++
                    snackbar.showSnackbar(
                        if (newVal != null) "${target.file} 已写入, 当前值 $newVal"
                        else "写入失败, 内核可能拒绝了该值"
                    )
                }
            },
        )
    }
}

// 一个 policy 一张卡: 标题「CPU簇 policyN · governor」+ tunable 列表。
@Composable
private fun PolicyCard(p: GovernorParams.PolicyParams, onEdit: (EditTarget) -> Unit) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 16.dp)) {
            Text(
                "CPU簇 policy${p.policy} · ${p.governor.ifEmpty { "--" }}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(6.dp))
            if (p.tunables.isEmpty()) {
                Text(
                    "该调速器没有可调参数, 或参数目录不可读。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            } else {
                p.tunables.forEach { t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(EditTarget(p.dir, t.name, t.value)) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            t.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            t.value.ifEmpty { "--" },
                            style = MonoNumber.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.End,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TunableEditDialog(
    target: EditTarget,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(target) { mutableStateOf(target.current) }
    // 与 GovernorParams.write 同一套字符白名单, 提前在 UI 拦住非法字符
    val valid = text.trim().isNotEmpty() && text.trim().all {
        it.isLetterOrDigit() || it == ' ' || it == ':' || it == '.' || it == '-' || it == '_'
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(target.file) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("新值") },
                    isError = text.isNotEmpty() && !valid,
                    supportingText = { Text("当前 ${target.current.ifEmpty { "--" }} · 写入后立即生效") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = valid) { Text("写入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun HintCard(title: String, desc: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        TonalCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
