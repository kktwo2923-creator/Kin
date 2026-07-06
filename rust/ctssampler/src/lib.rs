//! CTS Rust native 采样库 (libctssampler.so)
//!
//! 通过裸 JNI(不依赖 jni crate)导出给 Kotlin 侧的
//! `com.ktwo.cts.nativebridge.NativeSampler` 调用。
//!
//! 硬性约定:
//! - JNI 符号名与 Kotlin 全限定类名/方法名逐字符对应:
//!   `Java_com_ktwo_cts_nativebridge_NativeSampler_<方法名>`
//! - 任何失败(读不到文件/解析失败/首帧无差分)一律返回 -1;
//! - 绝不让 panic 跨越 FFI 边界(所有入口都用 catch_unwind 兜底)。
//!
//! 注意: App 内运行不持有 root, Android 10+ 的 SELinux 可能挡住全局
//! /proc/stat 的读取——此时本库返回 -1, Kotlin 侧自动退回原有实现。

use std::ffi::c_void;
use std::fs;
use std::panic::{self, AssertUnwindSafe};
use std::sync::atomic::{AtomicI64, Ordering};

// ── CPU 占用率差分状态(库内自持, 与 Kotlin 侧的差分状态互不干扰) ──
// -1 表示尚未采到第一帧(首次调用只记录基线, 返回 -1)。
static PREV_IDLE: AtomicI64 = AtomicI64::new(-1);
static PREV_TOTAL: AtomicI64 = AtomicI64::new(-1);

/// 统一兜底包装: 闭包内任何 panic / None 都收敛为 -1, 保证不跨 FFI 传播。
fn guard<F: FnOnce() -> Option<i32>>(f: F) -> i32 {
    panic::catch_unwind(AssertUnwindSafe(f))
        .ok()
        .flatten()
        .unwrap_or(-1)
}

/// 读 /proc/stat 首行 "cpu ..." 并做差分, 计算整体 CPU 占用百分比。
/// 首次调用(无历史基线)、读/解析失败、total 未增长时返回 None(即 -1)。
fn cpu_load_impl() -> Option<i32> {
    let text = fs::read_to_string("/proc/stat").ok()?;
    let line = text.lines().next()?;
    if !line.starts_with("cpu ") {
        return None;
    }
    // 首行格式: cpu user nice system idle iowait irq softirq steal ...
    let vals: Vec<i64> = line
        .split_whitespace()
        .skip(1)
        .filter_map(|s| s.parse::<i64>().ok())
        .collect();
    if vals.len() < 5 {
        return None;
    }
    let idle = vals[3] + vals[4]; // idle + iowait
    let total: i64 = vals.iter().sum();

    // 换入本次采样, 取回上一次的基线。
    let prev_idle = PREV_IDLE.swap(idle, Ordering::SeqCst);
    let prev_total = PREV_TOTAL.swap(total, Ordering::SeqCst);

    if prev_idle < 0 || prev_total < 0 || total <= prev_total {
        return None; // 首帧 / 计数回绕 / 时间片未推进
    }
    let d_total = (total - prev_total) as f64;
    let d_idle = (idle - prev_idle) as f64;
    if d_total <= 0.0 {
        return None;
    }
    let pct = ((d_total - d_idle) / d_total * 100.0) as i32;
    Some(pct.clamp(0, 100))
}

/// 读 /proc/meminfo 的 MemTotal / MemAvailable, 计算已用内存百分比。
/// 读不到或字段缺失/非法时返回 None(即 -1)。
fn mem_used_impl() -> Option<i32> {
    let text = fs::read_to_string("/proc/meminfo").ok()?;
    let mut total: i64 = -1;
    let mut avail: i64 = -1;
    for line in text.lines() {
        // 行格式: "MemTotal:       12345678 kB"
        if let Some(rest) = line.strip_prefix("MemTotal:") {
            total = rest.split_whitespace().next()?.parse().ok()?;
        } else if let Some(rest) = line.strip_prefix("MemAvailable:") {
            avail = rest.split_whitespace().next()?.parse().ok()?;
        }
        if total >= 0 && avail >= 0 {
            break; // 两个字段都拿到, 提前结束
        }
    }
    if total <= 0 || avail < 0 || avail > total {
        return None;
    }
    let pct = (((total - avail) as f64 / total as f64) * 100.0) as i32;
    Some(pct.clamp(0, 100))
}

// ════════ JNI 导出(符号名逐字符对应 Kotlin 全限定类名+方法名) ════════
// Kotlin: package com.ktwo.cts.nativebridge / object NativeSampler
// 两个参数为 JNIEnv* 与 jclass/jobject, 本库不使用, 以 *mut c_void 占位。

/// 存活探针: 恒返回 1。Kotlin 侧加载 .so 后调它确认符号解析成功。
#[no_mangle]
pub extern "system" fn Java_com_ktwo_cts_nativebridge_NativeSampler_nativePing(
    _env: *mut c_void,
    _this: *mut c_void,
) -> i32 {
    1
}

/// 整体 CPU 占用率(0..100); 首次调用/失败返回 -1。差分基线由本库自持。
#[no_mangle]
pub extern "system" fn Java_com_ktwo_cts_nativebridge_NativeSampler_cpuLoadPercent(
    _env: *mut c_void,
    _this: *mut c_void,
) -> i32 {
    guard(cpu_load_impl)
}

/// 已用内存百分比(0..100); 读不到返回 -1。
#[no_mangle]
pub extern "system" fn Java_com_ktwo_cts_nativebridge_NativeSampler_memUsedPercent(
    _env: *mut c_void,
    _this: *mut c_void,
) -> i32 {
    guard(mem_used_impl)
}
