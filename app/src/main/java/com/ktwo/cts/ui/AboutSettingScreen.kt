// 关于 tab 下的「设置」子页 — 对齐 OShin about_setting.kt 模式:
// 独立全屏页, 收 App 级偏好(外观/检测更新入口/文档)。功能行为开关在「功能」tab。
package com.ktwo.cts.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.SystemUpdate
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
import com.ktwo.cts.ui.theme.ThemeMode
import com.ktwo.cts.ui.theme.UiStyle
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AboutSettingScreen(
    onBack: () -> Unit,
    onOpenUpdate: () -> Unit = {},
    onOpenDocs: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val themeKey by Prefs.themeModeFlow(ctx).collectAsState(initial = "system")
    val uiStyleKey by Prefs.uiStyleFlow(ctx).collectAsState(initial = "glass")
    var showStyleDialog by remember { mutableStateOf(false) }
    val wallpaperUri by Prefs.wallpaperUriFlow(ctx).collectAsState(initial = "")
    val enableFloatingBar by Prefs.enableFloatingBottomBarFlow(ctx).collectAsState(initial = true)
    val enableFloatingBarBlur by Prefs.enableFloatingBottomBarBlurFlow(ctx)
        .collectAsState(initial = true)

    // 把选中图片复制到 App 私有目录存绝对路径,避免临时 content URI 重启失效
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

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // —— 外观(对齐 OShin about_setting 的 App 级偏好) ——
            PrefGroup("外观") {
                PrefChipsRow(title = "主题", desc = null) {
                    val cur = ThemeMode.fromKey(themeKey)
                    ThemeMode.entries.forEach { tm ->
                        FilterChip(
                            selected = tm == cur,
                            onClick = { scope.launch { Prefs.setThemeMode(ctx, tm.key) } },
                            label = { Text(tm.label) },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
                PrefDivider()
                PrefNavRow(
                    title = "界面风格",
                    desc = "当前: ${UiStyle.fromKey(uiStyleKey).label} · 仅切换主页布局, 全局保留液态玻璃",
                    onClick = { showStyleDialog = true },
                )
                PrefDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                PrefDivider()
                PrefToggleRow(
                    title = "悬浮底栏",
                    desc = "使用 Apple 风格的悬浮底栏",
                    checked = enableFloatingBar,
                    onCheckedChange = {
                        scope.launch { Prefs.setEnableFloatingBottomBar(ctx, it) }
                    },
                )
                AnimatedVisibility(visible = enableFloatingBar) {
                    Column {
                        PrefDivider()
                        PrefToggleRow(
                            title = "液态玻璃",
                            desc = "启用悬浮底栏的液态玻璃效果",
                            checked = enableFloatingBarBlur,
                            onCheckedChange = {
                                scope.launch { Prefs.setEnableFloatingBottomBarBlur(ctx, it) }
                            },
                        )
                    }
                }
            }

            // —— 其他(检测更新 / 使用文档, 对齐原设置页「其他」组的剩余项) ——
            PrefGroup("其他") {
                PrefNavRow(
                    icon = Icons.Outlined.SystemUpdate,
                    title = "检测更新",
                    desc = "App / Kinetic / 模块 三通道独立检测与更新",
                    onClick = onOpenUpdate,
                )
                PrefDivider()
                PrefNavRow(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = "使用文档",
                    desc = "功能说明 · 常见问题",
                    onClick = onOpenDocs,
                )
            }

            Spacer(Modifier.height(96.dp))
        }
    }

    // 界面风格单选对话框
    if (showStyleDialog) {
        AlertDialog(
            onDismissRequest = { showStyleDialog = false },
            title = { Text("界面风格") },
            text = {
                Column {
                    UiStyle.entries.forEach { style ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch { Prefs.setUiStyle(ctx, style.key) }
                                    showStyleDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = style.key == uiStyleKey,
                                onClick = {
                                    scope.launch { Prefs.setUiStyle(ctx, style.key) }
                                    showStyleDialog = false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(style.label)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "两种风格均为液态玻璃, 区别仅在主页是否显示仪表盘",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showStyleDialog = false }) { Text("完成") }
            },
        )
    }
}
