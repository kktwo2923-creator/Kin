package com.ktwo.cts.ui

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
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

// iOS 26 液态玻璃折射着色器(AGSL,Android 13+)。
// 在圆角矩形/胶囊的「边缘带」内把背景采样坐标向内拉,产生玻璃边缘的折射放大,
// 再叠左上镜面高光 / 右下轻阴影。content = 本层已绘制的(模糊)背景。
private const val LIQUID_GLASS_AGSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float band;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

half4 main(float2 coord) {
    float2 hsz = size * 0.5;
    float2 c = coord - hsz;
    float r = min(radius, min(hsz.x, hsz.y));
    float d = sdRoundBox(c, hsz, r);           // <0 在内部
    float edge = -d;                            // 距边缘的内向距离
    float t = clamp(1.0 - edge / band, 0.0, 1.0);
    float2 dir = c / max(length(c), 1.0);
    // 边缘附近把采样点向内拉 -> 边缘折射放大
    float2 uv = coord - dir * (t * t * 11.0);
    half4 col = content.eval(uv);
    // 左上镜面高光 / 右下轻阴影
    float tl = clamp((-dir.x - dir.y) * 0.5 + 0.5, 0.0, 1.0);
    col.rgb += (t * t) * tl * 0.30;
    col.rgb -= (t * t) * (1.0 - tl) * 0.06;
    return col;
}
"""

/** 在玻璃背景层上叠 AGSL 折射(仅 Android 13+,低版本无操作) */
@Composable
private fun Modifier.liquidRefraction(cornerRadiusPx: Float, shape: Shape): Modifier {
    if (Build.VERSION.SDK_INT < 33) return this
    // AGSL 在运行时由 GPU 驱动编译,个别机型/驱动可能拒绝 -> 兜底,失败就退化为无折射,
    // 绝不让整屏崩溃。
    val shader = remember {
        runCatching { android.graphics.RuntimeShader(LIQUID_GLASS_AGSL) }.getOrNull()
    } ?: return this
    var sz by remember { mutableStateOf(IntSize.Zero) }
    return this
        .onSizeChanged { sz = it }
        .graphicsLayer {
            this.clip = true
            this.shape = shape
            if (sz.width > 0 && sz.height > 0) {
                runCatching {
                    shader.setFloatUniform("size", sz.width.toFloat(), sz.height.toFloat())
                    shader.setFloatUniform("radius", cornerRadiusPx)
                    shader.setFloatUniform("band", 18f)
                    renderEffect = android.graphics.RenderEffect
                        .createRuntimeShaderEffect(shader, "content")
                        .asComposeRenderEffect()
                }
            }
        }
}

/**
 * 液态玻璃容器: Haze 真实模糊背景 + (Android 13+)AGSL 边缘折射 + 高光 + 渐变描边。
 * @param cornerDp 用于折射 SDF 的圆角半径(胶囊/圆传入较大值即可,着色器内自动夹到 min/2)。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    blurRadius: Dp = 24.dp,
    cornerDp: Dp = 22.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = LocalHazeState.current
    val cornerPx = with(LocalDensity.current) { cornerDp.toPx() }
    val glassMod = if (hazeState != null) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = tint,
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
    Box(modifier.clip(shape)) {
        // 玻璃背景层(被折射):模糊背景 + 顶面镜面高光
        Box(
            Modifier
                .matchParentSize()
                .liquidRefraction(cornerPx, shape)   // Android 13+ 折射
                .then(glassMod)
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.38f),
                        0.12f to Color.White.copy(alpha = 0.12f),
                        0.5f to Color.White.copy(alpha = 0.03f),
                        1f to Color.Transparent,
                    )
                )
        )
        // 渐变描边(独立层,不被折射)
        Box(
            Modifier.matchParentSize().border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.70f),
                    0.35f to Color.White.copy(alpha = 0.16f),
                    1f to Color.White.copy(alpha = 0.10f),
                ),
                shape = shape,
            )
        )
        // 内容层(清晰,不被折射)
        content()
    }
}

/**
 * OShin / SukiSU 风格悬浮液态玻璃导航 —
 *  - 等宽 N 格,每格「图标在上 + 文字在下」竖排,文字常显
 *  - 背后一颗「液态玻璃椭圆胶囊」高亮指示器,盖住整格(图标+文字)
 *  - 指示器可被手指自由横向拖动,实时跟手,松手吸附到最近的格
 *  - 点击某格也会把指示器平滑移过去;最近的格点亮成主色
 */
@Composable
fun FloatingGlassNav(
    items: List<Pair<String, ImageVector>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val n = items.size
    val pos = remember { Animatable(selectedIndex.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 外部选中变化时(且非拖动中)把指示器平滑移过去
    LaunchedEffect(selectedIndex) {
        if (!dragging) pos.animateTo(
            selectedIndex.toFloat(),
            spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        )
    }

    // 跟手/吸附过程中,指示器最接近哪一格 -> 该格点亮
    val activeIndex = pos.value.roundToInt().coerceIn(0, n - 1)

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = CircleShape,
            tint = MaterialTheme.colorScheme.surfaceContainer,
            blurRadius = 30.dp,
            cornerDp = 48.dp,   // 胶囊: 着色器内自动夹到高度的一半
        ) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 8.dp),
            ) {
                val density = LocalDensity.current
                val slotWpx = with(density) { maxWidth.toPx() } / n
                val slotWdp = maxWidth / n
                val primary = MaterialTheme.colorScheme.primary
                val pill = RoundedCornerShape(20.dp)   // 整块覆盖图标+文字的圆角胶囊

                // 长按拿起 -> 指示器抬起(放大 + 发光 + 玻璃更浓),0=落下 1=拿起
                val lift by animateFloatAsState(
                    targetValue = if (dragging) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = 0.55f,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    label = "navLift",
                )

                // 1) 液态玻璃高亮指示器 —— 整块圆角胶囊,覆盖选中格的「图标 + 文字」
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset((pos.value * slotWpx).roundToInt(), 0) }
                        .width(slotWdp)
                        .fillMaxHeight()
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                        .graphicsLayer {
                            // 拿起时放大(横向更夸张),液态被"提"起来的手感
                            scaleX = 1f + 0.16f * lift
                            scaleY = 1f + 0.08f * lift
                            shadowElevation = 22f * lift
                            shape = pill
                            clip = false
                            ambientShadowColor = primary
                            spotShadowColor = primary
                        }
                        .clip(pill)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    primary.copy(alpha = 0.40f + 0.32f * lift),
                                    primary.copy(alpha = 0.22f + 0.22f * lift),
                                )
                            )
                        )
                        .background(
                            Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.38f + 0.34f * lift),
                                0.55f to Color.Transparent,
                            )
                        )
                        .border(
                            1.dp,
                            Color.White.copy(alpha = 0.45f + 0.35f * lift),
                            pill,
                        )
                )

                // 2) 手势层(点击选格 + 长按拿起后滑动)+ 图标/文字
                Row(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(n, slotWpx) {
                            detectTapGestures { o ->
                                val i = (o.x / slotWpx).toInt().coerceIn(0, n - 1)
                                onSelect(i)
                            }
                        }
                        .pointerInput(n, slotWpx) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { o ->
                                    dragging = true
                                    // 拿起瞬间指示器跳到手指下
                                    scope.launch {
                                        pos.snapTo(
                                            (o.x / slotWpx - 0.5f)
                                                .coerceIn(0f, (n - 1).toFloat())
                                        )
                                    }
                                },
                                onDragEnd = {
                                    val target = pos.value.roundToInt().coerceIn(0, n - 1)
                                    dragging = false
                                    onSelect(target)
                                    scope.launch {
                                        pos.animateTo(
                                            target.toFloat(),
                                            spring(
                                                dampingRatio = 0.72f,
                                                stiffness = Spring.StiffnessMediumLow,
                                            ),
                                        )
                                    }
                                },
                                onDragCancel = { dragging = false },
                            ) { change, dragAmount ->
                                change.consume()
                                scope.launch {
                                    pos.snapTo(
                                        (pos.value + dragAmount.x / slotWpx)
                                            .coerceIn(0f, (n - 1).toFloat())
                                    )
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items.forEachIndexed { i, (label, icon) ->
                        NavSlot(
                            label = label,
                            icon = icon,
                            active = i == activeIndex,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavSlot(
    label: String,
    icon: ImageVector,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val fg by animateColorAsState(
        if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "navFg",
    )
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = fg,
            modifier = Modifier.size(23.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            softWrap = false,
        )
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
