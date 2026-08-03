package com.ktwo.cts.data

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kin 守护指标推流【客户端】(Scene 式 native daemon + 本地 Socket; Phase 2 追加 Metric 式共享内存零拷贝)。
 *
 * 守护进程(root、常驻)通过抽象命名空间 unix socket "kin_metrics" 把每秒采好的快照送给 App。
 * 两级取数(能力协商, 全程可降级):
 *   ① 文本(Phase 1): 每秒读一行 `key=value`。纯 Kotlin [LocalSocket], 不需 NDK。
 *   ② 共享内存零拷贝(Phase 2, Metric 式): 连上后先发 "K1\n" 声明支持; 守护回 "KIN1 shm=1\n" 并经
 *      SCM_RIGHTS 把 memfd 从同一条 socket 发来; App 把 fd map 成只读 [ByteBuffer], 之后守护每帧只发
 *      8 字节序号(门铃), App 按序号从共享内存【直接读】payload —— payload 不再逐字节过 socket。
 *
 * 【全程可降级】: 守护未装/未运行、SELinux 拦截 socket 或 fd 映射、数据过期 —— 任一情况 [fresh] 返回 null,
 * 调用方(SysfsReader/MetricReader)自动回退到原有 File / root 读取链路, 与接入前完全一致, 绝不因此坏功能。
 *
 * 线程模型: 单后台线程「连接→(协商)→读→断开重连」, 只更新一个 @Volatile 帧引用, 读侧无锁。
 */
object KinDaemon {

    /** 守护推来的一帧解析结果(key=value 映射 + 本地接收时刻)。 */
    class Frame(private val fields: Map<String, String>, val atMs: Long) {
        fun str(k: String): String? = fields[k]
        fun int(k: String): Int? = fields[k]?.toIntOrNull()
        fun long(k: String): Long? = fields[k]?.toLongOrNull()
    }

    private const val SOCK_NAME = "kin_metrics"   // 与守护 MetricsServer::kSockName 一致
    private const val FRESH_MS = 3000L
    private const val RECONNECT_MIN_MS = 2000L
    private const val RECONNECT_MAX_MS = 30000L

    // ── 共享内存环形缓冲区布局(与守护 RingShm 一致)──
    private const val SHM_MAGIC = 0x4B494E31        // "KIN1"
    private const val SHM_VERSION = 1
    private const val SHM_HEADER = 0x40             // 头部字节数
    private const val SHM_STRIDE = 512             // 每槽字节数
    private const val SHM_SLOTS = 256
    private const val SHM_PAYLOAD_OFF = 0x10        // 槽内 payload 偏移
    private const val SHM_PAYLOAD_MAX = SHM_STRIDE - SHM_PAYLOAD_OFF

    @Volatile private var frame: Frame? = null
    @Volatile private var started = false
    // 共享内存路径若在本机(SELinux/ROM)行不通, 置位后本次会话不再请求, 只走文本, 省得反复失败重连。
    @Volatile private var shmDisabled = false

