package com.ktwo.cts.data

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Kin 守护指标推流【客户端】(Scene 式 native daemon + 本地 Socket 架构的 App 侧)。
 *
 * 守护进程(root、常驻)通过抽象命名空间 unix socket "kin_metrics" 每秒推来一行 `key=value` 快照,
 * 这里用纯 Kotlin [LocalSocket] 直连读取(Phase 1 不需要 NDK)。取到的最新一帧供 [SysfsReader] 等
 * 作为「快路径」优先使用 —— 一次 socket 读取替代原本每秒多次 root shell / open sysfs 的高开销取数。
 *
 * 【全程可降级】: 连不上(守护未装 / 未运行 / SELinux 拦截抽象 socket)或数据过期时 [fresh] 返回 null,
 * 调用方自动回退到原有的 File / root 读取链路, 行为与接入前完全一致, 不会因此坏掉任何功能。
 *
 * 线程模型: 单个后台守护线程做「连接→逐行读→断开重连」, 只更新一个 @Volatile 帧引用, 读侧无锁。
 */
object KinDaemon {

    /** 守护推来的一帧解析结果(key=value 映射 + 本地接收时刻)。 */
    class Frame(private val fields: Map<String, String>, val atMs: Long) {
        fun str(k: String): String? = fields[k]
        fun int(k: String): Int? = fields[k]?.toIntOrNull()
        fun long(k: String): Long? = fields[k]?.toLongOrNull()
    }

    private const val SOCK_NAME = "kin_metrics"   // 与守护 MetricsServer::kSockName 一致
    private const val FRESH_MS = 3000L            // 超过 3s 未更新即视为断连, 回退自采
    private const val RECONNECT_MIN_MS = 2000L    // 断开后重连初始退避
    private const val RECONNECT_MAX_MS = 30000L   // 一直连不上(无守护/SELinux 拦)时退避上限, 省电

    @Volatile private var frame: Frame? = null
    @Volatile private var started = false

    private fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            Thread({ loop() }, "kin-daemon-sock").apply { isDaemon = true }.start()
        }
    }

    /**
     * 3 秒内的最新推流帧; 无连接 / 已过期返回 null(调用方据此回退自采)。
     * 首次调用会惰性拉起后台连接线程。
     */
    fun fresh(): Frame? {
        ensureStarted()
        val f = frame ?: return null
        return if (System.currentTimeMillis() - f.atMs <= FRESH_MS) f else null
    }

    /** 当前是否有守护在经 socket 实时供数(供诊断/状态展示用)。 */
    fun connected(): Boolean = fresh() != null

    private fun loop() {
        var backoff = RECONNECT_MIN_MS
        while (true) {
            try {
                val sock = LocalSocket()
                sock.connect(LocalSocketAddress(SOCK_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                backoff = RECONNECT_MIN_MS          // 连上即重置退避(掉线后能快速重连)
                BufferedReader(InputStreamReader(sock.inputStream)).use { br ->
                    while (true) {
                        val line = br.readLine() ?: break   // 对端关闭
                        parseLine(line)?.let { frame = it }
                    }
                }
                runCatching { sock.close() }
            } catch (_: Throwable) {
                // 连不上 / 读中断: 守护可能未运行或 SELinux 拦截 → 清帧、退避后重试(期间调用方全程回退自采)。
                // 退避逐次翻倍到上限: 无守护环境不会每 2s 空连一次, 省电。
                backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
            }
            frame = null
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }
        }
    }

    private fun parseLine(line: String): Frame? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val map = HashMap<String, String>()
        for (tok in t.split(' ')) {
            val i = tok.indexOf('=')
            if (i > 0 && i < tok.length - 1) map[tok.substring(0, i)] = tok.substring(i + 1)
        }
        return if (map.isEmpty()) null else Frame(map, System.currentTimeMillis())
    }
}
