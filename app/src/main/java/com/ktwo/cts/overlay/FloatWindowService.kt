package com.ktwo.cts.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.Window
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.ktwo.cts.R
import com.ktwo.cts.data.MetricReader
import com.ktwo.cts.data.Prefs
import com.ktwo.cts.data.ProcSampler
import com.ktwo.cts.data.OverlayType
import com.ktwo.cts.data.PerfIpcSampler
import com.ktwo.cts.data.SysfsReader
import com.ktwo.cts.data.fpsrecord.ThreadSampler
import com.ktwo.cts.service.AppSwitchService
import com.ktwo.cts.service.ForegroundDetector
import kotlinx.coroutines.*

/**
 * 悬浮窗管理器的后端: 单前台服务, 同时管理多个命名监视悬浮窗(1:1 对齐 Metric 原版架构)。
 * 每个悬浮窗宿主在一个 TYPE_APPLICATION_OVERLAY 的 Dialog 窗口里:
 *  - 窗背景 = 全透明圆角 GradientDrawable + decor clipToOutline(Metric 同款), 玻璃着色由内容视图画;
 *  - 卡片窗(负载/进程/线程/温度)无条件 setBackgroundBlurRadius(290), 系统不支持时自动无效;
 *  - FPS 窗模糊走原版状态机(建窗 0 / 展开 290 / 收起触碰 100 / 10.18s 后 7 步淡出), 由窗体经 blurSetter 驱动;
 *  - 原版没有入场/退场/拖动缩放/吸边/落点记忆 —— 一律不做, show/dismiss 直接生效, 拖动裸移动;
 *  - 仅 FPS 窗拖动钳制在屏内(Metric xb1.d), 其余窗可自由移出(原版行为)。
 */
class FloatWindowService : Service() {

