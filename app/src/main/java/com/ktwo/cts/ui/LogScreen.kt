package com.ktwo.cts.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 日志页 - 读取 /sdcard/Android/CTS 下的 *.log / *.txt。
 *  - 顶部文件选择(芯片) + 刷新/清空
 *  - 等宽字体逐行展示,自动滚到底
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var files by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    suspend fun loadFiles() {
        val fs = withContext(Dispatchers.IO) { RootShell.listLogFiles() }
        files = fs
        if (selected == null || selected !in fs) selected = fs.firstOrNull()
    }

    suspend fun loadLines() {
        val path = selected ?: run { lines = emptyList(); return }
        loading = true
        lines = withContext(Dispatchers.IO) { RootShell.readLogTail(path) }
        loading = false
    }

    LaunchedEffect(Unit) { loadFiles() }
    LaunchedEffect(selected) { loadLines() }
    // 行变化后滚到底部
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = { Text("日志") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                actions = {
                    IconButton(onClick = {
                        scope.launch { loadFiles(); loadLines() }
                    }) { Icon(Icons.Outlined.Refresh, "刷新") }
                    IconButton(onClick = {
                        val path = selected ?: return@IconButton
                        scope.launch {
                            withContext(Dispatchers.IO) { RootShell.clearLog(path) }
                            loadLines()
                        }
                    }) { Icon(Icons.Outlined.DeleteOutline, "清空") }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Horizontal),
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 文件选择芯片
            if (files.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    files.forEach { f ->
                        val name = f.substringAfterLast('/')
                        FilterChip(
                            selected = f == selected,
                            onClick = { selected = f },
                            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), Alignment.TopCenter) {
                    CircularProgressIndicator()
                }
                files.isEmpty() -> EmptyHint(
                    "未找到日志文件\n目录: ${RootShell.CTS_DIR}\n(需 Root 读取;模块运行后会生成 *.log)"
                )
                lines.isEmpty() -> EmptyHint("该文件为空")
                else -> TonalCard(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        items(lines) { line ->
                            Text(
                                text = line.ifEmpty { " " },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = colorForLine(line),
                                softWrap = false,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                            )
                        }
                        item { Spacer(Modifier.height(96.dp)) }
                    }
                }
            }
        }
    }
}

/** 简单的日志着色: 错误红 / 警告橙 / 其余默认 */
@Composable
private fun colorForLine(line: String): Color {
    val l = line.lowercase()
    return when {
        "error" in l || "fail" in l || " e " in l || "异常" in l ->
            MaterialTheme.colorScheme.error
        "warn" in l || " w " in l ->
            Color(0xFFE08A00)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
