package com.ktwo.cts.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自定义界面图标(原生 ImageVector,描边线条风格,24x24)。
 * 全部白色描边占位 — Icon() 用 tint 统一着色,跟随主题/选中态。
 * 含: 底栏三图标 + 11 个调度模式图标(替代 emoji)。
 */
object CtsIcons {

    // ============ 底栏 ============

    /** 首页 - 速度计 */
    val Speed: ImageVector by lazy {
        icon("CtsSpeed") {
            stroke { moveTo(4.5f, 17f); arcTo(8f, 8f, 0f, true, true, 19.5f, 17f) }
            stroke { moveTo(12f, 16f); lineTo(15.6f, 9.6f) }
        }
    }

    /** 应用 - 2x2 网格 */
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

    /** 设置 - 齿轮 */
    val Settings: ImageVector by lazy {
        icon("CtsSettings") {
            stroke {
                moveTo(12f, 2.8f); lineTo(13.6f, 5.4f); lineTo(16.6f, 4.8f); lineTo(16.6f, 7.8f)
                lineTo(19.2f, 9.4f); lineTo(17.5f, 12f); lineTo(19.2f, 14.6f); lineTo(16.6f, 16.2f)
                lineTo(16.6f, 19.2f); lineTo(13.6f, 18.6f); lineTo(12f, 21.2f); lineTo(10.4f, 18.6f)
                lineTo(7.4f, 19.2f); lineTo(7.4f, 16.2f); lineTo(4.8f, 14.6f); lineTo(6.5f, 12f)
                lineTo(4.8f, 9.4f); lineTo(7.4f, 7.8f); lineTo(7.4f, 4.8f); lineTo(10.4f, 5.4f); close()
            }
            stroke {
                moveTo(15.2f, 12f)
                arcTo(3.2f, 3.2f, 0f, true, true, 8.8f, 12f)
                arcTo(3.2f, 3.2f, 0f, true, true, 15.2f, 12f); close()
            }
        }
    }

    // ============ 调度模式(替代 emoji) ============

    /** follow 跟随 - 循环箭头 */
    val Follow: ImageVector by lazy {
        icon("CtsFollow") {
            stroke { moveTo(6f, 12f); arcTo(6f, 6f, 0f, true, true, 8f, 16.5f) }
            stroke { moveTo(6f, 8f); lineTo(6f, 12f); lineTo(10f, 12f) }
        }
    }

    /** powersave 省电 - 电池 + 加号 */
    val PowerSave: ImageVector by lazy {
        icon("CtsPowerSave") {
            stroke { roundRect(6f, 7f, 18f, 17f, 2f) }                   // 电池主体
            stroke { moveTo(10.5f, 5f); lineTo(13.5f, 5f) }              // 顶帽
            stroke { moveTo(12f, 10.5f); lineTo(12f, 14f); moveTo(10.3f, 12.2f); lineTo(13.7f, 12.2f) } // 加号
        }
    }

    /** balance 均衡 - 天平 */
    val Balance: ImageVector by lazy {
        icon("CtsBalance") {
            // 立柱 + 底座 + 横梁
            stroke {
                moveTo(12f, 5f); lineTo(12f, 19f)
                moveTo(8f, 20f); lineTo(16f, 20f)
                moveTo(5f, 7f); lineTo(19f, 7f)
            }
            // 顶钮
            stroke { moveTo(13.3f, 5f); arcTo(1.3f, 1.3f, 0f, true, true, 10.7f, 5f); arcTo(1.3f, 1.3f, 0f, true, true, 13.3f, 5f); close() }
            // 左吊绳 + 秤盘(向下凹的碗)
            stroke { moveTo(5f, 7f); lineTo(3f, 12f); lineTo(7f, 12f); close() }
            stroke { moveTo(3f, 12f); arcTo(2f, 2f, 0f, false, false, 7f, 12f) }
            // 右吊绳 + 秤盘
            stroke { moveTo(19f, 7f); lineTo(17f, 12f); lineTo(21f, 12f); close() }
            stroke { moveTo(17f, 12f); arcTo(2f, 2f, 0f, false, false, 21f, 12f) }
        }
    }

    /** performance 性能 - 闪电 */
    val Performance: ImageVector by lazy {
        icon("CtsPerformance") {
            stroke {
                moveTo(13f, 3f); lineTo(5f, 13.5f); lineTo(11f, 13.5f)
                lineTo(10f, 21f); lineTo(18f, 10.5f); lineTo(12f, 10.5f); close()
            }
        }
    }

    /** fast 风驰 - 火箭 */
    val Fast: ImageVector by lazy {
        icon("CtsFast") {
            // 箭体
            stroke {
                moveTo(12f, 3f)
                arcTo(7f, 9f, 0f, false, true, 16f, 12f)
                lineTo(14f, 16f); lineTo(10f, 16f); lineTo(8f, 12f)
                arcTo(7f, 9f, 0f, false, true, 12f, 3f); close()
            }
            // 尾翼
            stroke { moveTo(10f, 14f); lineTo(7f, 18f); lineTo(9.5f, 16.5f) }
            stroke { moveTo(14f, 14f); lineTo(17f, 18f); lineTo(14.5f, 16.5f) }
            // 舷窗
            stroke { moveTo(13.4f, 9f); arcTo(1.4f, 1.4f, 0f, true, true, 10.6f, 9f); arcTo(1.4f, 1.4f, 0f, true, true, 13.4f, 9f); close() }
            // 尾焰
            stroke { moveTo(12f, 17f); lineTo(12f, 21f) }
        }
    }

