package com.ktwo.cts.ui

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.ktwo.cts.ui.theme.isInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 当前页面的 Haze 模糊源状态;为 null 时玻璃退化为半透明拟态。 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/** 按压时轻微缩小后弹回的点击效果(M3 Expressive 手感)。 */
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

/** 整屏壁纸背景,作为 Haze 模糊采样源。wallpaperUri 为空时用默认渐变。 */
@Composable
fun WallpaperBackground(
    hazeState: HazeState,
    wallpaperUri: String = "",
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    // 壁纸解码是重 IO, 不能在主线程组合期做 → produceState + Dispatchers.IO 异步解码
    val bitmap by produceState<ImageBitmap?>(null, wallpaperUri) {
        value = if (wallpaperUri.isBlank()) null
        else withContext(Dispatchers.IO) {
            runCatching {
                if (wallpaperUri.startsWith("/")) {
                    android.graphics.BitmapFactory.decodeFile(wallpaperUri)?.asImageBitmap()
                } else {
                    ctx.contentResolver.openInputStream(android.net.Uri.parse(wallpaperUri)).use { ins ->
                        android.graphics.BitmapFactory.decodeStream(ins)?.asImageBitmap()
                    }
                }
            }.getOrNull()
        }
    }

    Box(modifier.fillMaxSize().hazeSource(hazeState)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val dark = isInDarkTheme()
            val grad = if (dark)
                listOf(Color(0xFF26282E), Color(0xFF3A3D46), Color(0xFF1C1D22))
            else
                listOf(Color(0xFFE9EBEF), Color(0xFFCBD0D8), Color(0xFFDFE2E8))
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(grad))) {
                Box(
                    Modifier.size(260.dp).align(Alignment.TopEnd)
                        .background(Color.White.copy(alpha = if (dark) 0.08f else 0.30f), CircleShape)
                )
                Box(
                    Modifier.size(220.dp).align(Alignment.BottomStart)
                        .background(
                            (if (dark) Color.White else Color.Black).copy(alpha = 0.06f),
                            CircleShape,
                        )
                )
            }
        }
    }
}

