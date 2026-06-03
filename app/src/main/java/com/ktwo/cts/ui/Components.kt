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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlin.math.roundToInt

/**
 * 共享 UI 组件 - SukiSU-Ultra 风格 + 真·液态玻璃。
 * 整屏铺壁纸背景(WallpaperBackground)作为模糊源,卡片/导航栏用 Haze
 * 真实采样模糊它,叠顶部高光 sheen + 渐变细描边。
 */

/** 当前页面的 Haze 模糊源状态; 为 null 时玻璃退化为半透明拟态。 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * 可点击 + 按压回弹(M3 Expressive 手感):按下时轻微缩小,松开弹回。
 * 只在按压瞬间动画,几乎零开销。
 */
@Composable
fun Modifier.pressClickable(
    pressedScale: Float = 0.96f,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    return this
        .scale(s)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

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
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        listOf(
                            cs.primary.copy(alpha = 0.55f),
                            cs.tertiary.copy(alpha = 0.40f),
                            cs.secondary.copy(alpha = 0.50f),
                        )
                    )
                )
            ) {
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
                // 更透(0.40 -> 0.26),贴近 iOS 26 液态玻璃的通透感
                tints = listOf(HazeTint(tint.copy(alpha = 0.26f))),
                blurRadius = blurRadius,
                noiseFactor = 0.04f,
            ),
        ) {
            inputScale = HazeInputScale.Auto
        }
    } else {
        Modifier.background(tint.copy(alpha = 0.55f))
    }
    Box(
        modifier
            .clip(shape)
            .then(glassMod)
            // 顶面镜面高光: 顶部一条更亮的反光,快速衰减(液态玻璃的"亮顶")
            .background(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.38f),
                    0.12f to Color.White.copy(alpha = 0.12f),
                    0.5f to Color.White.copy(alpha = 0.03f),
                    1f to Color.Transparent,
                )
            )
            // 渐变描边: 上缘亮(镜面)、两侧/底淡,凸显玻璃厚度
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.70f),
                    0.35f to Color.White.copy(alpha = 0.16f),
                    1f to Color.White.copy(alpha = 0.10f),
                ),
                shape = shape,
            ),
        content = content,
    )
}

/**
 * iOS 26 风格悬浮液态玻璃导航 —
 *  - 紧凑胶囊,左右留边浮起,Haze 真实模糊背景
 *  - 选中高亮块是「液态玻璃药丸」,可点也可用手指在导航条上自由拖动滑动,
 *    松手吸附到最近的 tab(弹簧动画)
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
                Modifier.fillMaxWidth().height(70.dp).padding(7.dp),
            ) {
                val density = LocalDensity.current
                val itemWidth = maxWidth / count
                val itemPx = with(density) { itemWidth.toPx() }

                // 拖动中的手指 x(px),null = 未拖动
                var dragX by remember { mutableStateOf<Float?>(null) }
                val dragging = dragX != null
                // 拖动时高亮居中跟手,否则停在选中 tab
                val targetPx = if (dragging)
                    (dragX!! - itemPx / 2f).coerceIn(0f, (count - 1) * itemPx)
                else
                    itemPx * selectedIndex
                val animPx by animateFloatAsState(
                    targetValue = targetPx,
                    animationSpec = spring(
                        dampingRatio = if (dragging) 1f else 0.6f,
                        stiffness = if (dragging) Spring.StiffnessHigh else Spring.StiffnessMediumLow,
                    ),
                    label = "navIndicator",
                )
                // 当前高亮的 tab(拖动时为手指所在格)
                val liveIndex = if (dragging)
                    (dragX!! / itemPx).toInt().coerceIn(0, count - 1)
                else selectedIndex

                // 液态玻璃高亮药丸
                Box(
                    Modifier
                        .offset { IntOffset(animPx.roundToInt(), 0) }
                        .width(itemWidth)
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                )
                            )
                        )
                        .background(
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.30f),
                                0.5f to Color.Transparent,
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                )

                // tab 行:点选 + 拖动滑选
                Row(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(count, itemPx) {
                            detectTapGestures { pos ->
                                onSelect((pos.x / itemPx).toInt().coerceIn(0, count - 1))
                            }
                        }
                        .pointerInput(count, itemPx) {
                            detectHorizontalDragGestures(
                                onDragStart = { off -> dragX = off.x },
                                onHorizontalDrag = { _, amt ->
                                    dragX = ((dragX ?: 0f) + amt).coerceIn(0f, count * itemPx)
                                },
                                onDragEnd = {
                                    val d = dragX; dragX = null
                                    if (d != null) onSelect((d / itemPx).toInt().coerceIn(0, count - 1))
                                },
                                onDragCancel = { dragX = null },
                            )
                        }
                ) {
                    items.forEachIndexed { i, (label, icon) ->
                        NavTab(
                            label = label,
                            icon = icon,
                            selected = i == liveIndex,
                            modifier = Modifier.width(itemWidth).fillMaxHeight(),
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
        targetValue = if (selected) 1.1f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "navScale",
    )
    // 图标 + 文字居中竖列,整列被高亮药丸罩住
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = fg,
            modifier = Modifier.size(22.dp).scale(iconScale),
        )
        if (labelProgress > 0.01f) {
            Spacer(Modifier.height(2.dp * labelProgress))
            Text(
                label,
                color = fg.copy(alpha = labelProgress),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
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
