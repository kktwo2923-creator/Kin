package com.ktwo.cts.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.ktwo.cts.R
import com.ktwo.cts.data.MetricReader
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.ProcSampler
import com.ktwo.cts.data.OverlayType
import com.ktwo.cts.data.SysfsReader
import kotlinx.coroutines.*

/**
 * 悬浮窗管理器的后端: 单前台服务, 用 WindowManager 同时管理多个命名监视悬浮窗(仿 Metric)。
 * 每个 OverlayType 一个独立可拖动浮窗, 各自开关/落点; 单采样循环喂所有窗。
 */
class FloatWindowService : Service() {

    private class Holder(val win: OverlayWindow, val lp: WindowManager.LayoutParams)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var wm: WindowManager
    private var dp = 1f
    private val windows = LinkedHashMap<OverlayType, Holder>()
    @Volatile private var activeTypes: Set<OverlayType> = emptySet()
    @Volatile private var alpha = 217
    @Volatile private var textSp = 12f
    private var cellCount = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        dp = resources.displayMetrics.density
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // 屏幕刷新率作为 FPS 兜底(实测帧率取不到时退回它)
        @Suppress("DEPRECATION")
        MetricReader.fallbackHz = runCatching { wm.defaultDisplay.refreshRate.toInt() }.getOrDefault(-1)
        startForeground(NOTIF_ID, buildNotification())
        scope.launch {
            cellCount = Prefs.cellCount(this@FloatWindowService)
            alpha = (Prefs.monOpacity(this@FloatWindowService).coerceIn(20, 100) * 255 / 100)
            textSp = Prefs.monTextSize(this@FloatWindowService).toFloat().coerceIn(9f, 22f)
            sampleLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val action = intent?.action
        val type = OverlayType.of(intent?.getStringExtra(EXTRA_TYPE))
        when (action) {
            ACTION_SHOW -> type?.let { showWindow(it) }
            ACTION_HIDE -> type?.let { hideWindow(it) }
            else -> syncFromPrefs()   // 服务重建(START_STICKY, intent 可能为空)按 Prefs 恢复
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        windows.values.forEach { runCatching { wm.removeView(it.win.root) } }
        windows.clear()
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun factory(type: OverlayType): OverlayWindow {
        val a = type.accent.toInt()
        return when (type) {
            OverlayType.LOAD    -> LoadOverlayWindow(this, a, alpha, textSp)
            OverlayType.PROCESS -> ProcessOverlayWindow(this, a, alpha, textSp)
            OverlayType.THREAD  -> ThreadOverlayWindow(this, a, alpha, textSp)
            OverlayType.MINI    -> MiniOverlayWindow(this, a, alpha, textSp)
            OverlayType.FPS     -> FpsOverlayWindow(this, a, alpha, textSp)
            OverlayType.TEMP    -> TempOverlayWindow(this, a, alpha, textSp)
        }
    }

    private fun defaultParams(type: OverlayType): WindowManager.LayoutParams {
        val winType = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val idx = OverlayType.entries.indexOf(type).coerceAtLeast(0)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            winType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels - px(150)).coerceAtLeast(0)
            y = px(48 + 90 * idx)
        }
    }

    private fun showWindow(type: OverlayType) {
        if (windows.containsKey(type)) return
        val win = factory(type)
        val lp = defaultParams(type)
        attachDrag(type, win.root, lp)
        val ok = runCatching { wm.addView(win.root, lp) }.isSuccess
        if (!ok) return
        windows[type] = Holder(win, lp)
        activeTypes = windows.keys.toSet()
        updateNotification()
        // 异步应用存档落点
        scope.launch {
            val (sx, sy) = Prefs.ovlPos(this@FloatWindowService, type)
            if (sx != Int.MIN_VALUE && sy != Int.MIN_VALUE) withContext(Dispatchers.Main) {
                lp.x = sx; lp.y = sy
                runCatching { wm.updateViewLayout(win.root, lp) }
            }
        }
    }

    private fun hideWindow(type: OverlayType) {
        val h = windows.remove(type) ?: return
        runCatching { wm.removeView(h.win.root) }
        activeTypes = windows.keys.toSet()
        if (windows.isEmpty()) { ProcSampler.reset(); stopSelf() } else updateNotification()
    }

    private fun syncFromPrefs() {
        scope.launch {
            val want = Prefs.enabledOverlayTypes(this@FloatWindowService)
            withContext(Dispatchers.Main) {
                want.forEach { showWindow(it) }
                if (windows.isEmpty()) stopSelf()
            }
        }
    }

    private fun attachDrag(type: OverlayType, view: View, lp: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt(); val dy = (ev.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > px(3) || kotlin.math.abs(dy) > px(3)) moved = true
                    lp.x = startX + dx; lp.y = startY + dy
                    runCatching { wm.updateViewLayout(view, lp) }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) scope.launch { Prefs.setOvlPos(this@FloatWindowService, type, lp.x, lp.y) }
                    true
                }
                else -> false
            }
        }
    }

    private suspend fun sampleLoop() {
        var tick = 0L
        while (scope.isActive) {
            val snap = runCatching { MetricReader.snapshot(cellCount) }.getOrNull()
            val batt = runCatching { SysfsReader.batteryTempC() }.getOrDefault(-999.0)
            val needProc = tick % 2 == 0L &&
                activeTypes.any { it == OverlayType.PROCESS || it == OverlayType.THREAD }
            val procs = if (needProc && activeTypes.contains(OverlayType.PROCESS))
                runCatching { ProcSampler.topProcesses(8) }.getOrNull() else null
            val thrs = if (needProc && activeTypes.contains(OverlayType.THREAD))
                runCatching { ProcSampler.topThreads(8) }.getOrNull() else null
            val data = TickData(snap, batt, procs, thrs)
            withContext(Dispatchers.Main) { windows.values.forEach { it.win.onTick(data) } }
            tick++
            delay(1000)
        }
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr?.getNotificationChannel(CH_ID) == null) {
            mgr?.createNotificationChannel(
                NotificationChannel(CH_ID, "悬浮监视器", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
        val n = windows.size.coerceAtLeast(1)
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("悬浮监视器运行中")
            .setContentText("$n 个监视悬浮窗")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
    }

    companion object {
        private const val CH_ID = "cts_float"
        private const val NOTIF_ID = 0x6CB1
        private const val ACTION_SHOW = "com.ktwo.cts.FLOAT_SHOW"
        private const val ACTION_HIDE = "com.ktwo.cts.FLOAT_HIDE"
        private const val ACTION_SYNC = "com.ktwo.cts.FLOAT_SYNC"
        private const val EXTRA_TYPE = "type"

        fun show(ctx: Context, type: OverlayType) {
            ctx.startForegroundService(
                Intent(ctx, FloatWindowService::class.java).setAction(ACTION_SHOW)
                    .putExtra(EXTRA_TYPE, type.id)
            )
        }
        fun hide(ctx: Context, type: OverlayType) {
            runCatching {
                ctx.startService(
                    Intent(ctx, FloatWindowService::class.java).setAction(ACTION_HIDE)
                        .putExtra(EXTRA_TYPE, type.id)
                )
            }
        }
        fun sync(ctx: Context) {
            runCatching {
                ctx.startForegroundService(
                    Intent(ctx, FloatWindowService::class.java).setAction(ACTION_SYNC)
                )
            }
        }
    }
}
