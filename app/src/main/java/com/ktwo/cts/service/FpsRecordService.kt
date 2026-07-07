// 帧率录制前台服务: 锁定一个前台应用, 每秒采一个 FpsPoint, 前台切换/手动停止时
// 汇总统计并落盘。通知样式与生命周期参照 NotifyService(specialUse)。
package com.ktwo.cts.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ktwo.cts.R
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.fpsrecord.FpsPoint
import com.ktwo.cts.data.fpsrecord.FpsRecordStore
import com.ktwo.cts.data.fpsrecord.FpsSampler
import com.ktwo.cts.data.fpsrecord.FpsSession
import com.ktwo.cts.data.fpsrecord.ThreadSampler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class FpsRecordService : Service() {

    /** 录制实时状态(UI 胶囊/按钮直接 collect)。 */
    data class RecState(
        val active: Boolean = false,     // 服务运行中(含等待锁定阶段)
        val recording: Boolean = false,  // 已锁定应用并在采样
        val pkg: String = "",
        val appName: String = "",
        val fps: Int = -1,
        val elapsedSec: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private val stopReasonReq = AtomicReference<String?>(null)
    // 开始请求序号(单飞): 每次 beginRecording 自增, join 后仅当仍是最新序号才真正重启
    private val startEpoch = java.util.concurrent.atomic.AtomicInteger(0)
    // 录制时长限制(秒, 0=不限): 由启动方(帧率悬浮窗时长片)传入, 服务内自守
    @Volatile private var limitSec = 0
    // 重启待处理: 旧循环收尾时若为 true, finishAndStop 只保存不停服务(等重启协程接管)
    @Volatile private var restartPending = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                if (loopJob?.isActive == true) {
                    stopReasonReq.set("手动停止")   // 采样循环 1s 内感知并收尾保存
                } else {
                    _state.value = RecState()
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf()
                }
            }
            else -> {   // ACTION_START(或系统拉起)
                ensureChannel()
                startForeground(NOTIF_ID, buildNotif("帧率录制", "准备中"))
                limitSec = intent?.getIntExtra(EXTRA_LIMIT_SEC, 0) ?: 0
                beginRecording(intent?.getStringExtra(EXTRA_PKG))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        _state.value = RecState()
        scope.cancel()
    }

    private fun beginRecording(targetPkg: String?) {
        val myEpoch = startEpoch.incrementAndGet()   // 本次开始请求的序号(单飞: 仅最新请求真正重启)
        val old = loopJob
        if (old?.isActive == true) {
            // 旧循环还在收尾(最长 1s): 请求其停止, 等 join 后重启, 不再吞掉本次开始请求。
            // 置 restartPending 让旧循环收尾走的 finishAndStop 跳过 stopSelf(否则会杀掉整个服务,
            // 把正在等 join 的重启协程一并 cancel, 新录制永远不会开始)。
            restartPending = true
            stopReasonReq.compareAndSet(null, "手动停止")
            scope.launch {
                runCatching { old.join() }
                // 若期间又来了更新的开始请求(epoch 变大), 本协程作废, 交给最新那个重启 →
                // 避免多个 join 协程叠加, 互相把对方刚起的录制又停掉(乒乓)。
                if (startEpoch.get() == myEpoch) beginRecording(targetPkg)
            }
            return
        }
        restartPending = false
        stopReasonReq.set(null)
        loopJob = scope.launch {
            val cellCount = runCatching { Prefs.cellCount(this@FpsRecordService) }.getOrDefault(1)
            val detector = ForegroundDetector(this@FpsRecordService)
            fun fg(): String = AppSwitchService.foreground.ifEmpty {
                runCatching { detector.currentForegroundPackage() }.getOrNull().orEmpty()
            }

            // ① 锁定目标: 指定了 pkg 直接用; 否则等用户切到本 App 之外的应用(从列表页点开始时前台是 CTS 自己)
            var locked = targetPkg.orEmpty()
            while (isActive && locked.isEmpty() && stopReasonReq.get() == null) {
                val f = fg()
                if (f.isNotEmpty() && f != packageName) { locked = f; break }
                _state.value = RecState(active = true)
                notify("等待进入目标应用", "切换到要录制的应用后自动开始")
                delay(1000)
            }
            if (locked.isEmpty() || !isActive) { finishAndStop(null); return@launch }

            val appName = runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(locked, 0)
                ).toString()
            }.getOrDefault(locked)

            // ② 秒级采样(FPS 每 1s 一点; 线程负载每 2s 一次, 解耦以控制 root 开销)
            val sampler = FpsSampler()
            val threadSampler = ThreadSampler()
            var threadPid = -1          // 锁定应用主进程 pid(懒解析, 失败下个周期重试)
            val points = ArrayList<FpsPoint>()
            val startMs = System.currentTimeMillis()
            var reason: String? = null
            // 先建立线程负载基线: ThreadSampler 首次调用只建基线、不产差分, 若等到循环里才第一次采,
            // 短录制(3~5s)会拿不到线程数据。此处提前解析 pid 并采一次基线, 使首个偶数刻即有真实差分。
            runCatching {
                threadPid = ThreadSampler.resolvePid(locked)
                if (threadPid > 0) threadSampler.sampleOnce(threadPid)
            }
            while (isActive) {
                reason = stopReasonReq.get()
                if (reason != null) break
                val f = fg()
                if (f.isNotEmpty() && f != locked) { reason = "前台应用切换"; break }
                val tick = System.currentTimeMillis()
                val p = sampler.sample(cellCount, locked)
                points.add(p)
                // M3: 每 2 秒采一次线程负载(无 root / 失败静默, 不影响主流程)
                if (points.size % 2 == 0) {
                    runCatching {
                        if (threadPid <= 0) threadPid = ThreadSampler.resolvePid(locked)
                        if (threadPid > 0) threadSampler.sampleOnce(threadPid)
                    }
                }
                // 已录时长按【真实墙钟】计, 不再假设"1 点=1 秒"(慢刻会让每点跨 >1s)
                val elapsedSec = (((tick - startMs) / 1000L).toInt()).coerceAtLeast(0)
                _state.value = RecState(
                    active = true, recording = true, pkg = locked, appName = appName,
                    fps = p.fps, elapsedSec = elapsedSec,
                )
                notify("录制中 · $appName", "${if (p.fps > 0) p.fps else 0}fps · ${elapsedSec}s")
                if (points.size >= MAX_POINTS) { reason = "达到时长上限"; break }
                // 用户设定的限时(时长片): 按真实墙钟到点自动停 —— 与悬浮窗是否还开着无关
                val lim = limitSec
                if (lim > 0 && elapsedSec >= lim) { reason = "到达设定时长"; break }
                // 扣掉本次采样耗时, 尽量保持 1 秒一个点
                val spent = System.currentTimeMillis() - tick
                delay((1000L - spent).coerceIn(200L, 1000L))
            }

            // ③ 收尾: 太短(<3s)不落盘, 避免误触产生垃圾记录
            val endMs = System.currentTimeMillis()
            val session = if (points.size >= 3) FpsSampler.buildSession(
                id = "%d_%04x".format(startMs, Random.nextInt(0x10000)),
                pkg = locked, appName = appName,
                startMs = startMs, endMs = endMs,
                stopReason = reason ?: "手动停止",
                points = points,
                threads = runCatching { threadSampler.aggregate() }.getOrDefault(emptyList()),
            ) else null
            finishAndStop(session)
        }
    }

    private fun finishAndStop(session: FpsSession?) {
        if (session != null) runCatching { FpsRecordStore.save(this, session) }
        // 有待重启请求 → 只保存, 不复位状态/不停前台/不停服务(新录制会接管;
        // 否则 stopSelf → onDestroy → scope.cancel 会杀掉正在等 join 的重启协程)
        if (restartPending) return
        _state.value = RecState()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun notify(title: String, text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIF_ID, buildNotif(title, text))
        }
    }

    private fun buildNotif(title: String, text: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CH_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CH_ID, "帧率录制", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }
    }

    companion object {
        private const val CH_ID = "cts_fps_record"
        private const val NOTIF_ID = 0x6CA8
        private const val ACTION_START = "com.ktwo.cts.FPSREC_START"
        private const val ACTION_STOP = "com.ktwo.cts.FPSREC_STOP"
        private const val EXTRA_PKG = "target_pkg"
        private const val EXTRA_LIMIT_SEC = "limit_sec"
        private const val MAX_POINTS = 4 * 3600   // 上限 4 小时, 防止无限增长

        private val _state = MutableStateFlow(RecState())
        /** 录制实时状态: 列表页胶囊/按钮 collectAsState 用。 */
        val state: StateFlow<RecState> = _state.asStateFlow()

        /** 开始录制。pkg 为空时等待用户切换到目标应用后自动锁定; limitSec>0 时到点自动停。 */
        fun start(ctx: Context, pkg: String? = null, limitSec: Int = 0) {
            runCatching {
                ctx.startForegroundService(
                    Intent(ctx, FpsRecordService::class.java)
                        .setAction(ACTION_START)
                        .putExtra(EXTRA_PKG, pkg)
                        .putExtra(EXTRA_LIMIT_SEC, limitSec)
                )
            }
        }

        /** 停止录制(收尾统计并保存)。 */
        fun stop(ctx: Context) {
            runCatching {
                ctx.startService(
                    Intent(ctx, FpsRecordService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}
