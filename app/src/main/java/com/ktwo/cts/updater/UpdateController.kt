package com.ktwo.cts.updater

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ktwo.cts.updater.UpdateChecker.VersionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 更新状态机控制器, 对齐 AStop `UpdateViewModel` 的职责:
 * 持有 [UpdateUiState] 单一数据源, 暴露 checkUpdates / 各通道下载方法。
 *
 * 与 ViewModel 的区别: Kin 现有代码不用 ViewModel(Compose 直接 remember + 协程),
 * 这里用普通类 + mutableStateOf 模拟 StateFlow 的"单一数据源 + 状态机"思想,
 * 由 Composable 用 `remember { UpdateController(scope) }` 持有, 生命周期跟随页面。
 *
 * 所有网络/root 操作走 IO 调度器; 状态更新在主线程(Compose snapshot)。
 */
class UpdateController(
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf(UpdateUiState())
        private set

    /** 是否正在检查更新(防重入)。 */
    val checking: Boolean get() = state.checking

    /** 检查更新。失败时写 error, 成功时写 result + lastCheckedTime。 */
    fun checkUpdates() {
        if (state.checking) return
        state = state.copy(checking = true, error = "")
        scope.launch {
            val r = UpdateChecker.check()
            val now = System.currentTimeMillis()
            // 无论是否有更新都写入 result, 便于页面显示"已是最新"(三通道 null)。
            // 切换 result 时清空旧的下载态(避免残留上一次的进度)。
            state = state.copy(
                checking = false,
                result = r,
                lastCheckedTime = now,
                error = "",
                app = state.app.copy(downloading = false, progress = 0, done = false, error = "", message = ""),
                binary = state.binary.copy(downloading = false, progress = 0, done = false, error = "", message = ""),
                module = state.module.copy(downloading = false, progress = 0, done = false, error = "", message = "", outputPath = ""),
            )
        }
    }

    // ── APP 通道: 下载 APK 并调起系统安装器 ──

    fun downloadApp(ctx: Context, info: VersionInfo) {
        if (state.app.downloading) return
        state = state.copy(app = state.app.copy(downloading = true, progress = 0, done = false, error = "", message = ""))
        UpdateChecker.downloadAndInstallApk(
            ctx, info,
            onProgress = { p -> state = state.copy(app = state.app.copy(progress = p)) },
            onDone = { ok, err ->
                state = state.copy(
                    app = state.app.copy(
                        downloading = false,
                        done = ok,
                        error = if (ok) "" else err,
                        message = if (ok) "已下载, 正在调起安装器…" else "",
                    )
                )
            },
        )
    }

    // ── 二进制 Kinetic 通道: 下载 + root cp 覆盖 + 重启守护(免重启手机) ──

    fun downloadBinary(ctx: Context, info: VersionInfo) {
        if (state.binary.downloading) return
        state = state.copy(binary = state.binary.copy(downloading = true, progress = 0, done = false, error = "", message = ""))
        scope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                UpdateChecker.downloadAndInstallBinary(ctx, info) { p ->
                    state = state.copy(binary = state.binary.copy(progress = p))
                }
            }
            state = state.copy(
                binary = state.binary.copy(
                    downloading = false,
                    done = ok,
                    error = if (ok) "" else msg,
                    message = if (ok) msg else "",
                )
            )
        }
    }

    // ── 模块 zip 通道: 下载到 /sdcard/Android/Kin/, 提示用户在 Magisk/KSU 刷入 ──

    fun downloadModule(ctx: Context, info: VersionInfo) {
        if (state.module.downloading) return
        state = state.copy(module = state.module.copy(downloading = true, progress = 0, done = false, error = "", message = "", outputPath = ""))
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                UpdateChecker.downloadDaemonZip(ctx, info) { p ->
                    state = state.copy(module = state.module.copy(progress = p))
                }
            }
            state = state.copy(
                module = state.module.copy(
                    downloading = false,
                    done = path != null,
                    error = if (path != null) "" else "下载失败, 请检查网络",
                    message = if (path != null) "已下载, 请在 Magisk/KernelSU 中刷入并重启" else "",
                    outputPath = path ?: "",
                )
            )
        }
    }

    /** 打开 Magisk/KernelSU 管理器(模块下载完成后用)。 */
    fun openModuleManager(ctx: Context) = UpdateChecker.openModuleManager(ctx)
}
