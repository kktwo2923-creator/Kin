package com.ktwo.cts.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.ktwo.cts.R
import com.ktwo.cts.data.MetricReader
import com.ktwo.cts.data.Prefs
import kotlinx.coroutines.*

/**
 * 悬浮性能监控窗(仿 Metric)。前台服务, 用 WindowManager 画一个小浮窗, 每秒刷新选中指标,
 * 可拖动、可调透明度/字号/显示项(全部读 Prefs)。免 root 优先, 读不到再 root。
 */
class MonitorOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wm: WindowManager? = null
    private var card: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private val rows = LinkedHashMap<String, TextView>()
    private var dp = 1f
    private var cellCount = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        dp = resources.displayMetrics.density
        startForeground(NOTIF_ID, buildNotification())
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        scope.launch {
            cellCount = Prefs.cellCount(this@MonitorOverlayService)
            val items = Prefs.monItems(this@MonitorOverlayService).split(",").filter { it.isNotBlank() }
            val opacity = Prefs.monOpacity(this@MonitorOverlayService)
            val textSize = Prefs.monTextSize(this@MonitorOverlayService)
            val (px0, py0) = Prefs.monPos(this@MonitorOverlayService)
            withContext(Dispatchers.Main) { buildOverlay(items, opacity, textSize, px0, py0) }
            // 刷新循环
            while (isActive) {
                val snap = runCatching { MetricReader.snapshot(cellCount) }.getOrNull()
                if (snap != null) {
                    val texts = items.associateWith { format(it, snap) }
                    withContext(Dispatchers.Main) {
                        texts.forEach { (k, v) -> rows[k]?.text = v }
                    }
                }
                delay(1000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        runCatching { card?.let { wm?.removeView(it) } }
        card = null
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun buildOverlay(items: List<String>, opacity: Int, textSize: Int, savedX: Int, savedY: Int) {
        val wmgr = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = wmgr
        val alpha = (opacity.coerceIn(20, 100) * 255 / 100)
        val bg = GradientDrawable().apply {
            cornerRadius = px(12).toFloat()
            setColor(Color.argb(alpha, 12, 14, 18))   // 近黑玻璃
            setStroke(px(1), Color.argb((alpha * 0.5f).toInt(), 255, 255, 255))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(10), px(7), px(10), px(7))
            background = bg
        }
        rows.clear()
        items.forEach { key ->
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                this.textSize = textSize.toFloat().coerceIn(9f, 22f)
                typeface = Typeface.MONOSPACE
                setLineSpacing(px(2).toFloat(), 1f)
                text = labelOf(key)
            }
            rows[key] = tv
            container.addView(tv)
        }
        card = container

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 默认落点: 右上角(状态栏下方)
            x = if (savedX != Int.MIN_VALUE) savedX
                else (resources.displayMetrics.widthPixels - px(140)).coerceAtLeast(0)
            y = if (savedY != Int.MIN_VALUE) savedY else px(48)
        }
        lp = params
        attachDrag(container, params)
        runCatching { wmgr.addView(container, params) }
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = params.x; startY = params.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt(); val dy = (ev.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > px(3) || kotlin.math.abs(dy) > px(3)) moved = true
                    params.x = startX + dx; params.y = startY + dy
                    runCatching { wm?.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) scope.launch { Prefs.setMonPos(this@MonitorOverlayService, params.x, params.y) }
                    true
                }
                else -> false
            }
        }
    }

    // ── 指标格式化 ──
    private fun labelOf(key: String): String = when (key) {
        "fps" -> "FPS --"; "cpu" -> "CPU --"; "cpuload" -> "占用 --"
        "gpu" -> "GPU --"; "gpuload" -> "GPU --"; "temp" -> "-- ℃"
        "batt" -> "-- %"; "power" -> "-- W"; "ram" -> "RAM --"
        else -> key
    }

    private fun format(key: String, s: MetricReader.Snapshot): String = when (key) {
        "fps"     -> "FPS " + (if (s.fps >= 0) s.fps.toString() else "--")
        "cpu"     -> "CPU " + (s.clusters.maxOfOrNull { it.mhz }?.takeIf { it > 0 }?.let { "${it}MHz" } ?: "--")
        "cpuload" -> "占用 " + (if (s.cpuLoadPct >= 0) "${s.cpuLoadPct}%" else "--")
        "gpu"     -> "GPU " + (if (s.gpuMhz > 0) "${s.gpuMhz}MHz" else "--")
        "gpuload" -> "GPU " + (if (s.gpuLoadPct >= 0) "${s.gpuLoadPct}%" else "--")
        "temp"    -> (if (s.tempC > -100) "${s.tempC}" else "--") + " ℃"
        "batt"    -> (if (s.battPct >= 0) "${s.battPct}" else "--") + " %"
        "power"   -> String.format("%.1f W", s.watt)
        "ram"     -> if (s.ramTotalGb > 0) String.format("RAM %.1f/%.0fG", s.ramUsedGb, s.ramTotalGb) else "RAM --"
        else -> key
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr?.getNotificationChannel(CH_ID) == null) {
            mgr?.createNotificationChannel(
                NotificationChannel(CH_ID, "性能监控悬浮窗", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("性能监控中")
            .setContentText("悬浮窗实时显示 CPU/GPU/温度/帧率")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CH_ID = "cts_monitor"
        private const val NOTIF_ID = 0x6CB0
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, MonitorOverlayService::class.java))
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorOverlayService::class.java))
        }
    }
}
