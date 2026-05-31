package com.ktwo.cts.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.vector.ImageVector
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

/** 整屏壁纸渐变背景 — 玻璃的模糊采样源。标 hazeSource 让上层玻璃可采样。 */
@Composable
fun WallpaperBackground(hazeState: HazeState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        cs.primary.copy(alpha = 0.55f),
                        cs.tertiary.copy(alpha = 0.40f),
                        cs.secondary.copy(alpha = 0.50f),
                    )
                )
            )
            .hazeSource(hazeState)
    ) {
        // 几团柔光,模糊后形成丰富的玻璃质感
        Box(
            Modifier.size(260.dp).align(Alignment.TopEnd)
                .background(Color.White.copy(alpha = 0.18f), CircleShape)
        )
        Box(
            Modifier.size(220.dp).align(Alignment.BottomStart)
                .background(cs.tertiary.copy(alpha = 0.30f), CircleShape)
        )
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
                tints = listOf(HazeTint(tint.copy(alpha = 0.40f))),
                blurRadius = blurRadius,
                noiseFactor = 0.04f,
            ),
        )
    } else {
        Modifier.background(tint.copy(alpha = 0.62f))
    }
    Box(
        modifier
            .clip(shape)
            .then(glassMod)
            // 顶面高光
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.22f),
                    0.45f to Color.White.copy(alpha = 0.05f),
                    1f to Color.Transparent,
                )
            )
            // 渐变描边
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.45f),
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.20f),
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
                Modifier.fillMaxWidth().height(58.dp).padding(6.dp),
            ) {
                val itemWidth = maxWidth / count
                // 滑动高亮块: 弹簧动画驱动水平偏移
                val indicatorOffset by animateDpAsState(
                    targetValue = itemWidth * selectedIndex,
                    animationSpec = spring(
                        dampingRatio = 0.65f,         // 略带回弹
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "navIndicator",
                )
                Box(
                    Modifier
                        .offset(x = indicatorOffset)
                        .width(itemWidth)
                        .fillMaxHeight()
                        .padding(horizontal = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.20f))
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
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.18f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "navScale",
    )
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .clip(CircleShape)
            .clickable(interaction, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = fg,
            modifier = Modifier.size(22.dp).scale(iconScale),
        )
        // 选中时文字淡入(用宽度动画自然展开)
        val labelWidth by animateFloatAsState(
            targetValue = if (selected) 1f else 0f,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "navLabel",
        )
        if (labelWidth > 0.01f) {
            Spacer(Modifier.width(6.dp * labelWidth))
            Text(
                label,
                color = fg.copy(alpha = labelWidth),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.scale(labelWidth.coerceIn(0.6f, 1f)),
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
