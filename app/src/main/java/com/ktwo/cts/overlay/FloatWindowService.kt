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
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.app.NotificationCompat
import com.ktwo.cts.R
import com.ktwo.cts.data.MetricReader
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.ProcSampler
import com.ktwo.cts.data.OverlayType
import com.ktwo.cts.data.SysfsReader
import com.ktwo.cts.data.fpsrecord.ThreadSampler
import com.ktwo.cts.service.AppSwitchService
import com.ktwo.cts.service.ForegroundDetector
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
        // 退场动画中的窗也一并移除, 防 View 泄漏
        leaving.forEach { v ->
            v.animate().cancel()
            runCatching { wm.removeView(v) }
        }
        leaving.clear()
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun factory(type: OverlayType): OverlayWindow {
        val a = type.accent.toInt()
        val win = when (type) {
            OverlayType.LOAD    -> LoadOverlayWindow(this, a, alpha, textSp)
            OverlayType.PROCESS -> ProcessOverlayWindow(this, a, alpha, textSp)
            OverlayType.THREAD  -> ThreadOverlayWindow(this, a, alpha, textSp)
            OverlayType.MINI    -> MiniOverlayWindow(this, a, alpha, textSp)
            OverlayType.FPS     -> FpsOverlayWindow(this, a, alpha, textSp)
            OverlayType.TEMP    -> TempOverlayWindow(this, a, alpha, textSp)
        }
        // 窗内 ✕: 移除该窗并持久化关闭(防 START_STICKY 重建 syncFromPrefs 复活)
        win.onCloseRequest = { requestClose(type) }
        return win
    }

    private fun requestClose(type: OverlayType) {
        scope.launch {
            runCatching { Prefs.setOvlEnabled(this@FloatWindowService, type, false) }
            withContext(Dispatchers.Main) { hideWindow(type) }
        }
    }

    private fun defaultParams(type: OverlayType): WindowManager.LayoutParams {
        val winType = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // 迷你监视器对齐 Metric 原版: 顶部居中贴挖孔区, 点击穿透(NOT_TOUCHABLE), 不可拖动
        if (type == OverlayType.MINI) {
            return WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                winType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0; y = px(2)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
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
            // 注意: 不用 FLAG_BLUR_BEHIND —— 它会把整块桌面/壁纸都模糊(不是只糊卡片)。
            // 玻璃质感靠卡片本身的半透明磨砂底(cardBg)呈现, 不动背后画面。
        }
    }

    private fun showWindow(type: OverlayType) {
        if (windows.containsKey(type)) return
        val win = factory(type)
        val lp = defaultParams(type)
        val fixed = type == OverlayType.MINI   // 迷你条固定顶部居中, 点击穿透不可拖
        if (!fixed) attachDrag(type, win, lp)
        // 入场动画: 普通窗缩放 0.85→1+淡入(带回弹); 迷你条从状态栏上方滑入+淡入
        win.root.alpha = 0f
        if (fixed) {
            win.root.translationY = -px(16).toFloat()
        } else {
            win.root.scaleX = 0.85f
            win.root.scaleY = 0.85f
        }
        val ok = runCatching { wm.addView(win.root, lp) }.isSuccess
        if (!ok) return
        win.root.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(280)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
        windows[type] = Holder(win, lp)
        activeTypes = windows.keys.toSet()
        updateNotification()
        // 异步应用存档落点(迷你条不落点, 恒定顶部居中)
        if (!fixed) scope.launch {
            val (sx, sy) = Prefs.ovlPos(this@FloatWindowService, type)
            if (sx != Int.MIN_VALUE && sy != Int.MIN_VALUE) withContext(Dispatchers.Main) {
                lp.x = sx; lp.y = sy
                runCatching { wm.updateViewLayout(win.root, lp) }
            }
        }
    }

    // 正在做退场动画、还挂在 WindowManager 上的窗根 View(onDestroy 兜底移除, 防泄漏)
    private val leaving = ArrayList<View>()

    private fun hideWindow(type: OverlayType) {
        val h = windows.remove(type) ?: return
        activeTypes = windows.keys.toSet()
        val v = h.win.root
        leaving.add(v)
        // 退场动画: 缩小+淡出后再真正移除; 全部关完才停服务
        v.animate()
            .alpha(0f).scaleX(0.85f).scaleY(0.85f)
            .setDuration(180)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                leaving.remove(v)
                runCatching { wm.removeView(v) }
                if (windows.isEmpty()) { ProcSampler.reset(); stopSelf() } else updateNotification()
            }
            .start()
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

    // 拖动 + 单击分发: 移动阈值(3dp)内抬手视为 tap, 回调窗体 onTap(负载窗用它做收起/展开)。
    // 带点击监听的子 View(✕/标签)会自行消费触摸, 不会走到这里, 天然不冲突。
    // 手感对齐 Metric: 按下轻微放大"拿起", 拖动实时钳在屏幕内, 松手回弹落下。
    private fun attachDrag(type: OverlayType, win: OverlayWindow, lp: WindowManager.LayoutParams) {
        val view = win.root
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longPressed = false

        fun settle() {
            view.animate().scaleX(1f).scaleY(1f)
                .setDuration(220)
                .setInterpolator(OvershootInterpolator(1.5f))
                .start()
        }

        // 长按(500ms 内未拖动): 回调窗体 onLongPress(负载窗用它关闭, Metric 语义)
        val longPress = Runnable {
            longPressed = true
            runCatching { win.onLongPress() }
        }

        view.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y
                    moved = false; longPressed = false
                    view.postDelayed(longPress, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt(); val dy = (ev.rawY - downY).toInt()
                    if (!moved && (kotlin.math.abs(dx) > px(3) || kotlin.math.abs(dy) > px(3))) {
                        moved = true
                        view.removeCallbacks(longPress)
                        // 越过拖动阈值才"拿起", 避免单击也闪一下
                        view.animate().scaleX(1.05f).scaleY(1.05f)
                            .setDuration(120).setInterpolator(AccelerateInterpolator()).start()
                    }
                    // 实时钳制在屏幕范围内, 拖不出去
                    val dm = resources.displayMetrics
                    val maxX = (dm.widthPixels - view.width).coerceAtLeast(0)
                    val maxY = (dm.heightPixels - view.height).coerceAtLeast(0)
                    lp.x = (startX + dx).coerceIn(0, maxX)
                    lp.y = (startY + dy).coerceIn(0, maxY)
                    runCatching { wm.updateViewLayout(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    if (moved) {
                        settle()
                        scope.launch { Prefs.setOvlPos(this@FloatWindowService, type, lp.x, lp.y) }
                    } else if (!longPressed) runCatching { win.onTap() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPress)
                    if (moved) settle()
                    true
                }
                else -> false
            }
        }
    }

    // ── 前台应用信息(线程窗表头): 优先无障碍推送的进程内缓存(零开销), 兜底 ForegroundDetector ──
    private var fgDetector: ForegroundDetector? = null
    private var fgPkg = ""
    private var fgLabel = ""
    private var fgPid = -1

    private fun refreshForeground(tick: Long) {
        runCatching {
            var p = AppSwitchService.foreground
            if ((p.isEmpty() || p == packageName) && tick % 5 == 0L) {
                val d = fgDetector ?: ForegroundDetector(this).also { fgDetector = it }
                p = runCatching { d.currentForegroundPackage() }.getOrNull().orEmpty()
            }
            if (p.isEmpty() || p == packageName) return   // 拿不到/是自己 → 保持上次
            if (p != fgPkg) {
                fgPkg = p
                fgLabel = runCatching {
                    val pm = packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()
                }.getOrDefault("")
                fgPid = runCatching { ThreadSampler.resolvePid(p) }.getOrDefault(-1)
            } else if (fgPid <= 0 && tick % 5 == 0L) {
                fgPid = runCatching { ThreadSampler.resolvePid(p) }.getOrDefault(-1)
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
            // Metric: 进程窗最多 30 行 / 线程窗 96~126dp 滚动列表, 采样条数跟上
            val procs = if (needProc && activeTypes.contains(OverlayType.PROCESS))
                runCatching { ProcSampler.topProcesses(30) }.getOrNull() else null
            val thrs = if (needProc && activeTypes.contains(OverlayType.THREAD))
                runCatching { ProcSampler.topThreads(24) }.getOrNull() else null
            if (activeTypes.contains(OverlayType.THREAD)) refreshForeground(tick)
            val data = TickData(snap, batt, procs, thrs, fgPkg, fgLabel, fgPid)
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
