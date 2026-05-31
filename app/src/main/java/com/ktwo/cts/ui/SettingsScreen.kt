package com.ktwo.cts.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.root.RootShell
import com.ktwo.cts.ui.theme.ThemeMode
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val themeKey by Prefs.themeModeFlow(ctx).collectAsState(initial = "system")
    val wallpaperUri by Prefs.wallpaperUriFlow(ctx).collectAsState(initial = "")
    var isRoot by remember { mutableStateOf(true) }

    // 壁纸选择器: 打开系统相册(照片选择器),把选中图片复制到 App 私有目录,
    // 存绝对路径,避免临时授权重启失效。
    val pickWallpaper = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val dst = java.io.File(ctx.filesDir, "wallpaper.jpg")
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        dst.outputStream().use { ins.copyTo(it) }
                    }
                    dst.absolutePath
                }.getOrNull()
            }
            if (saved != null) Prefs.setWallpaperUri(ctx, saved)
        }
    }

    LaunchedEffect(Unit) {
        isRoot = withContext(Dispatchers.IO) { RootShell.isRooted() }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            LargeTopAppBar(
                title = { Text("设置") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Horizontal),
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Section("外观") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("主题", fontWeight = FontWeight.Medium)
                    val cur = ThemeMode.fromKey(themeKey)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { tm ->
                            FilterChip(
                                selected = tm == cur,
                                onClick = { scope.launch { Prefs.setThemeMode(ctx, tm.key) } },
                                label = { Text(tm.label) },
                                shape = RoundedCornerShape(12.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自定义壁纸", fontWeight = FontWeight.Medium)
                            Text(
                                if (wallpaperUri.isBlank()) "当前: 默认渐变" else "当前: 自定义图片",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (wallpaperUri.isNotBlank()) {
                            TextButton(onClick = { scope.launch { Prefs.setWallpaperUri(ctx, "") } }) {
                                Text("重置")
                            }
                        }
                        FilledTonalButton(onClick = {
                            pickWallpaper.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }) { Text("选择") }
                    }
                }
            }

            Section("权限") {
                PermRow(
                    name = "Root 权限",
                    granted = isRoot,
                    desc = "读写画像表 / 调用 powercfg.sh 必需",
                    icon = Icons.Outlined.Security,
                    onAction = null,
                )
            }

            Section("关于") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("OnePlus V Pro · 接管助手", fontWeight = FontWeight.Medium)
                    Text(
                        "替代 Scene 接管,按应用画像自动切换调度模式\n调用入口: /data/powercfg.sh <mode>",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 给浮动玻璃底栏留出空间
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    SectionTitle(title)
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun PermRow(
    name: String, granted: Boolean, desc: String,
    icon: ImageVector,
    onAction: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon, null,
            modifier = Modifier.size(24.dp),
            tint = if (granted) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (granted) {
            Icon(Icons.Outlined.CheckCircle, null,
                tint = MaterialTheme.colorScheme.primary)
        } else if (onAction != null) {
            TextButton(onClick = onAction) { Text("设置") }
        }
    }
}
