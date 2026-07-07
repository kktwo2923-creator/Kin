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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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

private data class LinkInfo(val fullText: String, val url: String)

private val LINKS = listOf(
    LinkInfo("源代码 GitHub", "https://github.com/kktwo2923-creator/CTS"),
    LinkInfo("调度守护进程 CTS-Daemon", "https://github.com/kktwo2923-creator/CTS-Daemon"),
    LinkInfo("UI 设计参考 SukiSU-Ultra", "https://github.com/SukiSU-Ultra/SukiSU-Ultra"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val appIcon = remember {
        runCatching {
            ctx.packageManager.getApplicationIcon(ctx.packageName)
                .toBitmap(256, 256).asImageBitmap()
        }.getOrNull()
    }

    fun openLink(url: String) {
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
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
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" +
                    (if (BuildConfig.EDITION.isNotBlank()) " · ${BuildConfig.EDITION}" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(40.dp))
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
