package com.ktwo.cts.ui.metric

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ktwo.cts.ui.theme.MetricTokens
import com.ktwo.cts.ui.theme.MonoNumber

/**
 * Metric 仪表盘的纯 Canvas 图表组件(无数据依赖, 只用稳定 API):
 * 环形进度 / 带标签进度条 / 柱状直方图。
 */

/** 环形进度: 整圈浅色轨道 + 从 12 点方向起的圆头进度弧, 中心可叠两行文字。 */
@Composable
fun RingGauge(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 96.dp,
    strokeWidth: Dp = 12.dp,
    color: Color = MetricTokens.RingOrange,
    trackColor: Color = color.copy(alpha = 0.15f),
    centerTop: String? = null,
    centerBottom: String? = null,
) {
    val p by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(300),
        label = "ringProgress",
    )
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = strokeWidth.toPx()
            val inset = sw / 2f
            val arcSize = Size(size.width - sw, size.height - sw)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Round),
            )
            val sweep = p.coerceIn(0f, 1f) * 360f
            if (sweep > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = sw, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (centerTop != null) {
                Text(
                    centerTop,
                    style = MaterialTheme.typography.titleMedium.merge(MonoNumber),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            if (centerBottom != null) {
                Text(
                    centerBottom,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 带标签进度条: 上行「label … valueText」, 下行圆角轨道 + 前景条。 */
@Composable
fun LabeledBar(
    label: String,
    pct: Int,
    valueText: String,
    color: Color,
    modifier: Modifier = Modifier,
    barHeight: Dp = 8.dp,
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(barHeight)) {
            val r = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(color = color.copy(alpha = 0.12f), cornerRadius = r)
            val w = size.width * pct.coerceIn(0, 100) / 100f
            if (w > 0f) {
                drawRoundRect(color = color, size = Size(w, size.height), cornerRadius = r)
            }
        }
    }
}

/** 柱状直方图: 等分 maxBars 个槽位, 底对齐圆角柱; values 为 0..1, 不足时左侧留空。 */
@Composable
fun BarHistogram(
    values: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = MetricTokens.Accent,
    trackColor: Color = barColor.copy(alpha = 0.10f),
    maxBars: Int = 30,
    gapFraction: Float = 0.35f,
) {
    Canvas(modifier) {
        if (maxBars <= 0 || size.width <= 0f || size.height <= 0f) return@Canvas
        val slot = size.width / maxBars
        val barW = (slot * (1f - gapFraction.coerceIn(0f, 0.9f))).coerceAtLeast(1f)
        val r = CornerRadius(barW / 2f, barW / 2f)
        val tail = if (values.size > maxBars) values.takeLast(maxBars) else values
        val startIndex = maxBars - tail.size
        for (i in 0 until maxBars) {
            val x = i * slot + (slot - barW) / 2f
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(x, 0f),
                size = Size(barW, size.height),
                cornerRadius = r,
            )
        }
        tail.forEachIndexed { idx, v ->
            val h = size.height * v.coerceIn(0f, 1f)
            if (h > 0f) {
                val x = (startIndex + idx) * slot + (slot - barW) / 2f
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = r,
                )
            }
        }
    }
}
