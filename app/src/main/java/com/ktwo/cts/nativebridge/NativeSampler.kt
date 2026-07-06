package com.ktwo.cts.nativebridge

/**
 * Rust native 采样库(libctssampler.so)的 JNI 桥。
 *
 * 安全设计: .so 缺失(CI 未编 Rust)或加载失败时 available=false,
 * 调用方必须退回现有 Kotlin 实现——Rust 路径永远是"可选增强", 不影响出包。
 * runCatching 捕获 Throwable, 因此 UnsatisfiedLinkError 也会被兜住。
 */
object NativeSampler {
    val available: Boolean = runCatching {
        System.loadLibrary("ctssampler")
        nativePing() == 1
    }.getOrDefault(false)

    /** 存活探针: Rust 侧恒返回 1, 用于确认 .so 加载且符号解析成功。 */
    external fun nativePing(): Int

    /** 整体 CPU 占用 %(0..100), 失败/首帧 -1。差分基线由 Rust 侧自持。 */
    external fun cpuLoadPercent(): Int

    /** 已用内存 %(0..100), 读不到 -1。 */
    external fun memUsedPercent(): Int
}
