package com.ktwo.cts.data

/**
 * 悬浮监视器类型(仿 Metric 悬浮窗管理器)。纯 Kotlin, 无 Compose/Android 依赖,
 * 供服务、Prefs、UI 三方共用。accent 为各监视器主色(ARGB Long)。
 */
enum class OverlayType(
    val id: String,
    val title: String,
    val desc: String,
    val accent: Long,
) {
    LOAD   ("load",    "负载监视器", "以悬浮窗显示当前设备负载",     0xFF0FA5A5),
    PROCESS("process", "进程监视器", "以悬浮窗显示运行中的进程信息", 0xFF34A853),
    THREAD ("thread",  "线程监视器", "以悬浮窗显示线程采样信息",     0xFF8B5CF6),
    MINI   ("mini",    "迷你监视器", "顶部居中细条显示性能概览(点击穿透)", 0xFFFF8A34),
    FPS    ("fps",     "帧率记录器", "以悬浮窗显示帧率记录状态",     0xFFEC4899),
    TEMP   ("temp",    "温度监视器", "以悬浮窗显示设备温度",         0xFFF97316);

    companion object {
        fun of(id: String?): OverlayType? = entries.find { it.id == id }
    }
}
