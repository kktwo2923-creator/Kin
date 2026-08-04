// 模仿 SukiSU-Ultra ui/screen/about: 居中大图标 + 应用名 + 版本号 + 链接卡片(行尾箭头)。

package com.ktwo.cts.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import com.ktwo.cts.BuildConfig
import com.ktwo.cts.R
import com.ktwo.cts.updater.NoUpdateDialog
import com.ktwo.cts.updater.UpdateChecker
import com.ktwo.cts.updater.UpdateDialog
import kotlinx.coroutines.launch

private data class LinkInfo(val fullText: String, val url: String)

private val LINKS = listOf(
    LinkInfo("源代码 GitHub", "https://github.com/kktwo2923-creator/CTS"),
    LinkInfo("Kin 调度守护进程", "https://github.com/kktwo2923-creator/CTS-Daemon"),
    LinkInfo("UI 设计参考 SukiSU-Ultra", "https://github.com/SukiSU-Ultra/SukiSU-Ultra"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val appIcon = remember {
        runCatching {
            ctx.packageManager.getApplicationIcon(ctx.packageName)
                .toBitmap(256, 256).asImageBitmap()
        }.getOrNull()
    }

    // 更新对话框状态
    var checking by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateChecker.CheckResult?>(null) }
    var showNoUpdate by remember { mutableStateOf(false) }

    fun openLink(url: String) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun checkUpdate() {
        if (checking) return
        checking = true
        scope.launch {
            val r = UpdateChecker.check()
            checking = false
            if (r.hasUpdate) {
                updateResult = r
            } else {
                showNoUpdate = true
            }
        }
    }

    // 对话框
    updateResult?.let { r ->
        UpdateDialog(result = r) { updateResult = null }
    }
    if (showNoUpdate) {
        NoUpdateDialog { showNoUpdate = false }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("关于") },
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
        contentWindowInsets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Horizontal),
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    modifier = Modifier.size(100.dp).clip(RoundedCornerShape(24.dp)),
                )
            } else {
                Icon(
                    CtsIcons.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(100.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                stringResource(R.string.app_name),
                modifier = Modifier.padding(top = 12.dp, bottom = 5.dp),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
            )
            Text(
                "v${BuildConfig.VERSION_NAME}" +
                    (if (BuildConfig.EDITION.isNotBlank()) " · ${BuildConfig.EDITION}" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))
            // 检查更新卡片
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !checking) { checkUpdate() }
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        if (checking) "检查中…" else "检查更新",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Medium,
                    )
                    if (checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TonalCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    LINKS.forEachIndexed { i, link ->
                        if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { openLink(link.url) }
                                .padding(horizontal = 16.dp, vertical = 18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                link.fullText,
                                modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Medium,
                            )
                            Icon(
                                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(120.dp))
        }
    }
}
