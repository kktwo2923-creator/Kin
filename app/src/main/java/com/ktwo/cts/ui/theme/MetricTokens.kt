package com.ktwo.cts.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 界面风格开关: GLASS = 现状液态玻璃(默认, 逐像素不变); METRIC = 浅色仪表盘(仿 Metric 主页)。
 * 所有 Metric 分支一律以 LocalUiStyle.current == UiStyle.METRIC 判定, 不改任何函数签名/路由。
 */
enum class UiStyle(val key: String, val label: String) {
    GLASS("glass", "液态玻璃"),
    METRIC("metric", "Metric 仪表盘");

    companion object {
        fun fromKey(k: String?): UiStyle = entries.firstOrNull { it.key == k } ?: GLASS
    }
}

val LocalUiStyle = staticCompositionLocalOf { UiStyle.GLASS }

/** Metric 浅色仪表盘的设计令牌(纯色浅灰紫背景 + 白卡 + 蓝强调)。 */
object MetricTokens {
    val Bg = Color(0xFFEEF0F5)
    val CardBg = Color(0xFFFFFFFF)
    val Accent = Color(0xFF2A6DF4)
    val TextDark = Color(0xFF1A2233)
    val TextGrey = Color(0xFF8A93A6)
    val RingOrange = Color(0xFFFF8A34)
    val BarBlue = Color(0xFF3D7BFF)
    val RingGrey = Color(0xFFB8BFCC)
    val CardShape = RoundedCornerShape(24.dp)
    val CardElevation = 2.dp
}
