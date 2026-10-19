package com.ktwo.cts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.updater.BetaReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var rooted by remember { mutableStateOf<Boolean?>(null) }
    var module by remember { mutableStateOf<Boolean?>(null) }
    var report by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    fun check() = scope.launch {
        val result = withContext(Dispatchers.IO) { RootShell.isRooted() to RootShell.moduleInstalled() }
        rooted = result.first; module = result.second
    }
    LaunchedEffect(Unit) { check() }
    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(
            title = { Text("检查 Kin") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") } },
        )
    }) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("这里会检查 Kin 是否具备正常工作的条件。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DiagnosticCard("Root 权限", rooted, "调用模块调度策略")
            DiagnosticCard("Kin 模块", module, "提供后台调度服务")
            Button(onClick = { check() }, modifier = Modifier.fillMaxWidth()) { Text("重新检查") }
            HorizontalDivider()
            Text("真机采样", fontWeight = FontWeight.SemiBold)
            Text("生成脱敏的只读设备能力报告后，可手动上传给开发者。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                busy = true; message = ""
                scope.launch { report = withContext(Dispatchers.IO) { RootShell.runDeviceValidation() }; busy = false }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "读取中…" else "运行/读取采样") }
            if (report.isNotBlank()) {
                Text(report, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = {
                    busy = true
                    scope.launch { val result = BetaReporter.uploadValidationReport(); message = result.message; busy = false }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "上传中…" else "上传采样数据") }
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun DiagnosticCard(title: String, ok: Boolean?, description: String) {
    val icon = when (ok) { true -> Icons.Outlined.CheckCircle; false -> Icons.Outlined.ErrorOutline; null -> Icons.Outlined.Info }
    val tint = when (ok) { true -> MaterialTheme.colorScheme.primary; false -> MaterialTheme.colorScheme.error; null -> MaterialTheme.colorScheme.onSurfaceVariant }
    TonalCard(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(16.dp)) { Icon(icon, null, tint = tint); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
}
