package com.ktwo.cts.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// 同 SukiSU-Ultra ui/theme/Theme.kt 的效果开关 CompositionLocal
val LocalEnableFloatingBottomBar = staticCompositionLocalOf { false }
val LocalEnableFloatingBottomBarBlur = staticCompositionLocalOf { false }

/** 当前主题是否为暗色(考虑强制浅/深色模式, 而非只看系统)。 */
val LocalDarkMode = staticCompositionLocalOf { false }
val LocalSnowTheme = staticCompositionLocalOf { false }

@Composable
fun isInDarkTheme(): Boolean = LocalDarkMode.current

// SukiSU-Ultra 非 Monet 默认配色(取自 miuix 0.9.2 lightColorScheme/darkColorScheme)
private val LightScheme = lightColorScheme(
    primary = Color(0xFF3482FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC2D9FF),
    onPrimaryContainer = Color(0xFF0B2D5C),
    secondary = Color(0xFF5D9BFF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAF2FF),
    onSecondaryContainer = Color(0xFF25406B),
    tertiary = Color(0xFF6EB5FF),
    background = Color(0xFFF7F7F7),
    onBackground = Color(0xFF303030),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF303030),
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF666666),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF2F2F2),
    surfaceContainerHighest = Color(0xFFE8E8E8),
    outline = Color(0xFFA8A8A8),
    outlineVariant = Color(0xFFD9D9D9),
    error = Color(0xFFE94634),
    onError = Color.White,
    errorContainer = Color(0xFFFDF6F4),
    onErrorContainer = Color(0xFF410002),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF277AF7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B3B54),
    onPrimaryContainer = Color(0xFF99C7F1),
    secondary = Color(0xFF4788FF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF253E64),
    onSecondaryContainer = Color(0xFFC2D9FF),
    tertiary = Color(0xFF338FE4),
    background = Color(0xFF131313),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFF9A9A9A),
    surfaceContainer = Color(0xFF242424),
    surfaceContainerHigh = Color(0xFF2C2C2C),
    surfaceContainerHighest = Color(0xFF343434),
    outline = Color(0xFF4F4F4F),
    outlineVariant = Color(0xFF3F3F3F),
    error = Color(0xFFF12522),
    onError = Color.White,
    errorContainer = Color(0xFF2E0603),
    onErrorContainer = Color(0xFFF12522),
)

/** 主题模式: 同 SukiSU 主题设置 — 跟随系统 / 浅色 / 深色 */
enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun fromKey(k: String?): ThemeMode = entries.firstOrNull { it.key == k } ?: SYSTEM
    }
}

@Composable
fun AppTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    uiStyle: UiStyle = UiStyle.GLASS,
    snowTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val sysDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> sysDark
    }
    // 全风格统一液态玻璃配色: uiStyle 只决定主页是否显示 Metric 仪表盘布局, 不再切换配色。
    val base = if (dark) DarkScheme else LightScheme
    val scheme: ColorScheme = if (!snowTheme) base else if (dark) base.copy(
        primary = Color(0xFF38CFFF), onPrimary = Color(0xFF002F42),
        primaryContainer = Color(0xFF004C70), onPrimaryContainer = Color(0xFFD8F5FF),
        secondary = Color(0xFFFF80BD), onSecondary = Color(0xFF4E0028),
        secondaryContainer = Color(0xFF661D43), onSecondaryContainer = Color(0xFFFFD9E9),
        tertiary = Color(0xFF40E0C1), onTertiary = Color(0xFF00382D),
        background = Color(0xFF171B20), onBackground = Color(0xFFE6EDF3),
        surface = Color(0xFF21262D), onSurface = Color(0xFFE6EDF3),
        surfaceVariant = Color(0xFF303B46), onSurfaceVariant = Color(0xFFBCCBD7),
        surfaceContainer = Color(0xFF242B33), outlineVariant = Color(0xFF435260),
    ) else base.copy(
        primary = Color(0xFF006EBC), onPrimary = Color.White,
        primaryContainer = Color(0xFFC1EAFF), onPrimaryContainer = Color(0xFF003C62),
        secondary = Color(0xFFB92170), onSecondary = Color.White,
        secondaryContainer = Color(0xFFFFD9EA), onSecondaryContainer = Color(0xFF660C3A),
        tertiary = Color(0xFF007F6E), onTertiary = Color.White,
        background = Color(0xFFF5F9FC), onBackground = Color(0xFF243548),
        surface = Color.White, onSurface = Color(0xFF243548),
        surfaceVariant = Color(0xFFE3F0F8), onSurfaceVariant = Color(0xFF526779),
        surfaceContainer = Color(0xFFF8FBFE), surfaceContainerHigh = Color(0xFFECF4FA),
        surfaceContainerHighest = Color(0xFFE3F0F8), outlineVariant = Color(0xFFCDDFEA),
    )
    CompositionLocalProvider(
        LocalDarkMode provides dark,
        LocalSnowTheme provides snowTheme,
        LocalUiStyle provides uiStyle,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
