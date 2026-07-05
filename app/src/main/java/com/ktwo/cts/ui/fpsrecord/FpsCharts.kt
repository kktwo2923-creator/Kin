// 帧率记录详情页的原创 Compose Canvas 折线图与统计小件(只用稳定 API)。
package com.ktwo.cts.ui.fpsrecord

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ktwo.cts.ui.theme.MonoNumber

/**
 * 通用折线图: 横向网格线 + 折线 + 线下渐变填充。
 * values 为按时间顺序的采样值; minY/maxY 可固定纵轴(如百分比 0..100), 不传则按数据自适应。
 */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    minY: Float? = null,
    maxY: Float? = null,
) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    Canvas(modifier) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        // 横向网格(4 条等分线)
        val rows = 3
        for (i in 0..rows) {
            val y = size.height * i / rows
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (values.size < 2) return@Canvas

        var dataLo = Float.MAX_VALUE
        var dataHi = -Float.MAX_VALUE
        for (v in values) {
            if (v < dataLo) dataLo = v
            if (v > dataHi) dataHi = v
        }
        val pad = ((dataHi - dataLo) * 0.08f).coerceAtLeast(0.5f)
        val lo = minY ?: (dataLo - pad)
        val hi = maxY ?: (dataHi + pad)
        val span = (hi - lo).coerceAtLeast(1e-3f)

        val stepX = size.width / (values.size - 1)
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - lo) / span).coerceIn(0f, 1f) * size.height
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        // 线下渐变填充
        val fill = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(
                0f to color.copy(alpha = 0.22f),
                1f to color.copy(alpha = 0f),
            ),
        )
        drawPath(
            line, color,
            style = Stroke(
                width = 2.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/** 统计项: 上小标签 + 下等宽数值(MAX/MIN/AVG/1% Low 等)。 */
@Composable
fun ChartStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.merge(MonoNumber),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