    private class Holder(val win: OverlayWindow, val dlg: Dialog)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var wm: WindowManager
    private var dp = 1f
    private val windows = LinkedHashMap<OverlayType, Holder>()
    @Volatile private var activeTypes: Set<OverlayType> = emptySet()
    @Volatile private var textSp = 12f
    private var cellCount = 1
    // Prefs(簇数等)载入完成门闩: 未就绪时 show 请求排队等待
    private val prefsReady = CompletableDeferred<Unit>()

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
            runCatching {
                cellCount = Prefs.cellCount(this@FloatWindowService)
                textSp = Prefs.monTextSize(this@FloatWindowService).toFloat().coerceIn(9f, 22f)
            }
            prefsReady.complete(Unit)
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
        windows.values.forEach { runCatching { it.dlg.dismiss() } }
        windows.clear()
        Thread { runCatching { PerfIpcSampler.stop() } }.start()
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun factory(type: OverlayType): OverlayWindow {
        // 液态玻璃 tint alpha: 比 Met 的 0.55 再透一点(0.46), 让跨窗模糊更明显透出玻璃质感;
        // 系统无跨窗模糊能力时会略偏透(可读性仍够), 有模糊时玻璃感更强。
        val ga = (0.46f * 255).toInt()
        val win = when (type) {
            OverlayType.LOAD    -> LoadOverlayWindow(this, type.accent.toInt(), ga, textSp)
            OverlayType.PROCESS -> ProcessOverlayWindow(this, type.accent.toInt(), ga, textSp)
            OverlayType.THREAD  -> ThreadOverlayWindow(this, type.accent.toInt(), ga, textSp)
            OverlayType.MINI    -> MiniOverlayWindow(this, type.accent.toInt(), 217, textSp)
            OverlayType.FPS     -> FpsOverlayWindow(this, type.accent.toInt(), ga, textSp)
            OverlayType.TEMP    -> TempOverlayWindow(this, type.accent.toInt(), ga, textSp)
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

    // Metric 各窗固定初始落点(dp): 负载/进程/线程 (16,96), 温度 (16,168), FPS (16,64)
    private fun initialPos(type: OverlayType): Pair<Int, Int> = when (type) {
        OverlayType.TEMP -> px(16) to px(168)
        OverlayType.FPS  -> px(16) to px(64)
        else             -> px(16) to px(96)
    }

    /**
     * 每个悬浮窗一个 Dialog 宿主(Metric 原版做法):
     * 窗背景 = 全透明圆角 GradientDrawable(负载 16dp / 迷你 8dp / 其余 18dp) + clipToOutline,
     * 模糊裁剪在这个圆角轮廓内; 玻璃着色由窗体内容视图自己画。
     */
    private fun buildDialog(type: OverlayType, win: OverlayWindow): Dialog {
        val dlg = Dialog(this, android.R.style.Theme_Panel)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setCancelable(false)
        dlg.setCanceledOnTouchOutside(false)
        dlg.setContentView(win.root)
        val w = dlg.window ?: return dlg
        val lp = w.attributes
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        lp.format = PixelFormat.TRANSLUCENT
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        // Metric 卡片窗 flags 精确 = NOT_FOCUSABLE|LAYOUT_IN_SCREEN (0x108)
        lp.flags = lp.flags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        lp.dimAmount = 0f
        if (type == OverlayType.MINI) {
            // Metric 迷你条 flags=0x518: +NOT_TOUCHABLE +LAYOUT_NO_LIMITS, 高 12dp 顶部居中 y=0。
            // 宽度自适应内容(Metric 原版 160 定宽配矢量小图标; 我们用 CPU/GPU 文字标签更宽,
            // 定宽会把最后的 PWR 段裁掉) —— 外观不变, 只保证四段完整显示。
            lp.flags = lp.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT; lp.height = px(12)
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.x = 0; lp.y = 0
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
            lp.gravity = Gravity.TOP or Gravity.START
            val (ix, iy) = initialPos(type)
            lp.x = ix; lp.y = iy
        }
        w.attributes = lp
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        // 全透明圆角窗背景(模糊/裁剪轮廓), 玻璃 tint 在内容层
        w.setBackgroundDrawable(GradientDrawable().apply {
            setColor(0)
            cornerRadius = px(win.windowCornerDp).toFloat()
        })
        w.decorView.setPadding(0, 0, 0, 0)
        w.decorView.clipToOutline = true
        // 模糊: 卡片窗无条件 290(Metric 不查询跨窗模糊开关); FPS 建窗 0, 状态机经 blurSetter 驱动; 迷你条无
        when (type) {
            OverlayType.MINI -> {}
            OverlayType.FPS -> {
                runCatching { w.setBackgroundBlurRadius(0) }
                win.blurSetter = { r -> runCatching { w.setBackgroundBlurRadius(r) } }
            }
            else -> runCatching { w.setBackgroundBlurRadius(290) }
        }
        return dlg
    }

    private fun showWindow(type: OverlayType) {
        // 帧率记录器暂时下线(录制还没修好, 等以后再搞): 即使旧偏好里残留开启也一律不显示,
        // 避免它卡在屏上又无法从选择列表关闭。恢复功能时删掉此行即可。
        // P1-b: FPS 被禁用时, 若当前无任何悬浮窗则停服, 避免空服务常驻(通知不消+每秒采样)。
        if (type == OverlayType.FPS) {
            if (windows.isEmpty()) stopSelf()
            return
        }
        if (windows.containsKey(type)) return
        if (!prefsReady.isCompleted) {
            // 设置未载入完 → 排队; 就绪后复核开关仍开着才建(防冷启动开→关竞态出幽灵窗)
            scope.launch {
                prefsReady.await()
                val stillOn = runCatching {
                    Prefs.enabledOverlayTypes(this@FloatWindowService).contains(type)
                }.getOrDefault(true)
                if (stillOn) withContext(Dispatchers.Main) { showWindow(type) }
                else withContext(Dispatchers.Main) { if (windows.isEmpty()) stopSelf() }
            }
            return
        }
        val win = factory(type)
        val dlg = buildDialog(type, win)
        if (type != OverlayType.MINI) attachDrag(type, win, dlg)
        val ok = runCatching { dlg.show() }.isSuccess
        if (!ok) {
            if (windows.isEmpty()) stopSelf()
            return
        }
        // 迷你条 show 后再钉一次尺寸(Metric oy2.d 同款; 宽度自适应内容防 PWR 段被裁)
        if (type == OverlayType.MINI) runCatching {
            dlg.window?.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, px(12))
        }
        windows[type] = Holder(win, dlg)
        activeTypes = windows.keys.toSet()
        updateNotification()
        // 负载窗需要每核 IPC(Metric 展开面板 IPC 列) → 拉起 root perf 采样(无 root 自动空转)
        if (type == OverlayType.LOAD) scope.launch { runCatching { PerfIpcSampler.start() } }
    }

    private fun hideWindow(type: OverlayType) {
        // 冷启动收到 ACTION_HIDE(startService 会把服务拉起)时该 type 无窗: 若无任何窗则停服,
        // 否则残留一个空转的前台服务(通知不消+每秒采样)。
        val h = windows.remove(type) ?: run { if (windows.isEmpty()) stopSelf(); return }
        activeTypes = windows.keys.toSet()
        if (type == OverlayType.LOAD) scope.launch { runCatching { PerfIpcSampler.stop() } }
        runCatching { h.dlg.dismiss() }   // Metric: 直接 dismiss, 无退场动画
        if (windows.isEmpty()) { ProcSampler.reset(); stopSelf() } else updateNotification()
    }

    private fun syncFromPrefs() {
        scope.launch {
            val want = Prefs.enabledOverlayTypes(this@FloatWindowService)
            withContext(Dispatchers.Main) {
                want.forEach { showWindow(it) }
                if (want.isEmpty() && windows.isEmpty()) stopSelf()
            }
        }
    }

    // 拖动 + 单击/长按分发(对齐 Metric):
    //  - 移动阈值(3dp)内抬手 = tap(负载/FPS 窗展开收起); 500ms 未动 = 长按(负载/温度窗关闭);
    //  - 拖动 = 原始像素直加(Metric moveBy), 仅 FPS 窗按 40×26/268×230dp 钳制在屏内;
    //  - 无拿起缩放/无回弹/无吸边(原版没有)。
    private fun attachDrag(type: OverlayType, win: OverlayWindow, dlg: Dialog) {
        val view = win.root
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longPressed = false

        val longPress = Runnable {
            longPressed = true
            runCatching { win.onLongPress() }
        }

        view.setOnTouchListener { _, ev ->
            val w = dlg.window ?: return@setOnTouchListener false
            val lp = w.attributes
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
                        runCatching { win.onDragged() }   // FPS 收起态: 触碰唤醒玻璃(模糊 100)
                    }
                    lp.x = startX + dx
                    lp.y = startY + dy
                    // 仅 FPS 窗钳制(Metric xb1.d: 收起 40×26 / 展开 268×230)
                    if (type == OverlayType.FPS) {
                        val dm = resources.displayMetrics
                        // FPS 钳制用窗体实际尺寸(展开高按内容量出, 不再硬编 230, 与窗体一致避免底部死区)
                        val fw = if (win.isExpandedWindow) px(268) else px(40)
                        val fh = if (win.isExpandedWindow) w.decorView.height.coerceAtLeast(px(202)) else px(26)
                        lp.x = lp.x.coerceIn(0, (dm.widthPixels - fw).coerceAtLeast(0))
                        lp.y = lp.y.coerceIn(0, (dm.heightPixels - fh).coerceAtLeast(0))
                    }
                    runCatching { w.attributes = lp }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    if (!moved && !longPressed) runCatching { win.onTap() }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPress)
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
            // Metric: 进程窗定高列表 top-30 / 线程窗 96~126dp 滚动列表
            val procs = if (needProc && activeTypes.contains(OverlayType.PROCESS))
                // 候选覆盖全部进程(按应用枚举): 不按 CPU 名次截断, 过滤出"应用"后才不漏(悬浮窗最终最多显示 48 条)
                runCatching { ProcSampler.topProcesses(400) }.getOrNull() else null
            // 先用无障碍刷新前台 pkg/pid, 再列该前台 App 自身进程(pid 固定)的内部 TID 线程
            if (activeTypes.contains(OverlayType.THREAD)) refreshForeground(tick)
            val thrs = if (needProc && activeTypes.contains(OverlayType.THREAD))
                runCatching { ProcSampler.topThreads(24, fgPid) }.getOrNull() else null
            val data = TickData(snap, batt, procs, thrs, fgPkg, fgLabel, fgPid)
            withContext(Dispatchers.Main) {
                windows.values.forEach { runCatching { it.win.onTick(data) } }
            }
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
