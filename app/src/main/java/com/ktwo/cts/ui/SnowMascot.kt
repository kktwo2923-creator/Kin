package com.ktwo.cts.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import com.ktwo.cts.R
import kotlin.math.max

/** Align the poster's face horizontally with the actual status badge. */
@Composable
fun SnowMascot(modifier: Modifier = Modifier, targetCenterX: Float? = null) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scale = max(size.width / 1280f, size.height / 425f)
    // Face center measured on the supplied 1280 x 425 poster.
    val faceX = 695f * scale - (1280f * scale - size.width) / 2f
    val shift = targetCenterX?.let { it - faceX } ?: 0f
    Image(
        painter = painterResource(R.drawable.snow_mascot_background),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        alpha = if (com.ktwo.cts.ui.theme.isInDarkTheme()) 0.14f else 0.22f,
        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.3f) }),
        modifier = modifier.onSizeChanged { size = it }.graphicsLayer { translationX = shift },
    )
}
