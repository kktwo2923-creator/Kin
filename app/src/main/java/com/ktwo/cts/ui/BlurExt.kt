// Mirrored from SukiSU-Ultra ui/util/BlurExt.kt — MiuixTheme swapped for MaterialTheme.
// CTS 页面是透明卡片叠全局壁纸(SukiSU 是不透明 surface 页面), 顶/底栏模糊
// 必须把壁纸层并入采样源, 否则模糊出来是一块不透明 surface 色。

package com.ktwo.cts.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import com.ktwo.cts.ui.liquid.CombinedBackdrop
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported

/** 全屏壁纸背景的采样层, 由 AppRoot 提供; 各页顶栏与之合成出"壁纸+内容"的模糊源。 */
val LocalWallpaperBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
fun rememberBlurBackdrop(enableBlur: Boolean): LayerBackdrop? {
    if (!enableBlur || !isRenderEffectSupported()) return null
    return rememberLayerBackdrop()
}

/** 顶栏消费用: 壁纸层(下) + 页面内容层(上) 合成; 无壁纸层时退化为仅内容。 */
@Composable
fun rememberBarBackdrop(contentBackdrop: LayerBackdrop?): Backdrop? {
    val wallpaper = LocalWallpaperBackdrop.current
    return remember(wallpaper, contentBackdrop) {
        when {
            contentBackdrop == null -> null
            wallpaper == null -> contentBackdrop
            else -> CombinedBackdrop(wallpaper, contentBackdrop)
        }
    }
}

@Composable
fun BlurredBar(
    backdrop: Backdrop?,
    blurActive: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = if (blurActive && backdrop != null) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = BlurColors(
                    blendColors = listOf(
                        // 源码为 surface 0.87f; CTS 叠在壁纸上, 减淡让玻璃感透出来
                        BlendColorEntry(color = MaterialTheme.colorScheme.surface.copy(0.65f)),
                    ),
                ),
            )
        } else {
            Modifier
        },
    ) {
        content()
    }
}
