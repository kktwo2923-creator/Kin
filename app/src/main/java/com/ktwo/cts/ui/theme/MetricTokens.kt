package com.ktwo.cts.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 界面风格开关: 两种风格都是全局液态玻璃, 区别仅在主页布局 —
 * GLASS = 经典主页; METRIC = 主页显示 Metric 仪表盘。
 * 判定一律用 LocalUiStyle.current == UiStyle.METRIC, 不改任何函数签名/路由。
 */
enum class UiStyle(val key: String, val label: String) {
    GLASS("glass", "液态玻璃"),
    METRIC("metric", "Metric 仪表盘");

    companion object {
        fun fromKey(k: String?): UiStyle = entries.firstOrNull { it.key == k } ?: GLASS
    }
}

val LocalUiStyle = staticCompositionLocalOf { UiStyle.GLASS }

/** Metric 仪表盘的图表设计令牌(仅图表用色 + 卡片几何; 文字颜色一律随主题取 colorScheme)。 */
object MetricTokens {
    val Accent = Color(0xFF2A6DF4)
    val RingOrange = Color(0xFFFF8A34)
    val BarBlue = Color(0xFF3D7BFF)
    val RingGrey = Color(0xFFB8BFCC)
    val CardShape = RoundedCornerShape(24.dp)
    val CardElevation = 2.dp
}

/** 仪表盘大数值专用等宽字体样式(系统内置 Monospace, 不依赖任何字体资源)。 */
val MonoNumber = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
)