    /** game_heavy 重度游戏 - 火焰(外焰 + 内焰) */
    val GameHeavy: ImageVector by lazy {
        icon("CtsGameHeavy") {
            // 外焰
            stroke {
                moveTo(12f, 2.5f)
                curveTo(13f, 6f, 17f, 7.5f, 17f, 13f)
                arcTo(5f, 5f, 0f, false, true, 7f, 13f)
                curveTo(7f, 10.5f, 8.5f, 9.5f, 9f, 8f)
                curveTo(9.5f, 10f, 10.5f, 10.5f, 11f, 10f)
                curveTo(11.5f, 8.5f, 10.5f, 6f, 12f, 2.5f); close()
            }
            // 内焰
            stroke {
                moveTo(12f, 11f)
                curveTo(13f, 12.5f, 13.5f, 13.5f, 13.5f, 14.8f)
                arcTo(1.5f, 1.5f, 0f, false, true, 10.5f, 14.8f)
                curveTo(10.5f, 13.8f, 11.3f, 12.5f, 12f, 11f); close()
            }
        }
    }

    /** game_lite 轻度游戏 - 手柄 */
    val GameLite: ImageVector by lazy {
        icon("CtsGameLite") {
            stroke {
                moveTo(8f, 8f); lineTo(16f, 8f)
                arcTo(5f, 5f, 0f, false, true, 20f, 16f)
                arcTo(2.4f, 2.4f, 0f, false, true, 16f, 16f)
                lineTo(8f, 16f)
                arcTo(2.4f, 2.4f, 0f, false, true, 4f, 16f)
                arcTo(5f, 5f, 0f, false, true, 8f, 8f); close()
            }
            // 方向键
            stroke { moveTo(7f, 12f); lineTo(10f, 12f); moveTo(8.5f, 10.5f); lineTo(8.5f, 13.5f) }
            // 按钮
            stroke { moveTo(15.5f, 11f); arcTo(0.6f, 0.6f, 0f, true, true, 15.4f, 11f); close() }
            stroke { moveTo(17.5f, 13f); arcTo(0.6f, 0.6f, 0f, true, true, 17.4f, 13f); close() }
        }
    }

    /** video 视频 - 播放三角 + 框 */
    val Video: ImageVector by lazy {
        icon("CtsVideo") {
            stroke { roundRect(3.5f, 6f, 20.5f, 18f, 3f) }
            stroke { moveTo(10.5f, 9.5f); lineTo(15.5f, 12f); lineTo(10.5f, 14.5f); close() }
        }
    }

    /** social 社交 - 对话气泡 */
    val Social: ImageVector by lazy {
        icon("CtsSocial") {
            stroke {
                moveTo(4f, 7f)
                arcToRelative(3f, 3f, 0f, false, true, 3f, -3f)
                lineTo(17f, 4f)
                arcToRelative(3f, 3f, 0f, false, true, 3f, 3f)
                lineTo(20f, 13f)
                arcToRelative(3f, 3f, 0f, false, true, -3f, 3f)
                lineTo(9f, 16f); lineTo(5f, 20f); lineTo(5f, 16f)
                arcToRelative(1f, 1f, 0f, false, true, -1f, -1f); close()
            }
        }
    }

    /** reading 阅读 - 打开的书 */
    val Reading: ImageVector by lazy {
        icon("CtsReading") {
            stroke { moveTo(12f, 6f); lineTo(12f, 19f) }
            stroke {
                moveTo(12f, 6f)
                arcToRelative(5f, 3f, 0f, false, false, -8f, 0f)
                lineTo(4f, 17f)
                arcToRelative(5f, 3f, 0f, false, true, 8f, 0f)
            }
            stroke {
                moveTo(12f, 6f)
                arcToRelative(5f, 3f, 0f, false, true, 8f, 0f)
                lineTo(20f, 17f)
                arcToRelative(5f, 3f, 0f, false, false, -8f, 0f)
            }
        }
    }

    /** music 音乐 - 音符 */
    val Music: ImageVector by lazy {
        icon("CtsMusic") {
            stroke { moveTo(9f, 18f); lineTo(9f, 6f); lineTo(18f, 4f); lineTo(18f, 16f) }
            stroke { moveTo(9f, 18f); arcTo(2.4f, 2f, 0f, true, true, 6.2f, 16.2f); arcTo(2.4f, 2f, 0f, true, true, 9f, 18f); close() }
            stroke { moveTo(18f, 16f); arcTo(2.4f, 2f, 0f, true, true, 15.2f, 14.2f); arcTo(2.4f, 2f, 0f, true, true, 18f, 16f); close() }
        }
    }

    /** 按 Mode.key 取图标 */
    fun forMode(key: String): ImageVector = when (key) {
        "follow"      -> Follow
        "powersave"   -> PowerSave
        "balance"     -> Balance
        "performance" -> Performance
        "fast"        -> Fast
        "game_heavy"  -> GameHeavy
        "game_lite"   -> GameLite
        "video"       -> Video
        "social"      -> Social
        "reading"     -> Reading
        "music"       -> Music
        else          -> Follow
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
