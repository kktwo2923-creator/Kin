package com.ktwo.cts.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 浅色 — 高亮、近白表面 + iOS 蓝绿红,体现液态玻璃通透感
private val LightScheme = lightColorScheme(
    primary = Color(0xFF007AFF),            // iOS 蓝
    onPrimary = Color.White,
    secondary = Color(0xFF34C759),          // iOS 绿
    tertiary = Color(0xFFFF3B30),           // iOS 红
    background = Color(0xFFF4F8FF),
    onBackground = Color(0xFF0A1626),
    surface = Color(0xFFF7FAFF),
    onSurface = Color(0xFF0A1626),
    surfaceVariant = Color(0xFFE8EFFA),
    onSurfaceVariant = Color(0xFF46586C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFDFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8EBFF),
    onPrimaryContainer = Color(0xFF003063),
    secondaryContainer = Color(0xFFD7F6DF),
    onSecondaryContainer = Color(0xFF06310F),
    outline = Color(0xFF8A99AC),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF0A84FF),            // iOS 暗色蓝
    secondary = Color(0xFF30D158),
    tertiary = Color(0xFFFF453A),
)

// 黑白(单色)主题 — primary/secondary/tertiary 全用灰阶
private val MonoLight = lightColorScheme(
    primary = Color(0xFF1A1A1A),
    onPrimary = Color.White,
    secondary = Color(0xFF4A4A4A),
    tertiary = Color(0xFF2E2E2E),
    primaryContainer = Color(0xFFE2E2E2),
    onPrimaryContainer = Color(0xFF101010),
    secondaryContainer = Color(0xFFE8E8E8),
    onSecondaryContainer = Color(0xFF1A1A1A),
)
private val MonoDark = darkColorScheme(
    primary = Color(0xFFEDEDED),
    onPrimary = Color(0xFF101010),
    secondary = Color(0xFFBDBDBD),
    tertiary = Color(0xFFD0D0D0),
    primaryContainer = Color(0xFF2C2C2C),
    onPrimaryContainer = Color(0xFFF2F2F2),
    secondaryContainer = Color(0xFF262626),
    onSecondaryContainer = Color(0xFFEDEDED),
)

/** 主题模式: system / light / dark / mono(黑白) */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    MONO("mono", "黑白");

    companion object {
        fun fromKey(k: String?): ThemeMode = entries.firstOrNull { it.key == k } ?: SYSTEM
    }
}

@Composable
fun AppTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val sysDark = isSystemInDarkTheme()
    val scheme: ColorScheme = when (mode) {
        ThemeMode.LIGHT -> LightScheme
        ThemeMode.DARK -> DarkScheme
        ThemeMode.MONO -> if (sysDark) MonoDark else MonoLight
        ThemeMode.SYSTEM -> if (sysDark) DarkScheme else LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
