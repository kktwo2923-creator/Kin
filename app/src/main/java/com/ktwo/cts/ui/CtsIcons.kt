package com.ktwo.cts.ui

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自定义界面图标(原生 ImageVector,描边线条风格)。
 * 全部用白色描边占位 — Icon() 会用 tint 统一染成选中/未选中色,
 * 因此描边线条能正确跟随主题着色(不会被双色干扰)。
 * 底栏: 速度计(首页) / 方块网格(应用) / 齿轮(设置)。
 */
object CtsIcons {

    /** 首页 - 速度计弧 + 指针 */
    val Speed: ImageVector by lazy {
        icon("CtsSpeed") {
            // 表盘弧(开口向下)
            stroke {
                moveTo(4.5f, 17f)
                arcTo(8f, 8f, 0f, true, true, 19.5f, 17f)
            }
            // 指针
            stroke {
                moveTo(12f, 16f)
                lineTo(15.6f, 9.6f)
            }
        }
    }

    /** 应用 - 2x2 圆角方块网格 */
    val Apps: ImageVector by lazy {
        icon("CtsApps") {
            stroke {
                roundRect(4f, 4f, 10f, 10f, 2f)
                roundRect(14f, 4f, 20f, 10f, 2f)
                roundRect(4f, 14f, 10f, 20f, 2f)
                roundRect(14f, 14f, 20f, 20f, 2f)
            }
        }
    }

    /** 设置 - 齿轮(八齿外圈 + 中心圆,描边) */
    val Settings: ImageVector by lazy {
        icon("CtsSettings") {
            // 外圈八齿
            stroke {
                moveTo(12f, 2.8f)
                lineTo(13.6f, 5.4f)
                lineTo(16.6f, 4.8f)
                lineTo(16.6f, 7.8f)
                lineTo(19.2f, 9.4f)
                lineTo(17.5f, 12f)
                lineTo(19.2f, 14.6f)
                lineTo(16.6f, 16.2f)
                lineTo(16.6f, 19.2f)
                lineTo(13.6f, 18.6f)
                lineTo(12f, 21.2f)
                lineTo(10.4f, 18.6f)
                lineTo(7.4f, 19.2f)
                lineTo(7.4f, 16.2f)
                lineTo(4.8f, 14.6f)
                lineTo(6.5f, 12f)
                lineTo(4.8f, 9.4f)
                lineTo(7.4f, 7.8f)
                lineTo(7.4f, 4.8f)
                lineTo(10.4f, 5.4f)
                close()
            }
            // 中心圆
            stroke {
                moveTo(15.2f, 12f)
                arcTo(3.2f, 3.2f, 0f, true, true, 8.8f, 12f)
                arcTo(3.2f, 3.2f, 0f, true, true, 15.2f, 12f)
                close()
            }
        }
    }

    // ---- 构建辅助 ----
    private fun icon(
        name: String,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private fun ImageVector.Builder.stroke(
        width: Float = 2f,
        pathBlock: PathBuilder.() -> Unit,
    ) {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = pathBlock,
        )
    }
}

/** PathBuilder 扩展: 画圆角矩形 */
private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
    moveTo(l + rad, t)
    lineTo(r - rad, t)
    arcToRelative(rad, rad, 0f, false, true, rad, rad)
    lineTo(r, b - rad)
    arcToRelative(rad, rad, 0f, false, true, -rad, rad)
    lineTo(l + rad, b)
    arcToRelative(rad, rad, 0f, false, true, -rad, -rad)
    lineTo(l, t + rad)
    arcToRelative(rad, rad, 0f, false, true, rad, -rad)
    close()
}
