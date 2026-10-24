package com.ktwo.cts.data

import com.ktwo.cts.root.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * 后台轮询 daemon 的 schema 3 auto_status.txt, 给 Compose 提供
 * [snapshots] 一条流. 用独立 CoroutineScope, 不依赖 Compose 生命周期,
 * 避免 AutoMonitorScreen 反复进出时重建.
 *
 * daemon 存活时 1000ms 一轮, 不存活时指数退避到最多 5000ms.
 */
object KinRuntimeRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _snapshots = MutableStateFlow(RuntimeSnapshot())
    val snapshots: StateFlow<RuntimeSnapshot> = _snapshots.asStateFlow()

    @Volatile private var job: Job? = null

    /** 启动后台轮询; 幂等, 多次调用只起一个 Job. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoffMs = 1000L
            while (true) {
                val snap = doOnePoll()
                _snapshots.value = snap
                backoffMs = if (snap.daemonAlive == true) 1000L
                            else (backoffMs * 2).coerceAtMost(5000L)
                delay(backoffMs.milliseconds)
            }
        }
    }

    /** 手动刷一次 (测试 / 进页面时可选). */
    fun refreshNow() {
        scope.launch { _snapshots.value = doOnePoll() }
    }

    private fun doOnePoll(): RuntimeSnapshot {
        val r = try {
            RootShell.readAutoStatusSnapshot()
        } catch (_: Exception) {
            RootShell.AutoStatusRead("", false, RootShell.AutoStatusRead.Fail.SHELL_ERROR)
        }
        if (r.raw.isBlank()) return _snapshots.value.copy(daemonAlive = r.alive)
        return RuntimeSnapshot(auto = AutoStatus.parse(r.raw), daemonAlive = r.alive)
    }
}
