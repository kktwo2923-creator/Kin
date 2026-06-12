package com.ktwo.cts.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// 同 SukiSU-Ultra ui/theme/Theme.kt 的三个效果开关 CompositionLocal
val LocalEnableBlur = staticCompositionLocalOf { false }
val LocalEnableFloatingBottomBar = staticCompositionLocalOf { false }
val LocalEnableFloatingBottomBarBlur = staticCompositionLocalOf { false }

/** 当前主题是否为暗色(考虑强制浅/深色模式, 而非只看系统)。 */
val LocalDarkMode = staticCompositionLocalOf { false }

@Composable
fun isInDarkTheme(): Boolean = LocalDarkMode.current

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    secondary = Color(0xFF388E3C),
    tertiary = Color(0xFFD32F2F),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF63A4FF),
    secondary = Color(0xFF66BB6A),
    tertiary = Color(0xFFE57373),
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

/** 主题模式: dynamic(动态取色) / system / light / dark / mono(黑白) */
enum class ThemeMode(val key: String, val label: String) {
    DYNAMIC("dynamic", "动态"),
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
    MONO("mono", "黑白");

    companion object {
        fun fromKey(k: String?): ThemeMode = entries.firstOrNull { it.key == k } ?: DYNAMIC
    }
}

@Composable
fun AppTheme(
    mode: ThemeMode = ThemeMode.DYNAMIC,
    content: @Composable () -> Unit,
) {
    val sysDark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme: ColorScheme = when (mode) {
        ThemeMode.DYNAMIC ->
            // Material You: 颜色跟随系统壁纸自动生成(Android 12+),低版本回退到默认配色
            if (Build.VERSION.SDK_INT >= 31) {
                if (sysDark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else {
                if (sysDark) DarkScheme else LightScheme
            }
        ThemeMode.LIGHT -> LightScheme
        ThemeMode.DARK -> DarkScheme
        ThemeMode.MONO -> if (sysDark) MonoDark else MonoLight
        ThemeMode.SYSTEM -> if (sysDark) DarkScheme else LightScheme
    }
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> sysDark
    }
    CompositionLocalProvider(LocalDarkMode provides dark) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
