package com.ktwo.cts.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/**
 * 共享 UI 组件 - SukiSU-Ultra 风格 + 真·液态玻璃。
 * 整屏铺壁纸背景(WallpaperBackground)作为模糊源,卡片/导航栏用 Haze
 * 真实采样模糊它,叠顶部高光 sheen + 渐变细描边。
 */

/** 当前页面的 Haze 模糊源状态; 为 null 时玻璃退化为半透明拟态。 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * 整屏壁纸背景 — 玻璃的模糊采样源。标 hazeSource 让上层玻璃可采样。
 * wallpaperUri 非空时显示用户自定义图片,否则用默认渐变 + 柔光球。
 */
@Composable
fun WallpaperBackground(
    hazeState: HazeState,
    wallpaperUri: String = "",
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val bitmap = remember(wallpaperUri) {
        if (wallpaperUri.isBlank()) null
        else runCatching {
            if (wallpaperUri.startsWith("/")) {
                // 私有目录绝对路径
                android.graphics.BitmapFactory.decodeFile(wallpaperUri)?.asImageBitmap()
            } else {
                ctx.contentResolver.openInputStream(android.net.Uri.parse(wallpaperUri)).use { ins ->
                    android.graphics.BitmapFactory.decodeStream(ins)?.asImageBitmap()
                }
            }
        }.getOrNull()
    }

    Box(modifier.fillMaxSize().hazeSource(hazeState)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 默认壁纸: 先铺一层明亮底,再叠柔和彩光球 -> 玻璃模糊后高亮通透
            Box(
                Modifier.fillMaxSize()
                    .background(cs.background)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                cs.primary.copy(alpha = 0.35f),
                                cs.secondary.copy(alpha = 0.22f),
                                cs.tertiary.copy(alpha = 0.28f),
                            )
                        )
                    )
            ) {
                Box(
                    Modifier.size(280.dp).align(Alignment.TopEnd)
                        .background(Color.White.copy(alpha = 0.35f), CircleShape)
                )
                Box(
                    Modifier.size(240.dp).align(Alignment.BottomStart)
                        .background(cs.primary.copy(alpha = 0.28f), CircleShape)
                )
            }
        }
    }
}

/** 液态玻璃容器: Haze 真实模糊背景 + 高光 + 渐变描边 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    blurRadius: Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = LocalHazeState.current
    val glassMod = if (hazeState != null) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = tint,
                // 提高亮度: 玻璃体色更白更通透,呈现 iOS 磨砂质感
                tints = listOf(HazeTint(tint.copy(alpha = 0.30f))),
                blurRadius = blurRadius,
                noiseFactor = 0.05f,
            ),
        )
    } else {
        Modifier.background(tint.copy(alpha = 0.55f))
    }
    Box(
        modifier
            .clip(shape)
            .then(glassMod)
            // 顶面高光(增强,液态玻璃高光更亮)
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.38f),
                    0.4f to Color.White.copy(alpha = 0.10f),
                    1f to Color.White.copy(alpha = 0.02f),
                )
            )
            // 渐变描边(更亮的玻璃边缘高光)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.65f),
                        Color.White.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.30f),
                    )
                ),
                shape = shape,
            ),
        content = content,
    )
}

/**
 * iOS 26 风格悬浮液态玻璃导航 —
 *  - 紧凑胶囊,左右留边浮起,Haze 真实模糊背景
 *  - 选中高亮块用弹簧动画在 tab 间「滑动」(animateDpAsState + spring)
 *  - 选中图标弹性放大,文字淡入
 */
@Composable
fun FloatingGlassNav(
    items: List<Pair<String, ImageVector>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val count = items.size
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
            .padding(bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            tint = MaterialTheme.colorScheme.surfaceContainer,
            blurRadius = 30.dp,
        ) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(64.dp).padding(8.dp),
            ) {
                val itemWidth = maxWidth / count
                // 滑动高亮块(圆角小方块,居中在图标下),弹簧动画驱动水平偏移
                val indicatorOffset by animateDpAsState(
                    targetValue = itemWidth * selectedIndex,
                    animationSpec = spring(
                        dampingRatio = 0.6f,          // 略带回弹
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "navIndicator",
                )
                Box(
                    Modifier
                        .offset(x = indicatorOffset)
                        .width(itemWidth)
                        .fillMaxHeight()
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                )
                // tab 项
                Row(Modifier.fillMaxSize()) {
                    items.forEachIndexed { i, (label, icon) ->
                        NavTab(
                            label = label,
                            icon = icon,
                            selected = i == selectedIndex,
                            modifier = Modifier.width(itemWidth).fillMaxHeight(),
                            onClick = { onSelect(i) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "navFg",
    )
    // 选中: 图标轻微上移 + 放大,文字从下方淡入弹出(不挤压图标)
    val labelProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "navLabel",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "navScale",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 图标: 选中时整体上移 5dp 给下方文字腾位
        Icon(
            icon,
            contentDescription = label,
            tint = fg,
            modifier = Modifier
                .size(22.dp)
                .offset(y = (-5).dp * labelProgress)
                .scale(iconScale),
        )
        // 文字: 固定在偏下位置,随选中从下淡入上浮
        if (labelProgress > 0.01f) {
            Text(
                label,
                color = fg.copy(alpha = labelProgress),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (6.dp) * (1f - labelProgress))
                    .padding(bottom = 2.dp),
            )
        }
    }
}

/** 大圆角玻璃卡片(替代原 Tonal 卡片,接口保持兼容) */
@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = contentColorFor(color),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(modifier = modifier, tint = color) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Column(content = content)
        }
    }
}

/**
 * 顶部状态主卡(对应 SukiSU StatusCard / WarningCard):
 *  - working=true  -> secondaryContainer + 勾
 *  - working=false -> errorContainer + 警告
 * 左图标 + 右标题/副标题,可选内联徽章行与尾部内容(开关等)。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun StatusHeroCard(
    working: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    badges: List<Pair<String, Boolean>> = emptyList(), // text to isPrimary
    trailing: (@Composable () -> Unit)? = null,
) {
    val container = if (working)
        MaterialTheme.colorScheme.secondaryContainer
    else
        MaterialTheme.colorScheme.errorContainer
    val onContainer = if (working)
        MaterialTheme.colorScheme.onSecondaryContainer
    else
        MaterialTheme.colorScheme.onErrorContainer

    TonalCard(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        contentColor = onContainer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                // 标题 + 徽章用 FlowRow: 一行放不下时整块徽章换到下一行,
                // 而不是被挤成 0 宽导致文字竖排。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    badges.forEach { (text, primary) ->
                        Badge(text, primary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = onContainer.copy(alpha = 0.8f),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}

/** 内联小徽章(LKM/GKI 之类) */
@Composable
fun Badge(text: String, primary: Boolean = true) {
    val bg = if (primary) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.error
    val fg = if (primary) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onError
    Surface(shape = RoundedCornerShape(8.dp), color = bg, contentColor = fg) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 统计小卡(并排,如 Superuser 数 / 模块数) */
@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    label: String,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    TonalCard(modifier = modifier) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, null, modifier = Modifier.size(26.dp), tint = tint)
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 信息卡 - 堆叠的 label/value 行(对应 SukiSU InfoCard) */
@Composable
fun InfoCard(title: String, rows: List<Pair<String, String>>) {
    TonalCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            rows.forEachIndexed { i, (label, value) ->
                if (i > 0) Spacer(Modifier.height(14.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 分区标题 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}