    private fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            Thread({ loop() }, "kin-daemon-sock").apply { isDaemon = true }.start()
        }
    }

    /** 3 秒内的最新推流帧; 无连接/已过期返回 null(调用方据此回退自采)。首次调用惰性拉起后台线程。 */
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
            var shm: SharedMemory? = null
            try {
                val sock = LocalSocket()
                sock.connect(LocalSocketAddress(SOCK_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                backoff = RECONNECT_MIN_MS
                val ins = sock.inputStream
                // 声明支持共享内存(旧守护无视此行, 只会发文本, 自动走文本分支)
                if (!shmDisabled) runCatching { sock.outputStream.write("K1\n".toByteArray()); sock.outputStream.flush() }
                // 读第一行(裸读, 不用 BufferedReader —— 缓冲流可能吞掉携带 fd 的那次 recvmsg 的控制消息)
                val firstLine = readRawLine(ins)
                val fds = runCatching { sock.ancillaryFileDescriptors }.getOrNull()
                val fd0 = fds?.firstOrNull()

                if (!shmDisabled && fd0 != null && firstLine != null && firstLine.startsWith("KIN1 shm=1")) {
                    // ── ② 共享内存零拷贝分支 ──
                    val buf = try {
                        // dup 一份 fd 交给 SharedMemory 拥有(原 ancillary fd 仍归 socket, 随 socket 关闭)。
                        // fromFd 接管该 pfd, 不要再手动 close pfd, 由 shm.close() 统一释放。
                        val pfd = ParcelFileDescriptor.dup(fd0)
                        val s = SharedMemory.fromFileDescriptor(pfd).also { shm = it }
                        val b = s.mapReadOnly().order(ByteOrder.LITTLE_ENDIAN)
                        require(b.getInt(0x00) == SHM_MAGIC && b.getInt(0x04) == SHM_VERSION) { "shm header mismatch" }
                        b
                    } catch (t: Throwable) {
                        // fd 映射被 SELinux 拦 / 校验失败 → 本会话起停用共享内存, 断开后重连走文本
                        shmDisabled = true
                        throw t
                    }
                    doorbellLoop(ins, buf)
                } else {
                    // ── ① 文本分支(旧守护 / 未启用共享内存 / 无 fd)──
                    firstLine?.let { parseLine(it)?.let { fr -> frame = fr } }
                    val br = BufferedReader(InputStreamReader(ins))
                    while (true) {
                        val line = br.readLine() ?: break
                        parseLine(line)?.let { frame = it }
                    }
                }
                runCatching { sock.close() }
            } catch (_: Throwable) {
                backoff = (backoff * 2).coerceAtMost(RECONNECT_MAX_MS)
            } finally {
                runCatching { shm?.close() }
            }
            // 不断开/读空时不覆写为 null, 保留上一帧由 fresh() 的 FRESH_MS 兜底过期, 避免 UI 抖动
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { return }
        }
    }

    /** 门铃循环: 每收到 8 字节 LE 序号, 从共享内存对应槽零拷贝读出 payload 并解析。 */
    private fun doorbellLoop(ins: InputStream, buf: ByteBuffer) {
        val bell = ByteArray(8)
        val payload = ByteArray(SHM_PAYLOAD_MAX)
        while (true) {
            if (!readFully(ins, bell, 8)) break             // 对端关闭
            var seq = 0L
            for (i in 0 until 8) seq = seq or ((bell[i].toLong() and 0xFF) shl (i * 8))   // LE
            if (seq <= 0L) continue
            val slot = SHM_HEADER + ((seq - 1).toInt() % SHM_SLOTS) * SHM_STRIDE
            // acquire 近似: 读前后各校验槽序号 == 门铃序号(256 槽 @1Hz 不会追尾, 双校验足够防撕裂)
            if (buf.getLong(slot) != seq) continue
            val size = buf.getInt(slot + 0x08)
            if (size <= 0 || size > SHM_PAYLOAD_MAX) continue
            val dup = buf.duplicate()
            dup.position(slot + SHM_PAYLOAD_OFF)
            dup.get(payload, 0, size)
            if (buf.getLong(slot) != seq) continue          // 读中途被覆盖 → 丢弃, 等下一帧
            parseLine(String(payload, 0, size, Charsets.UTF_8))?.let { frame = it }
        }
    }

    /** 从流里读一行(以 '\n' 结束, 不含换行); EOF 且无内容返回 null。裸读, 保证随后能取到 ancillary fd。 */
    private fun readRawLine(ins: InputStream): String? {
        val sb = StringBuilder(96)
        while (true) {
            val c = ins.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 4096) return sb.toString()       // 防御: 异常长行
        }
    }

    private fun readFully(ins: InputStream, dst: ByteArray, n: Int): Boolean {
        var off = 0
        while (off < n) {
            val r = ins.read(dst, off, n - off)
            if (r < 0) return false
            off += r
        }
        return true
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
