package com.ktwo.cts.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.Window
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
 * 悬浮窗管理器的后端: 单前台服务, 同时管理多个命名监视悬浮窗(对齐 Metric 原版架构)。
 * 每个悬浮窗宿主在一个 TYPE_APPLICATION_OVERLAY 的 Dialog 窗口里(Metric 同款),
 * 玻璃模糊用 Window.setBackgroundBlurRadius —— 只糊窗背景圆角内, 绝不糊整屏。
 * 每个 OverlayType 一个独立可拖动浮窗, 各自开关/落点; 单采样循环喂所有窗。
 */
class FloatWindowService : Service() {

    private class Holder(val win: OverlayWindow, val dlg: Dialog)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var wm: WindowManager
    private var dp = 1f
    private val windows = LinkedHashMap<OverlayType, Holder>()
    @Volatile private var activeTypes: Set<OverlayType> = emptySet()
    @Volatile private var alpha = 217
    @Volatile private var textSp = 12f
    private var cellCount = 1
    // 系统支持跨窗口模糊时: 窗背景模糊 290(Metric 同值), 卡底同步调透接近 surface@0.55
    private var blurOn = false
    // Prefs(透明度/字号/簇数)载入完成门闩: 未就绪时 show 请求排队等待, 避免用默认值建窗
    private val prefsReady = CompletableDeferred<Unit>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        dp = resources.displayMetrics.density
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        blurOn = runCatching { wm.isCrossWindowBlurEnabled }.getOrDefault(false)
        // 屏幕刷新率作为 FPS 兜底(实测帧率取不到时退回它)
        @Suppress("DEPRECATION")
        MetricReader.fallbackHz = runCatching { wm.defaultDisplay.refreshRate.toInt() }.getOrDefault(-1)
        startForeground(NOTIF_ID, buildNotification())
        scope.launch {
            runCatching {
                cellCount = Prefs.cellCount(this@FloatWindowService)
                alpha = (Prefs.monOpacity(this@FloatWindowService).coerceIn(20, 100) * 255 / 100)
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
        // 退场动画中的窗也一并关掉, 防泄漏
        leaving.forEach { d ->
            runCatching { d.window?.decorView?.animate()?.cancel() }
            runCatching { d.dismiss() }
        }
        leaving.clear()
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun factory(type: OverlayType): OverlayWindow {
        val a = type.accent.toInt()
        // 有真模糊时玻璃调透(Metric: surface@0.55+blur); 无模糊按用户不透明度直出
        val ea = if (blurOn) (alpha * 0.62f).toInt().coerceAtLeast(64) else alpha
        val win = when (type) {
            OverlayType.LOAD    -> LoadOverlayWindow(this, a, ea, textSp)
            OverlayType.PROCESS -> ProcessOverlayWindow(this, a, ea, textSp)
            OverlayType.THREAD  -> ThreadOverlayWindow(this, a, ea, textSp)
            OverlayType.MINI    -> MiniOverlayWindow(this, a, alpha, textSp)   // 迷你条恒定深色, 不透化
            OverlayType.FPS     -> FpsOverlayWindow(this, a, ea, textSp)
            OverlayType.TEMP    -> TempOverlayWindow(this, a, ea, textSp)
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

    /**
     * 每个悬浮窗一个 Dialog 宿主(Metric 原版做法):
     * 窗背景 = 该窗的玻璃 Drawable(圆角+半透明深底), 模糊裁剪在这个背景的圆角内。
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
        lp.flags = lp.flags or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        lp.dimAmount = 0f
        if (type == OverlayType.MINI) {
            // 迷你条: 顶部居中贴挖孔区, 点击穿透
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.x = 0; lp.y = px(2)
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
            val idx = OverlayType.entries.indexOf(type).coerceAtLeast(0)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.x = (resources.displayMetrics.widthPixels - px(180)).coerceAtLeast(0)
            lp.y = px(48 + 90 * idx)
        }
        w.attributes = lp
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setBackgroundDrawable(win.windowBackground() ?: ColorDrawable(0))
        w.decorView.setPadding(0, 0, 0, 0)
        // Metric 玻璃: 模糊只作用在窗背景圆角内(Window.setBackgroundBlurRadius(290)),
        // 与 FLAG_BLUR_BEHIND 不同, 不会糊到卡片外的整屏。迷你条恒定深色不模糊。
        if (blurOn && type != OverlayType.MINI) {
            runCatching { w.setBackgroundBlurRadius(290) }
        }
        return dlg
    }

    private fun showWindow(type: OverlayType) {
        if (windows.containsKey(type)) return
        if (!prefsReady.isCompleted) {
            // 设置未载入完 → 排队, 载入后回主线程再建(避免用默认透明度/字号建窗)
            scope.launch {
                prefsReady.await()
                withContext(Dispatchers.Main) { showWindow(type) }
            }
            return
        }
        val win = factory(type)
        val dlg = buildDialog(type, win)
        val fixed = type == OverlayType.MINI   // 迷你条固定顶部居中, 点击穿透不可拖
        if (!fixed) attachDrag(type, win, dlg)
        val ok = runCatching { dlg.show() }.isSuccess
        if (!ok) {
            if (windows.isEmpty()) stopSelf()
            return
        }
        // 入场动画作用在 decorView 上(窗背景玻璃随内容一起缩放/淡入):
        // 普通窗缩放 0.85→1+淡入(带回弹); 迷你条从状态栏上方滑入
        dlg.window?.decorView?.let { d ->
            d.alpha = 0f
            if (fixed) d.translationY = -px(16).toFloat() else { d.scaleX = 0.85f; d.scaleY = 0.85f }
            d.animate()
                .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(280)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }
        windows[type] = Holder(win, dlg)
        activeTypes = windows.keys.toSet()
        updateNotification()
        // 展开/收起等导致窗口变大时自动钳回屏幕内(Metric xb1.d 语义: 展开不许伸出屏)
        if (!fixed) dlg.window?.decorView?.addOnLayoutChangeListener { _, l, tt, r, bb, ol, ot, or2, ob ->
            val wPx = r - l; val hPx = bb - tt
            if (wPx == or2 - ol && hPx == ob - ot) return@addOnLayoutChangeListener
            runCatching {
                val w = dlg.window ?: return@runCatching
                val lp = w.attributes
                val dm = resources.displayMetrics
                val nx = lp.x.coerceIn(0, (dm.widthPixels - wPx).coerceAtLeast(0))
                val ny = lp.y.coerceIn(0, (dm.heightPixels - hPx).coerceAtLeast(0))
                if (nx != lp.x || ny != lp.y) { lp.x = nx; lp.y = ny; w.attributes = lp }
            }
        }
        // 异步应用存档落点(迷你条不落点): 带屏幕钳制; 用户已开始拖动则放弃恢复, 不从手上抢窗
        if (!fixed) scope.launch {
            val (sx, sy) = Prefs.ovlPos(this@FloatWindowService, type)
            if (sx != Int.MIN_VALUE && sy != Int.MIN_VALUE) withContext(Dispatchers.Main) {
                runCatching {
                    if (win.userTouched) return@runCatching
                    dlg.window?.let { w ->
                        val lp = w.attributes
                        val dm = resources.displayMetrics
                        val vw = w.decorView.width
                        val vh = w.decorView.height
                        lp.x = sx.coerceIn(0, (dm.widthPixels - vw).coerceAtLeast(0))
                        lp.y = sy.coerceIn(0, (dm.heightPixels - vh).coerceAtLeast(0))
                        w.attributes = lp
                    }
                }
            }
        }
    }

    // 正在做退场动画、还未 dismiss 的 Dialog(onDestroy 兜底关闭, 防泄漏)
    private val leaving = ArrayList<Dialog>()

    private fun hideWindow(type: OverlayType) {
        val h = windows.remove(type) ?: return
        activeTypes = windows.keys.toSet()
        val dlg = h.dlg
        val d = dlg.window?.decorView
        if (d == null) {
            runCatching { dlg.dismiss() }
            if (windows.isEmpty()) { ProcSampler.reset(); stopSelf() } else updateNotification()
            return
        }
        leaving.add(dlg)
        // 摘掉触摸监听: 退场期间触摸不再触发拿起/回弹动画(它们会顶掉 withEndAction 导致永不 dismiss)
        h.win.root.setOnTouchListener(null)
        val finish = Runnable {
            if (!leaving.remove(dlg)) return@Runnable
            runCatching { dlg.dismiss() }
            if (windows.isEmpty()) { ProcSampler.reset(); stopSelf() } else updateNotification()
        }
        // 退场动画: 缩小+淡出后再真正关闭; 300ms 兜底保证动画被打断也一定关窗
        d.animate()
            .alpha(0f).scaleX(0.85f).scaleY(0.85f)
            .setDuration(180)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { finish.run() }
            .start()
        d.postDelayed(finish, 300)
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

    // 拖动 + 单击分发: 移动阈值(3dp)内抬手视为 tap, 回调窗体 onTap(负载/帧率窗用它展开收起)。
    // 带点击监听的子 View(✕/标签)会自行消费触摸, 不会走到这里, 天然不冲突。
    // 手感对齐 Metric: 按下轻微放大"拿起", 拖动实时钳在屏幕内, 松手回弹落下。
    private fun attachDrag(type: OverlayType, win: OverlayWindow, dlg: Dialog) {
        val view = win.root
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false; var longPressed = false
        var snapAnim: android.animation.ValueAnimator? = null

        fun window(): Window? = dlg.window

        fun settle() {
            window()?.decorView?.animate()?.scaleX(1f)?.scaleY(1f)
                ?.setDuration(200)
                ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                ?.start()
        }

        // 长按(500ms 内未拖动): 回调窗体 onLongPress(负载窗用它关闭, Metric 语义)
        val longPress = Runnable {
            longPressed = true
            runCatching { win.onLongPress() }
        }

        view.setOnTouchListener { _, ev ->
            val w = window() ?: return@setOnTouchListener false
            val lp = w.attributes
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnim?.cancel(); snapAnim = null   // 吸边动画途中再抓住 → 停在手上
                    win.userTouched = true                // 存档落点恢复见此放弃, 不从手上抢窗
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
                        // 越过拖动阈值轻微"按压"(缩小; 放大会被 WRAP 窗口表面裁剪出切边)
                        w.decorView.animate().scaleX(0.965f).scaleY(0.965f)
                            .setDuration(120).setInterpolator(AccelerateInterpolator()).start()
                    }
                    // 实时钳制在屏幕范围内, 拖不出去
                    val dm = resources.displayMetrics
                    val vw = w.decorView.width
                    val vh = w.decorView.height
                    val maxX = (dm.widthPixels - vw).coerceAtLeast(0)
                    val maxY = (dm.heightPixels - vh).coerceAtLeast(0)
                    lp.x = (startX + dx).coerceIn(0, maxX)
                    lp.y = (startY + dy).coerceIn(0, maxY)
                    runCatching { w.attributes = lp }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    if (moved) {
                        settle()
                        // Metric: 仅帧率窗松手吸附到最近的左右屏边(lb0 FPS 分支带 edge-snap)
                        if (type == OverlayType.FPS) {
                            val dm = resources.displayMetrics
                            val vw = w.decorView.width
                            val maxX = (dm.widthPixels - vw).coerceAtLeast(0)
                            val toX = if (lp.x + vw / 2 < dm.widthPixels / 2) 0 else maxX
                            snapAnim = android.animation.ValueAnimator.ofInt(lp.x, toX).apply {
                                duration = 300
                                interpolator = android.view.animation.PathInterpolator(0.15f, 1f, 0.3f, 1f)
                                addUpdateListener {
                                    lp.x = it.animatedValue as Int
                                    runCatching { w.attributes = lp }
                                }
                                addListener(object : android.animation.AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(a: android.animation.Animator) {
                                        scope.launch {
                                            Prefs.setOvlPos(this@FloatWindowService, type, lp.x, lp.y)
                                        }
                                    }
                                })
                                start()
                            }
                        } else {
                            scope.launch { Prefs.setOvlPos(this@FloatWindowService, type, lp.x, lp.y) }
                        }
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
