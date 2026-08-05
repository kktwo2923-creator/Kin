// 关于页 — 借鉴 OShin (suqi8/OShin) 关于界面风格:
// 大号渐变文字头部 + 浮动渐变描边检查更新按钮 + 卡片化链接列表 + 版本详情卡。
// 保持 Kin 全局液态玻璃风格(TonalCard + Haze 背景)。

package com.ktwo.cts.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.ktwo.cts.BuildConfig
import com.ktwo.cts.R

private data class LinkInfo(val icon: ImageVector, val title: String, val url: String)

private val LINKS = listOf(
    LinkInfo(Icons.Outlined.Code, "源代码 GitHub", "https://github.com/kktwo2923-creator/CTS"),
    LinkInfo(Icons.Outlined.Memory, "Kin 调度守护进程", "https://github.com/kktwo2923-creator/CTS-Daemon"),
    LinkInfo(Icons.Outlined.Public, "UI 设计参考 SukiSU-Ultra", "https://github.com/SukiSU-Ultra/SukiSU-Ultra"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenUpdate: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()

    // 头部渐变色: 夜间蓝紫 → 粉紫, 白天蓝 → 青
    val gradientColors = remember(dark) {
        if (dark) listOf(Color(0xD0A279ED), Color(0xD0E3BCB1))
        else listOf(Color(0xD03A18AD), Color(0xD0A56138))
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
        Box(Modifier.fillMaxSize()) {
            // 滚动内容
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(56.dp))

                // —— 头部: 大号渐变文字(对齐 OShin, 不再用图标) ——
                Text(
                    text = stringResource(R.string.app_name),
                    fontWeight = FontWeight.Bold,
                    fontSize = 42.sp,
                    style = TextStyle(brush = Brush.linearGradient(gradientColors)),
                )
                Text(
                    text = "v${BuildConfig.VERSION_NAME}" +
                        (if (BuildConfig.EDITION.isNotBlank()) " · ${BuildConfig.EDITION}" else ""),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )

                Spacer(Modifier.height(64.dp))

                // —— 版本详情卡 ——
                TonalCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        AboutDetailRow("版本名称", BuildConfig.VERSION_NAME)
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        AboutDetailRow("版本号", BuildConfig.VERSION_CODE.toString())
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        AboutDetailRow("应用 ID", ctx.packageName)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // —— 链接卡(统一 LinkRow) ——
                TonalCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        LINKS.forEachIndexed { i, link ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            LinkRow(icon = link.icon, title = link.title) { openLink(link.url) }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // —— 底部署名 ——
                Text(
                    "Powered By KTWO · 参考 OShin / SukiSU-Ultra",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(120.dp))
            }

            // —— 浮动检查更新按钮(渐变描边 + 阴影, 对齐 OShin) ——
            val borderColor = remember(dark) {
                if (dark) listOf(Color(0xFFE3BCB1), Color(0xFFA279ED))
                else listOf(Color(0xFFA56138), Color(0xFF3A18AD))
            }
            val bgBtn = if (dark) Color(0x1FFFFFFF) else Color(0x99FFFFFF)
            val strokeWidthPx = with(LocalDensity.current) { 1.5.dp.toPx() }
            val cornerPx = with(LocalDensity.current) { 16.dp.toPx() }
            val insetPx = strokeWidthPx / 2f

            Button(
                onClick = onOpenUpdate,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 168.dp)
                    .fillMaxWidth(0.72f)
                    .drawBehind {
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colors = borderColor,
                                start = Offset(size.width / 2f, 0f),
                                end = Offset(size.width / 2f, size.height),
                            ),
                            topLeft = Offset(insetPx, insetPx),
                            size = Size(size.width - strokeWidthPx, size.height - strokeWidthPx),
                            cornerRadius = CornerRadius(cornerPx),
                            style = Stroke(width = strokeWidthPx),
                        )
                    }
                    .shadow(
                        elevation = 2.dp,
                        shape = RoundedCornerShape(16.dp),
                        clip = true,
                        ambientColor = Color.Black.copy(alpha = 0.15f),
                        spotColor = Color.Black.copy(alpha = 0.2f),
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = bgBtn,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
            ) {
                Text(
                    text = "检查更新",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** 版本详情行: 左标题右值, 垂直居中。 */
@Composable
private fun AboutDetailRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 统一链接行: 图标 + 标题 + 右箭头, 整行可点击。 */
@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(14.dp))
        Text(
            title,
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