// AGSL 折射着色器(Android 13+):边缘带内折射放大 + 高光/阴影。
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
    float d = sdRoundBox(c, hsz, r);
    float edge = -d;
    float t = clamp(1.0 - edge / band, 0.0, 1.0);
    float2 dir = c / max(length(c), 1.0);
    // R/G/B 用略不同折射率采样产生色散(iOS26 标志性彩色镶边)
    // R/G/B 用略不同折射率采样产生色散(iOS26 标志性彩色镶边)
    float pull = t * t * 14.0;
    half4 col;
    col.r = content.eval(coord - dir * (pull * 1.14)).r;
    col.g = content.eval(coord - dir * pull).g;
    half4 cb = content.eval(coord - dir * (pull * 0.86));
    col.b = cb.b;
    col.a = cb.a;
    float tl = clamp((-dir.x - dir.y) * 0.5 + 0.5, 0.0, 1.0);
    float rim = smoothstep(0.78, 1.0, t);
    col.rgb += (t * t) * tl * 0.22;   // 高光
    col.rgb += rim * tl * 0.18;       // 边缘补光
    col.rgb -= (t * t) * (1.0 - tl) * 0.05;
    return col;
}
"""

/** Android 13+ 在玻璃背景层叠 AGSL 折射;个别驱动拒绝编译时安全退化为无折射。 */
@Composable
private fun Modifier.liquidRefraction(cornerRadiusPx: Float, shape: Shape): Modifier {
    if (Build.VERSION.SDK_INT < 33) return this
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
                    shader.setFloatUniform("band", 15f)
                    val lens = android.graphics.RenderEffect
                        .createRuntimeShaderEffect(shader, "content")
                    // 先提饱和度再折射,模拟 vibrancy 效果
                    val sat = android.graphics.ColorMatrix().apply { setSaturation(1.35f) }
                    val vibrancy = android.graphics.RenderEffect
                        .createColorFilterEffect(android.graphics.ColorMatrixColorFilter(sat))
                    renderEffect = android.graphics.RenderEffect
                        .createChainEffect(lens, vibrancy)
                        .asComposeRenderEffect()
                }
            }
        }
}

/** 液态玻璃容器:Haze 模糊 + AGSL 边缘折射(Android 13+)+ 高光描边。cornerDp 传胶囊/圆时给大值,着色器内自动夹限。 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    tint: Color = Color.Unspecified,
    blurRadius: Dp = 30.dp,
    cornerDp: Dp = 26.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val hazeState = LocalHazeState.current
    val cornerPx = with(LocalDensity.current) { cornerDp.toPx() }
    // tint 仅调用方显式传入(语义色)时染色;否则用中性白/深灰
    val dark = isInDarkTheme()
    val neutral = if (dark) Color(0xFF2A2A2C) else Color.White
    val effTint = if (tint == Color.Unspecified) neutral else tint
    val glassMod = if (hazeState != null) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = neutral,
                tints = listOf(HazeTint(effTint.copy(alpha = if (tint == Color.Unspecified) 0.14f else 0.20f))),
                blurRadius = blurRadius,
                noiseFactor = 0.015f,
            ),
        ) {
            inputScale = HazeInputScale.Auto
        }
    } else {
        Modifier.background(effTint.copy(alpha = 0.5f))
    }
    Box(
        modifier
            .shadow(
                elevation = 12.dp,
                shape = shape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.10f),
                spotColor = Color.Black.copy(alpha = 0.16f),
            )
            .clip(shape)
    ) {
        Box(
            Modifier
                .matchParentSize()
                .liquidRefraction(cornerPx, shape)
                .then(glassMod)
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.24f),
                        0.16f to Color.White.copy(alpha = 0.06f),
                        0.66f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.035f),
                    )
                )
        )
        // 顶部高光 + 底沿回光描边
        Box(
            Modifier.matchParentSize().border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.95f),
                    0.22f to Color.White.copy(alpha = 0.22f),
                    0.7f to Color.White.copy(alpha = 0.06f),
                    1f to Color.White.copy(alpha = 0.34f),
                ),
                shape = shape,
            )
        )
        content()
    }
}

/** 大圆角玻璃卡片(替代原 Tonal 卡片,接口保持兼容)。默认中性白/深灰玻璃(iOS26),不吃主题色。 */
@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    contentColor: Color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface
                          else contentColorFor(color),
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(modifier = modifier, tint = color) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Column(content = content)
        }
    }
}

/** 顶部状态主卡:working=true 用 secondaryContainer,否则 errorContainer。 */
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
                // FlowRow: 一行放不下时徽章整块换行,避免被挤成 0 宽竖排
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

// ============ 统一的 preference 行规范(组=标题+卡片; 行自带内距; 分隔线 16dp 缩进) ============
// 从 SettingsScreen.kt 提取为公共组件, 供 AboutSettingScreen / FeaturesScreen 等复用。

/** 设置分组: 组标题 + 一张卡片; 标题与卡片间 8dp, 组间距由父级 spacedBy(16dp) 控制。 */
@Composable
fun PrefGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        TonalCard(modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

/** 组内分隔线, 两侧 16dp 缩进, 与行内距对齐。 */
@Composable
fun PrefDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}

/** 开关行: 标题 + 说明 + Switch, 行自带内距。 */
@Composable
fun PrefToggleRow(
    title: String, desc: String,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 导航/对话框行: (可选图标 +) 标题 + 说明 + 右箭头, 整行(含内距)可点击。 */
@Composable
fun PrefNavRow(
    title: String, desc: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 选择行: 标题 (+ 说明) + FilterChip 流式排列。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrefChipsRow(
    title: String,
    desc: String?,
    chips: @Composable FlowRowScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontWeight = FontWeight.Medium)
        if (desc != null) {
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = chips,
        )
    }
}

/** 权限状态行: 图标颜色表示已授/未授, 尾部对勾或「设置」按钮, 行自带内距。 */
@Composable
fun PermRow(
    name: String, granted: Boolean, desc: String,
    icon: ImageVector,
    onAction: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
