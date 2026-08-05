package com.ktwo.cts.updater

import com.ktwo.cts.updater.UpdateChecker.CheckResult
import com.ktwo.cts.updater.UpdateChecker.VersionInfo

/**
 * 单个更新通道(APP / 二进制 Kinetic / 模块)的下载安装状态。
 *
 * @param downloading 是否正在下载
 * @param progress    下载进度 0-100
 * @param done        是否已完成(APK 已调起安装器 / 二进制已热更新 / 模块已下载到 sdcard)
 * @param error       失败文案(空 = 未失败)
 * @param message     成功后的提示文案
 * @param outputPath  模块下载后的本地路径(供"打开 Magisk"用)
 */
data class ComponentState(
    val downloading: Boolean = false,
    val progress: Int = 0,
    val done: Boolean = false,
    val error: String = "",
    val message: String = "",
    val outputPath: String = "",
)

/**
 * 更新页面整体 UI 状态机, 对齐 AStop UpdateUiState 的字段集合(checking / snapshot / error /
 * 各通道下载状态 / lastCheckedTime), 但简化为 Kin 三通道结构。
 *
 * @param checking        是否正在检查更新
 * @param result          最近一次检查结果(含三通道 VersionInfo, null 表示该通道无更新)
 * @param error           检查失败文案(空 = 未失败)
 * @param lastCheckedTime 最近一次检查成功的时间戳(ms)
 * @param app             APP 通道下载状态
 * @param binary          二进制 Kinetic 通道下载状态
 * @param module          模块 zip 通道下载状态
 */
data class UpdateUiState(
    val checking: Boolean = false,
    val result: CheckResult = CheckResult(null, null, null),
    val error: String = "",
    val lastCheckedTime: Long = 0L,
    val app: ComponentState = ComponentState(),
    val binary: ComponentState = ComponentState(),
    val module: ComponentState = ComponentState(),
) {
    val appInfo: VersionInfo? get() = result.appUpdate
    val kinInfo: VersionInfo? get() = result.kinUpdate
    val moduleInfo: VersionInfo? get() = result.moduleUpdate

    /** 三通道任意一个有更新即为 true。 */
    val hasUpdate: Boolean get() = result.hasUpdate

    /** 任意通道 forceUpdate=true 即为强制更新(启动提示 Dialog 只允许"去更新")。 */
    val hasForceUpdate: Boolean
        get() = (result.appUpdate?.forceUpdate == true) ||
            (result.kinUpdate?.forceUpdate == true) ||
            (result.moduleUpdate?.forceUpdate == true)
}
